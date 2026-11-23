package com.sfdm.app.engine

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.sfdm.app.data.Category
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** An open, position-writable output file. Safe for concurrent positional writes. */
class OpenTarget(val channel: FileChannel, private val extra: Closeable?) : Closeable {
    override fun close() {
        runCatching { channel.force(false) }
        runCatching { channel.close() }
        runCatching { extra?.close() }
    }
}

/**
 * Where downloads are saved: the public "Download/SFDM" folder.
 * Android 10+ uses MediaStore (no storage permission needed); older versions write the file directly.
 * A target is stored in the database as a string: "content://..." or an absolute path.
 */
class Storage(private val context: Context) {

    fun create(fileName: String, mimeType: String?, category: Category): String {
        val safe = sanitize(fileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                if (mimeType != null) put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SFDM/" + category.folder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("ফাইল তৈরি করা যায়নি")
            uri.toString()
        } else {
            val dir = File(legacyDir(), category.folder).apply { mkdirs() }
            uniqueFile(dir, safe).also { it.createNewFile() }.absolutePath
        }
    }

    fun open(target: String): OpenTarget = if (target.startsWith("content://")) {
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(target), "rw")
            ?: throw IllegalStateException("ফাইল খোলা যায়নি")
        OpenTarget(ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel, null)
    } else {
        val raf = RandomAccessFile(File(target), "rw")
        OpenTarget(raf.channel, raf)
    }

    fun exists(target: String): Boolean = if (target.startsWith("content://")) {
        runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(target), "r")?.use { true } ?: false
        }.getOrDefault(false)
    } else File(target).exists()

    /** Makes the finished file visible to other apps. */
    fun finish(target: String) {
        if (target.startsWith("content://")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                context.contentResolver.update(Uri.parse(target), values, null, null)
            }
        } else {
            MediaScannerConnection.scanFile(context, arrayOf(target), null, null)
        }
    }

    fun delete(target: String) {
        runCatching {
            if (target.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(target), null, null)
            } else {
                File(target).delete()
            }
        }
    }

    /** A URI other apps can open (for "Open file"). */
    fun shareableUri(target: String): Uri = if (target.startsWith("content://")) {
        Uri.parse(target)
    } else {
        FileProvider.getUriForFile(context, context.packageName + ".files", File(target))
    }

    /** Truncates/clears the file so a non-resumable download can restart from zero. */
    fun truncate(target: String) {
        if (target.startsWith("content://")) {
            context.contentResolver.openFileDescriptor(Uri.parse(target), "rwt")?.use { }
        } else {
            RandomAccessFile(File(target), "rw").use { it.setLength(0) }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDir(): File {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SFDM")
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "SFDM")
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    companion object {
        fun sanitize(name: String): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
            return cleaned.ifBlank { "download" }.take(180)
        }
    }
}
