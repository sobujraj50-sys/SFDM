package com.sfdm.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sfdm.app.SfdmApp
import com.sfdm.app.data.AppSettings
import com.sfdm.app.data.DownloadEntity
import com.sfdm.app.data.NetworkMode
import com.sfdm.app.engine.LiveStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadItem(val entity: DownloadEntity, val live: LiveStats?) {
    val downloaded: Long get() = live?.downloaded ?: entity.downloadedBytes
    val total: Long get() = if (entity.totalBytes > 0) entity.totalBytes else live?.total ?: -1
    val speed: Long get() = live?.speedBps ?: 0
    val connections: Int get() = live?.connections ?: 0
}

enum class Filter { ALL, ACTIVE, DONE }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val sfdm = SfdmApp.get(app)
    private val manager = sfdm.manager

    val filter = MutableStateFlow(Filter.ALL)

    val items: StateFlow<List<DownloadItem>> =
        combine(manager.downloads, manager.live, filter) { list, live, f ->
            list.filter {
                when (f) {
                    Filter.ALL -> true
                    Filter.ACTIVE -> it.status.isActive || it.status.name == "PAUSED" || it.status.name == "FAILED"
                    Filter.DONE -> it.status.name == "COMPLETED"
                }
            }.map { DownloadItem(it, live[it.id]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = sfdm.settings.state

    fun add(url: String, name: String?, connections: Int, mode: NetworkMode, quality: String?) {
        viewModelScope.launch { manager.add(url, name, connections, mode, quality) }
    }

    suspend fun inspect(url: String) = manager.inspect(url)

    fun pause(id: Long) { manager.pause(id) }
    fun resume(id: Long) { manager.resume(id) }
    fun delete(id: Long, deleteFile: Boolean) { manager.delete(id, deleteFile) }
    fun setNetworkMode(id: Long, mode: NetworkMode) { manager.setNetworkMode(id, mode) }
    fun updateSettings(s: AppSettings) { sfdm.settings.update { s } }
    fun shareableUri(target: String) = manager.storage.shareableUri(target)
}
