package com.sfdm.app

import android.app.Application
import android.content.Context
import com.sfdm.app.data.AppDatabase
import com.sfdm.app.data.SettingsStore
import com.sfdm.app.engine.DownloadManager
import com.sfdm.app.network.NetworkProvider
import com.sfdm.app.service.Notifications

class SfdmApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var network: NetworkProvider
        private set
    lateinit var manager: DownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        settings = SettingsStore(this)
        network = NetworkProvider(this)
        manager = DownloadManager(this, AppDatabase.get(this), network, settings)
    }

    companion object {
        fun get(context: Context): SfdmApp = context.applicationContext as SfdmApp
    }
}
