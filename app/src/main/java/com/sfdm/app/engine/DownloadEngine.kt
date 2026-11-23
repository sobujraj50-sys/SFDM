package com.sfdm.app.engine

import com.sfdm.app.data.Category
import com.sfdm.app.data.DownloadDao
import com.sfdm.app.data.DownloadEntity
import com.sfdm.app.data.DownloadStatus
import com.sfdm.app.data.NetworkMode
import com.sfdm.app.data.SegmentEntity
import com.sfdm.app.network.Lane
import com.sfdm.app.network.NetworkProvider
import com.sfdm.app.network.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Live numbers shown in the UI and notification. */
data class LiveStats(
    val downloaded: Long,
    val total: Long,
    val speedBps: Long,
    val connections: Int,
)

sealed class EngineResult {
    data object Completed : EngineResult()
    data class WaitingNetwork(val message: String) : EngineResult()
    data class Failed(val message: String) : EngineResult()
}

/** The network a worker was using disappeared; another lane may take over its work. */
private class LaneDeadException : IOException("network lost")

/** A byte range from start to end (inclusive). The end can shrink when the range is split (dynamic segmentation). */
private class Seg(val start: Long, end: Long, downloaded: Long) {
    val end = AtomicLong(end)
    val downloaded = AtomicLong(downloaded)
    val claimed = AtomicBoolean(false)
    fun pos() = start + downloaded.get()
    fun remaining(): Long {
        val e = end.get()
        return if (e == Long.MAX_VALUE) Long.MAX_VALUE else e - pos() + 1
    }
}

/**
 * Downloads one file with several parallel connections.
 *
 * - The file is split into segments, each fetched with an HTTP Range request.
 * - When a connection finishes its segment it takes half of the largest remaining one
 *   (dynamic segmentation), so fast connections and fast networks naturally do more work.
 * - Progress of every segment is saved, so the download resumes after pause, crash or reboot.
 * - In Boost mode workers are spread over Wi-Fi and mobile data at the same time.
 */
class DownloadEngine(
    private val initial: DownloadEntity,
    private val dao: DownloadDao,
    private val net: NetworkProvider,
    private val storage: Storage,
    private val onStats: (LiveStats) -> Unit,
) {
    private companion object {
        const val MIN_SEGMENT = 1L shl 20        // 1 MB: don't create smaller starting segments
        const val MIN_SPLIT = 512L * 1024          // only steal work if at least 2 x 512 KB remain
        const val BUFFER = 64 * 1024
        const val MAX_RETRIES = 6
    }

    private var d = initial
    /** The URL actually fetched: the direct file behind a page link (refreshed on every start). */
    private var fetchUrl = initial.url
    private var suggestedName: String? = null
    private val segLock = Any()
    private val segs = mutableListOf<Seg>()
    private var splittable = false
    private val wifiBytes = AtomicLong(initial.wifiBytes)
    private val mobileBytes = AtomicLong(initial.mobileBytes)
    @Volatile private var autoTransport: Transport? = null

    suspend fun execute(): EngineResult = withContext(Dispatchers.IO) {
        dao.setStatus(d.id, DownloadStatus.CONNECTING)
        val acquired = mutableListOf<Transport>()
        try {
            val lanes = resolveLanes(d.networkMode, acquired)
                ?: return@withContext EngineResult.WaitingNetwork(waitingMessage(d.networkMode))
            autoTransport = net.defaultTransport()

            val probe = try {
                val resolved = LinkResolver.resolve((lanes.firstOrNull { net.isAlive(it) } ?: lanes[0]).client, d.url, d.quality)
                fetchUrl = resolved.url
                suggestedName = resolved.suggestedName
                probeWithRetry(lanes)
            } catch (e: ExtractionException) {
                return@withContext EngineResult.Failed(e.message ?: "ভিডিও পাওয়া যায়নি")
            } catch (e: FatalHttpException) {
                return@withContext EngineResult.Failed(e.message ?: "সার্ভার ত্রুটি")
            } catch (e: IOException) {
                return@withContext if (lanes.none { net.isAlive(it) }) {
                    EngineResult.WaitingNetwork(waitingMessage(d.networkMode))
                } else {
                    EngineResult.Failed("সার্ভারে সংযোগ করা যায়নি: ${e.message ?: ""}".trim())
                }
            }

            if (probe.mimeType?.startsWith("text/html") == true) {
                return@withContext EngineResult.Failed(
                    "এটা একটা ওয়েবপেজের লিংক, সরাসরি ফাইলের লিংক না। " +
                        "ওয়েবপেজ থেকে ভিডিও নামানোর সুবিধা বিল্ট-ইন ব্রাউজারে আসছে।",
                )
            }
            prepareTarget(probe)
            prepareSegments()
            dao.setStatus(d.id, DownloadStatus.RUNNING)
            runWorkers(lanes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FatalHttpException) {
            EngineResult.Failed(e.message ?: "সার্ভার ত্রুটি")
        } catch (e: IOException) {
            if (!net.hasInternet()) EngineResult.WaitingNetwork(waitingMessage(d.networkMode))
            else EngineResult.Failed(e.message ?: "ডাউনলোড ব্যর্থ")
        } catch (e: Exception) {
            EngineResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            acquired.forEach { net.release(it) }
        }
    }

    // ---------------------------------------------------------------- networks

    private suspend fun resolveLanes(mode: NetworkMode, acquired: MutableList<Transport>): List<Lane>? {
        suspend fun take(t: Transport, timeout: Long): Lane? {
            val n = net.acquire(t, timeout) ?: return null
            acquired += t
            return net.laneFor(n, t)
        }
        return when (mode) {
            NetworkMode.AUTO -> if (net.hasInternet()) listOf(net.autoLane()) else null
            NetworkMode.WIFI_ONLY -> take(Transport.WIFI, 4_000)?.let { listOf(it) }
            NetworkMode.MOBILE_ONLY -> take(Transport.CELLULAR, 10_000)?.let { listOf(it) }
            NetworkMode.BOOST -> listOfNotNull(
                take(Transport.WIFI, 4_000),
                take(Transport.CELLULAR, 10_000),
            ).ifEmpty { null }
        }
    }

    private fun waitingMessage(mode: NetworkMode) = when (mode) {
        NetworkMode.AUTO -> "ইন্টারনেট সংযোগের অপেক্ষায়"
        NetworkMode.WIFI_ONLY -> "Wi-Fi-এর অপেক্ষায়"
        NetworkMode.MOBILE_ONLY -> "মোবাইল ডেটার অপেক্ষায় — মোবাইল ডেটা চালু আছে কিনা দেখুন"
        NetworkMode.BOOST -> "নেটওয়ার্কের অপেক্ষায়"
    }

    private suspend fun probeWithRetry(lanes: List<Lane>): ProbeResult {
        var attempt = 0
        while (true) {
            val lane = lanes.firstOrNull { net.isAlive(it) } ?: throw IOException("network lost")
            try {
                return Probe.run(lane.client, fetchUrl)
            } catch (e: FatalHttpException) {
                // Some servers reject Range requests outright; ask again without it.
                if (e.code == 400 || e.code == 416) return Probe.run(lane.client, fetchUrl, useRange = false)
                if (Probe.isFatal(e.code) || ++attempt >= 3) throw e
            } catch (e: IOException) {
                if (++attempt >= 3) throw e
            }
            delay(1_500L * attempt)
        }
    }

    // ---------------------------------------------------------------- file + segments

    private var resetSegments = false

    private suspend fun prepareTarget(p: ProbeResult) {
        val existing = d.target?.takeIf { storage.exists(it) }
        if (existing == null) {
            val name = Storage.sanitize(d.fileName.ifBlank { suggestedName ?: p.fileName ?: "download" })
            val category = Category.detect(name, p.mimeType)
            val target = storage.create(name, p.mimeType, category)
            d = d.copy(
                fileName = name,
                category = category.name,
                target = target,
                mimeType = p.mimeType,
                totalBytes = p.totalBytes,
                supportsRange = p.supportsRange,
                etag = p.etag,
                lastModified = p.lastModified,
                downloadedBytes = 0,
                wifiBytes = 0,
                mobileBytes = 0,
                error = null,
            )
            wifiBytes.set(0); mobileBytes.set(0)
            resetSegments = true
        } else {
            val changed = (d.totalBytes > 0 && p.totalBytes != d.totalBytes) ||
                (d.etag != null && p.etag != null && d.etag != p.etag)
            if (changed || !p.supportsRange || !d.supportsRange) {
                // The file on the server changed, or it can't resume: start this file over.
                storage.truncate(existing)
                d = d.copy(
                    totalBytes = p.totalBytes,
                    supportsRange = p.supportsRange,
                    etag = p.etag,
                    lastModified = p.lastModified,
                    downloadedBytes = 0,
                    error = null,
                )
                resetSegments = true
            } else {
                d = d.copy(error = null)
            }
        }
        dao.update(d.copy(status = DownloadStatus.CONNECTING))
    }

    private suspend fun prepareSegments() {
        splittable = d.supportsRange && d.totalBytes > 0
        val saved = if (resetSegments || !splittable) emptyList() else dao.segments(d.id)
        synchronized(segLock) {
            segs.clear()
            if (saved.isNotEmpty()) {
                saved.forEach { segs += Seg(it.start, it.end, it.downloaded) }
            } else if (splittable) {
                val total = d.totalBytes
                val n = d.connections.coerceIn(1, 32).toLong().coerceAtMost(maxOf(1L, total / MIN_SEGMENT)).toInt()
                val size = total / n
                for (i in 0 until n) {
                    val s = i * size
                    val e = if (i == n - 1) total - 1 else s + size - 1
                    segs += Seg(s, e, 0)
                }
            } else {
                segs += Seg(0, if (d.totalBytes > 0) d.totalBytes - 1 else Long.MAX_VALUE, 0)
            }
        }
        saveSegments()
    }

    private fun claimNext(): Seg? = synchronized(segLock) {
        segs.firstOrNull { !it.claimed.get() && it.remaining() > 0 }?.let {
            it.claimed.set(true)
            return it
        }
        if (!splittable) return null
        val victim = segs.filter { it.claimed.get() && it.remaining() >= MIN_SPLIT * 2 }
            .maxByOrNull { it.remaining() } ?: return null
        val pos = victim.pos()
        val end = victim.end.get()
        val mid = pos + (end - pos + 1) / 2
        val fresh = Seg(mid, end, 0).also { it.claimed.set(true) }
        victim.end.set(mid - 1)
        segs += fresh
        fresh
    }

    private fun downloadedSum(): Long = synchronized(segLock) {
        segs.sumOf { s ->
            val e = s.end.get()
            if (e == Long.MAX_VALUE) s.downloaded.get() else minOf(s.downloaded.get(), e - s.start + 1)
        }
    }

    private suspend fun saveSegments() {
        val snapshot = synchronized(segLock) {
            segs.map { SegmentEntity(downloadId = d.id, start = it.start, end = it.end.get(), downloaded = it.downloaded.get()) }
        }
        dao.replaceSegments(d.id, snapshot)
        dao.setProgress(d.id, downloadedSum(), wifiBytes.get(), mobileBytes.get())
    }

    // ---------------------------------------------------------------- workers

    private suspend fun runWorkers(lanes: List<Lane>): EngineResult {
        val target = d.target ?: return EngineResult.Failed("ফাইল পাওয়া যায়নি")
        val out = storage.open(target)
        val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
        val workerCount = if (splittable) maxOf(d.connections.coerceIn(1, 32), lanes.size) else 1
        val activeWorkers = AtomicLong(0)

        try {
            coroutineScope {
                // Cancels in-flight HTTP calls immediately on pause.
                val watcher = launch {
                    try { awaitCancellation() } finally { calls.forEach { it.cancel() } }
                }
                val ticker = launch {
                    var last = downloadedSum()
                    var speed = 0.0
                    var tick = 0
                    while (true) {
                        delay(1_000)
                        autoTransport = net.defaultTransport()
                        val now = downloadedSum()
                        val inst = (now - last).coerceAtLeast(0).toDouble()
                        speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                        last = now
                        onStats(LiveStats(now, d.totalBytes, speed.toLong(), activeWorkers.get().toInt()))
                        dao.setProgress(d.id, now, wifiBytes.get(), mobileBytes.get())
                        if (++tick % 3 == 0) saveSegments()
                    }
                }
                (0 until workerCount).map { i ->
                    async {
                        activeWorkers.incrementAndGet()
                        try { worker(lanes[i % lanes.size], out, calls) } finally { activeWorkers.decrementAndGet() }
                    }
                }.awaitAll()
                ticker.cancel()
                watcher.cancel()
            }
        } finally {
            out.close()
            withContext(NonCancellable) { saveSegments() }
        }

        val complete = synchronized(segLock) { segs.all { it.remaining() <= 0 } }
        if (!complete) return EngineResult.WaitingNetwork(waitingMessage(d.networkMode))

        storage.finish(target)
        val total = if (d.totalBytes > 0) d.totalBytes else downloadedSum()
        d = d.copy(
            status = DownloadStatus.COMPLETED,
            totalBytes = total,
            downloadedBytes = total,
            wifiBytes = wifiBytes.get(),
            mobileBytes = mobileBytes.get(),
            finishedAt = System.currentTimeMillis(),
            error = null,
        )
        dao.update(d)
        dao.deleteSegments(d.id)
        onStats(LiveStats(total, total, 0, 0))
        return EngineResult.Completed
    }

    private suspend fun worker(lane: Lane, out: OpenTarget, calls: MutableSet<Call>) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val seg = claimNext() ?: return
            try {
                fetch(seg, lane, out, calls)
            } catch (e: LaneDeadException) {
                return // leave the segment for a worker on another network
            } finally {
                seg.claimed.set(false)
            }
        }
    }

    private fun count(lane: Lane, n: Int) {
        when (lane.fixedTransport ?: autoTransport) {
            Transport.WIFI -> wifiBytes.addAndGet(n.toLong())
            Transport.CELLULAR -> mobileBytes.addAndGet(n.toLong())
            else -> Unit
        }
    }

    private suspend fun fetch(seg: Seg, lane: Lane, out: OpenTarget, calls: MutableSet<Call>) {
        var failures = 0
        val buf = ByteArray(BUFFER)
        while (seg.remaining() > 0) {
            currentCoroutineContext().ensureActive()
            val from = seg.pos()
            val rb = Request.Builder()
                .url(fetchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "identity")
            if (splittable) {
                rb.header("Range", "bytes=$from-${seg.end.get()}")
                d.etag?.let { rb.header("If-Range", it) }
            }
            val call = lane.client.newCall(rb.build())
            calls += call
            var eof = false
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (Probe.isFatal(resp.code)) throw Probe.httpError(resp.code)
                        throw IOException("HTTP ${resp.code}")
                    }
                    if (splittable && resp.code != 206) {
                        throw FatalHttpException(resp.code, "সার্ভারের ফাইল বদলে গেছে — আবার শুরু করুন")
                    }
                    val input = resp.body?.byteStream() ?: throw IOException("empty body")
                    while (true) {
                        val r = input.read(buf)
                        if (r == -1) { eof = true; break }
                        currentCoroutineContext().ensureActive()
                        val pos = seg.pos()
                        val allowed = minOf(r.toLong(), seg.end.get() - pos + 1).toInt()
                        if (allowed <= 0) break
                        val bb = ByteBuffer.wrap(buf, 0, allowed)
                        var p = pos
                        while (bb.hasRemaining()) p += out.channel.write(bb, p)
                        seg.downloaded.addAndGet(allowed.toLong())
                        count(lane, allowed)
                        failures = 0
                        if (allowed < r) break
                    }
                }
                if (eof && seg.end.get() == Long.MAX_VALUE) {
                    // Unknown size: the stream ending means the file is complete.
                    seg.end.set(seg.pos() - 1)
                    return
                }
                if (eof && seg.remaining() > 0) throw IOException("সংযোগ আগেভাগে বন্ধ হয়ে গেছে")
            } catch (e: CancellationException) {
                throw e
            } catch (e: FatalHttpException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (!net.isAlive(lane)) throw LaneDeadException()
                if (++failures > MAX_RETRIES) throw e
                if (!splittable) {
                    // This server can't resume, so retrying means starting the stream over.
                    seg.downloaded.set(0)
                }
                delay(minOf(8_000L, 1_000L shl (failures - 1)))
            } finally {
                calls -= call
            }
        }
    }
}
