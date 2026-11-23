package com.sfdm.app.data

/** File categories; each has its own folder under Download/SFDM. */
enum class Category(val label: String, val folder: String) {
    VIDEO("ভিডিও", "Video"),
    MUSIC("মিউজিক", "Music"),
    DOCUMENT("ডকুমেন্ট", "Documents"),
    APP("অ্যাপ", "Apps"),
    ARCHIVE("আর্কাইভ", "Archives"),
    IMAGE("ছবি", "Images"),
    OTHER("অন্যান্য", "Other");

    companion object {
        private val ext = mapOf(
            VIDEO to setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "3gp", "m4v", "wmv", "ts", "m3u8"),
            MUSIC to setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr"),
            DOCUMENT to setOf(
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp",
                "csv", "epub", "md", "json", "xml", "html", "htm",
            ),
            APP to setOf("apk", "xapk", "apks", "apkm", "aab"),
            ARCHIVE to setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso"),
            IMAGE to setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg", "tif", "tiff"),
        )

        fun detect(fileName: String?, mime: String?): Category {
            val e = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (e.isNotEmpty()) ext.entries.firstOrNull { e in it.value }?.let { return it.key }
            val m = mime?.lowercase().orEmpty()
            return when {
                m.startsWith("video/") -> VIDEO
                m.startsWith("audio/") -> MUSIC
                m.startsWith("image/") -> IMAGE
                m == "application/vnd.android.package-archive" -> APP
                m.contains("zip") || m.contains("rar") || m.contains("7z") || m.contains("tar") -> ARCHIVE
                m.startsWith("text/") || m.contains("pdf") || m.contains("document") || m.contains("msword") ||
                    m.contains("sheet") || m.contains("presentation") -> DOCUMENT
                else -> OTHER
            }
        }

        fun of(name: String?): Category = entries.firstOrNull { it.name == name } ?: OTHER
    }
}
