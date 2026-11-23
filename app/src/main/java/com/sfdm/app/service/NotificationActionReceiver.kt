package com.sfdm.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sfdm.app.SfdmApp

/** Handles Pause / Resume / Cancel buttons on download notifications. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        val manager = SfdmApp.get(context).manager
        when (intent.action) {
            ACTION_PAUSE -> manager.pause(id)
            ACTION_RESUME -> {
                androidx.core.app.NotificationManagerCompat.from(context).cancel((1000 + id).toInt())
                manager.resume(id)
            }
            ACTION_CANCEL -> manager.delete(id, deleteFile = true)
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.sfdm.app.action.PAUSE"
        const val ACTION_RESUME = "com.sfdm.app.action.RESUME"
        const val ACTION_CANCEL = "com.sfdm.app.action.CANCEL"
        private const val EXTRA_ID = "id"

        fun intent(context: Context, action: String, id: Long): PendingIntent {
            val i = Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_ID, id)
            val slot = when (action) { ACTION_PAUSE -> 1; ACTION_RESUME -> 2; else -> 3 }
            val code = (id * 4 + slot).toInt()
            return PendingIntent.getBroadcast(
                context, code, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
