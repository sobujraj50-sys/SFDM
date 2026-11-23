package com.sfdm.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sfdm.app.R
import com.sfdm.app.SfdmApp
import com.sfdm.app.data.DownloadEntity
import com.sfdm.app.data.DownloadStatus
import com.sfdm.app.engine.LiveStats
import com.sfdm.app.util.Format
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps downloads alive in the background.
 * Shows one summary notification (required for the foreground service) plus
 * one notification per running download with Pause / Cancel buttons.
 * Stops itself when nothing is downloading or waiting.
 */
class DownloadService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false
    private val shown = HashSet<Long>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val manager = SfdmApp.get(this).manager
        when (intent?.action) {
            ACTION_PAUSE_ALL -> manager.pauseAll()
        }
        if (!started) {
            started = true
            goForeground(summary("SFDM", "ডাউনলোড প্রস্তুত হচ্ছে…", 0, 0, true))
            acquireLocks()
            observe()
        }
        return START_STICKY
    }

    private fun goForeground(n: Notification) {
        ServiceCompat.startForeground(
            this, Notifications.FOREGROUND_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun observe() {
        val manager = SfdmApp.get(this).manager
        val nm = getSystemService(NotificationManager::class.java)
        lifecycleScope.launch {
            combine(manager.downloads, manager.live) { list, live -> list to live }.collect { (list, live) ->
                val active = list.filter { it.status.isActive }
                val running = active.filter { live.containsKey(it.id) }

                // Per-download notifications: post for running ones, remove the rest.
                val runningIds = running.map { it.id }.toSet()
                (shown - runningIds).forEach { nm.cancel(Notifications.progressId(it)) }
                shown.retainAll(runningIds)
                running.forEach { d ->
                    nm.notify(Notifications.progressId(d.id), item(d, live[d.id]))
                    shown += d.id
                }

                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }

                val waiting = active.count { it.status == DownloadStatus.WAITING_NETWORK }
                val queued = active.count { it.status == DownloadStatus.QUEUED }
                val speed = running.sumOf { live[it.id]?.speedBps ?: 0L }
                val done = active.sumOf { live[it.id]?.downloaded ?: it.downloadedBytes }
                val total = active.sumOf { if (it.totalBytes > 0) it.totalBytes else 0L }
                val title = when {
                    running.isNotEmpty() -> "${running.size}টি ডাউনলোড চলছে • ${Format.speed(speed)}"
                    waiting > 0 -> "নেটওয়ার্কের অপেক্ষায়"
                    else -> "সারিতে অপেক্ষমাণ"
                }
                val text = buildString {
                    if (total > 0) append("${Format.bytes(done)} / ${Format.bytes(total)}")
                    if (queued > 0) { if (isNotEmpty()) append(" • "); append("${queued}টি সারিতে") }
                    if (waiting > 0) { if (isNotEmpty()) append(" • "); append("${waiting}টি নেটের অপেক্ষায়") }
                }
                val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                nm.notify(Notifications.FOREGROUND_ID, summary(title, text, percent, 100, total <= 0 && running.isNotEmpty()))
            }
        }
    }

    private fun item(d: DownloadEntity, s: LiveStats?): Notification {
        val done = s?.downloaded ?: d.downloadedBytes
        val total = if (d.totalBytes > 0) d.totalBytes else s?.total ?: -1
        val speed = s?.speedBps ?: 0
        val text = buildString {
            append(Format.speed(speed))
            if (total > 0) {
                append(" • ${Format.bytes(done)} / ${Format.bytes(total)}")
                Format.eta(total - done, speed).takeIf { it.isNotEmpty() }?.let { append(" • $it") }
            } else {
                append(" • ${Format.bytes(done)}")
            }
        }
        val percent = if (total > 0) ((done * 100) / total).toInt() else 0
        return NotificationCompat.Builder(this, Notifications.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(d.fileName.ifBlank { d.url })
            .setContentText(text)
            .setSubText(if (total > 0) "$percent%" else null)
            .setProgress(100, percent, total <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setGroup(Notifications.GROUP)
            .setContentIntent(Notifications.openAppIntent(this))
            .addAction(0, "থামান", NotificationActionReceiver.intent(this, NotificationActionReceiver.ACTION_PAUSE, d.id))
            .addAction(0, "বাতিল", NotificationActionReceiver.intent(this, NotificationActionReceiver.ACTION_CANCEL, d.id))
            .build()
    }

    private fun summary(title: String, text: String, progress: Int, max: Int, indeterminate: Boolean): Notification {
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(max, progress, indeterminate)
            .setGroup(Notifications.GROUP)
            .setGroupSummary(true)
            .setContentIntent(Notifications.openAppIntent(this))
            .addAction(0, "সব থামান", pauseIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SFDM:download").apply {
            setReferenceCounted(false); acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SFDM:wifi").apply {
            setReferenceCounted(false); acquire()
        }
    }

    override fun onDestroy() {
        val nm = getSystemService(NotificationManager::class.java)
        shown.forEach { nm.cancel(Notifications.progressId(it)) }
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PAUSE_ALL = "com.sfdm.app.PAUSE_ALL"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }
        }
    }
}
