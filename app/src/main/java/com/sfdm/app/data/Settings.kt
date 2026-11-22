package com.sfdm.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val defaultNetworkMode: NetworkMode = NetworkMode.AUTO,
    val defaultConnections: Int = 8,
    val maxParallelDownloads: Int = 3,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("sfdm_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun load() = AppSettings(
        defaultNetworkMode = runCatching {
            NetworkMode.valueOf(prefs.getString("network_mode", NetworkMode.AUTO.name)!!)
        }.getOrDefault(NetworkMode.AUTO),
        defaultConnections = prefs.getInt("connections", 8).coerceIn(1, 32),
        maxParallelDownloads = prefs.getInt("max_parallel", 3).coerceIn(1, 10),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        prefs.edit()
            .putString("network_mode", s.defaultNetworkMode.name)
            .putInt("connections", s.defaultConnections)
            .putInt("max_parallel", s.maxParallelDownloads)
            .apply()
        _state.value = s
    }
}
