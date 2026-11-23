package com.sfdm.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadStatus {
    QUEUED,
    CONNECTING,
    RUNNING,
    PAUSED,
    WAITING_NETWORK,
    COMPLETED,
    FAILED;

    val isActive: Boolean get() = this == QUEUED || this == CONNECTING || this == RUNNING || this == WAITING_NETWORK
}

/**
 * Which network a download may use.
 * AUTO is the default: whatever network the phone is currently connected to.
 * The other modes are only used when the user picks them.
 */
enum class NetworkMode { AUTO, WIFI_ONLY, MOBILE_ONLY, BOOST }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val mimeType: String? = null,
    /** Where the bytes are written: a content:// URI (Android 10+) or an absolute file path. */
    val target: String? = null,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val connections: Int = 8,
    val networkMode: NetworkMode = NetworkMode.AUTO,
    /** Chosen video quality ("HD"/"SD") for page links; null = best available. */
    val quality: String? = null,
    val supportsRange: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
    val wifiBytes: Long = 0,
    val mobileBytes: Long = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
)

/** One byte range of a download. [end] is inclusive. */
@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("downloadId")],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val downloadId: Long,
    val start: Long,
    val end: Long,
    val downloaded: Long,
)
