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
import com.sfdm.app.data.DownloadStatus
import com.sfdm.app.util.Format
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps downloads alive while the app is in the background and shows one summary notification.
 * Stops itself when nothing is downloading or waiting.
 */
class DownloadService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_PAUSE_ALL) {
            SfdmApp.get(this).manager.pauseAll()
        }
        if (!started) {
            started = true
            goForeground(build("SFDM", "ডাউনলোড প্রস্তুত হচ্ছে…", 0, 0, true))
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
        lifecycleScope.launch {
            combine(manager.downloads, manager.live) { list, live -> list to live }.collect { (list, live) ->
                val active = list.filter { it.status.isActive }
                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                val running = active.filter { live.containsKey(it.id) }
                val waiting = active.count { it.status == DownloadStatus.WAITING_NETWORK }
                val speed = running.sumOf { live[it.id]?.speedBps ?: 0L }
                val done = active.sumOf { live[it.id]?.downloaded ?: it.downloadedBytes }
                val total = active.sumOf { if (it.totalBytes > 0) it.totalBytes else 0L }
                val title = when {
                    running.size == 1 -> running[0].fileName.ifBlank { "ডাউনলোড চলছে" }
                    running.isNotEmpty() -> "${running.size}টি ডাউনলোড চলছে"
                    waiting > 0 -> "নেটওয়ার্কের অপেক্ষায়"
                    else -> "সারিতে অপেক্ষমাণ"
                }
                val text = buildString {
                    if (running.isNotEmpty()) append(Format.speed(speed))
                    if (total > 0) {
                        if (isNotEmpty()) append(" • ")
                        append("${Format.bytes(done)} / ${Format.bytes(total)}")
                    }
                    if (waiting > 0 && running.isNotEmpty()) append(" • ${waiting}টি অপেক্ষায়")
                }
                val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                val n = build(title, text, percent, 100, total <= 0 && running.isNotEmpty())
                getSystemService(NotificationManager::class.java).notify(Notifications.FOREGROUND_ID, n)
            }
        }
    }

    private fun build(title: String, text: String, progress: Int, max: Int, indeterminate: Boolean): Notification {
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
