package com.sfdm.app.engine

import android.content.Context
import com.sfdm.app.data.AppDatabase
import com.sfdm.app.data.DownloadEntity
import com.sfdm.app.data.DownloadStatus
import com.sfdm.app.data.NetworkMode
import com.sfdm.app.data.SettingsStore
import com.sfdm.app.network.NetworkProvider
import com.sfdm.app.service.DownloadService
import com.sfdm.app.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the download queue: starts up to N downloads at once, pauses/resumes them,
 * and automatically restarts downloads that were waiting for a network.
 */
class DownloadManager(
    private val context: Context,
    private val db: AppDatabase,
    private val net: NetworkProvider,
    private val settings: SettingsStore,
) {
    private val dao = db.downloads()
    val storage = Storage(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val scheduleLock = Mutex()

    private val _live = MutableStateFlow<Map<Long, LiveStats>>(emptyMap())
    val live: StateFlow<Map<Long, LiveStats>> = _live.asStateFlow()

    val downloads = dao.observeAll()

    init {
        scope.launch {
            // Downloads that were running when the app was killed go back into the queue.
            dao.getActive().forEach {
                if (it.status == DownloadStatus.CONNECTING || it.status == DownloadStatus.RUNNING) {
                    dao.setStatus(it.id, DownloadStatus.QUEUED)
                }
            }
            if (dao.getActive().isNotEmpty()) DownloadService.start(context)
        }
        scope.launch {
            net.changes.collect { schedule(includeWaiting = true) }
        }
        scope.launch {
            // Safety net: retry waiting downloads every 20 seconds.
            while (true) {
                delay(20_000)
                schedule(includeWaiting = true)
            }
        }
    }

    suspend fun add(url: String, fileName: String?, connections: Int?, mode: NetworkMode?): Long {
        val s = settings.state.value
        val id = dao.insert(
            DownloadEntity(
                url = url.trim(),
                fileName = fileName?.trim().orEmpty(),
                connections = (connections ?: s.defaultConnections).coerceIn(1, 32),
                networkMode = mode ?: s.defaultNetworkMode,
            ),
        )
        DownloadService.start(context)
        schedule(includeWaiting = false)
        return id
    }

    fun pause(id: Long) = scope.launch {
        jobs.remove(id)?.cancelAndJoin()
        dao.setStatus(id, DownloadStatus.PAUSED)
        _live.update { it - id }
        schedule(includeWaiting = false)
    }

    fun resume(id: Long) = scope.launch {
        dao.setStatus(id, DownloadStatus.QUEUED)
        DownloadService.start(context)
        schedule(includeWaiting = false)
    }

    fun pauseAll() = scope.launch {
        dao.getActive().forEach { d ->
            jobs.remove(d.id)?.cancelAndJoin()
            dao.setStatus(d.id, DownloadStatus.PAUSED)
        }
        _live.value = emptyMap()
    }

    fun setNetworkMode(id: Long, mode: NetworkMode) = scope.launch {
        val wasRunning = jobs.containsKey(id)
        jobs.remove(id)?.cancelAndJoin()
        val d = dao.get(id) ?: return@launch
        dao.update(d.copy(networkMode = mode, status = if (d.status.isActive || wasRunning) DownloadStatus.QUEUED else d.status))
        schedule(includeWaiting = true)
    }

    fun delete(id: Long, deleteFile: Boolean) = scope.launch {
        jobs.remove(id)?.cancelAndJoin()
        val d = dao.get(id)
        if (d?.target != null && (deleteFile || d.status != DownloadStatus.COMPLETED)) {
            storage.delete(d.target)
        }
        dao.delete(id)
        _live.update { it - id }
        schedule(includeWaiting = false)
    }

    suspend fun hasActive(): Boolean = dao.getActive().isNotEmpty()

    fun runningCount(): Int = jobs.size

    private suspend fun schedule(includeWaiting: Boolean) = scheduleLock.withLock {
        val max = settings.state.value.maxParallelDownloads
        val candidates = dao.getActive().filter { d ->
            !jobs.containsKey(d.id) && (
                d.status == DownloadStatus.QUEUED ||
                    (includeWaiting && d.status == DownloadStatus.WAITING_NETWORK)
                )
        }
        for (d in candidates) {
            if (jobs.size >= max) break
            start(d)
        }
    }

    private fun start(d: DownloadEntity) {
        val job = scope.launch {
            val engine = DownloadEngine(d, dao, net, storage) { stats ->
                _live.update { it + (d.id to stats) }
            }
            val result = try {
                engine.execute()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
            withContext(NonCancellable) {
                when (result) {
                    is EngineResult.Completed -> {
                        dao.get(d.id)?.let { Notifications.completed(context, it) }
                    }
                    is EngineResult.WaitingNetwork ->
                        dao.setStatus(d.id, DownloadStatus.WAITING_NETWORK, result.message)
                    is EngineResult.Failed -> {
                        dao.setStatus(d.id, DownloadStatus.FAILED, result.message)
                        dao.get(d.id)?.let { Notifications.failed(context, it) }
                    }
                }
                _live.update { it - d.id }
            }
        }
        jobs[d.id] = job
        job.invokeOnCompletion {
            jobs.remove(d.id, job)
            scope.launch { schedule(includeWaiting = false) }
        }
    }
}
