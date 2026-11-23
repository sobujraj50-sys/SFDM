package com.sfdm.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sfdm.app.SfdmApp
import com.sfdm.app.data.AppSettings
import com.sfdm.app.data.Category
import com.sfdm.app.data.DownloadEntity
import com.sfdm.app.data.DownloadStatus
import com.sfdm.app.data.NetworkMode
import com.sfdm.app.engine.LiveStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadItem(val entity: DownloadEntity, val live: LiveStats?) {
    val downloaded: Long get() = live?.downloaded ?: entity.downloadedBytes
    val total: Long get() = if (entity.totalBytes > 0) entity.totalBytes else live?.total ?: -1
    val speed: Long get() = live?.speedBps ?: 0
    val connections: Int get() = live?.connections ?: 0
    val category: Category get() = entity.category?.let { Category.of(it) } ?: Category.detect(entity.fileName, entity.mimeType)
}

enum class Filter { ALL, ACTIVE, DONE }
enum class SortBy { DATE, NAME, SIZE }

data class ListQuery(
    val filter: Filter = Filter.ALL,
    val category: Category? = null,
    val search: String = "",
    val sort: SortBy = SortBy.DATE,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val sfdm = SfdmApp.get(app)
    private val manager = sfdm.manager

    val query = MutableStateFlow(ListQuery())

    private val all = combine(manager.downloads, manager.live) { list, live ->
        list.map { DownloadItem(it, live[it.id]) }
    }

    val items: StateFlow<List<DownloadItem>> = combine(all, query) { list, q ->
        list.asSequence()
            .filter {
                when (q.filter) {
                    Filter.ALL -> true
                    Filter.ACTIVE -> it.entity.status != DownloadStatus.COMPLETED
                    Filter.DONE -> it.entity.status == DownloadStatus.COMPLETED
                }
            }
            .filter { q.category == null || it.category == q.category }
            .filter {
                q.search.isBlank() ||
                    it.entity.fileName.contains(q.search, ignoreCase = true) ||
                    it.entity.url.contains(q.search, ignoreCase = true)
            }
            .let { seq ->
                when (q.sort) {
                    SortBy.DATE -> seq.sortedByDescending { it.entity.finishedAt ?: it.entity.createdAt }
                    SortBy.NAME -> seq.sortedBy { it.entity.fileName.lowercase() }
                    SortBy.SIZE -> seq.sortedByDescending { it.total }
                }
            }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Number of downloads per category (for the category chips). */
    val categoryCounts: StateFlow<Map<Category, Int>> = all.map { list ->
        list.groupingBy { it.category }.eachCount()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val settings: StateFlow<AppSettings> = sfdm.settings.state

    fun setFilter(f: Filter) { query.value = query.value.copy(filter = f) }
    fun setCategory(c: Category?) { query.value = query.value.copy(category = c) }
    fun setSearch(s: String) { query.value = query.value.copy(search = s) }
    fun setSort(s: SortBy) { query.value = query.value.copy(sort = s) }

    fun add(url: String, name: String?, connections: Int, mode: NetworkMode, quality: String?) {
        viewModelScope.launch { manager.add(url, name, connections, mode, quality) }
    }

    suspend fun inspect(url: String) = manager.inspect(url)

    fun pause(id: Long) { manager.pause(id) }
    fun resume(id: Long) { manager.resume(id) }
    fun pauseAll() { manager.pauseAll() }
    fun resumeAll() { manager.resumeAll() }
    fun moveToTop(id: Long) { manager.moveToTop(id) }
    fun clearHistory() { manager.clearHistory() }
    fun delete(id: Long, deleteFile: Boolean) { manager.delete(id, deleteFile) }
    fun setNetworkMode(id: Long, mode: NetworkMode) { manager.setNetworkMode(id, mode) }
    fun updateSettings(s: AppSettings) { sfdm.settings.update { s } }
    fun shareableUri(target: String) = manager.storage.shareableUri(target)
}
