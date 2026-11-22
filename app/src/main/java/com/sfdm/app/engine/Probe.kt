package com.sfdm.app.engine

import android.net.Uri
import android.webkit.MimeTypeMap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder

const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

class FatalHttpException(val code: Int, message: String) : IOException(message)

data class ProbeResult(
    val totalBytes: Long,
    val supportsRange: Boolean,
    val fileName: String?,
    val mimeType: String?,
    val etag: String?,
    val lastModified: String?,
)

object Probe {

    /** Asks the server for the file's size, name and whether it can send pieces (resume/multi-connection). */
    fun run(client: OkHttpClient, url: String): ProbeResult {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw httpError(resp.code)
            val contentRange = resp.header("Content-Range")
            val ranged = resp.code == 206 && contentRange != null
            val total = when {
                ranged -> contentRange!!.substringAfterLast('/').trim().toLongOrNull() ?: -1L
                else -> resp.body?.contentLength() ?: -1L
            }
            val finalUrl = resp.request.url.toString()
            val headerMime: String? = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            val rawName: String? = parseDisposition(resp.header("Content-Disposition")) ?: nameFromUrl(finalUrl)
            val ext: String = rawName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            val mime: String? =
                if (headerMime == null || headerMime == "application/octet-stream" || headerMime == "binary/octet-stream") {
                    (if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null) ?: headerMime
                } else headerMime
            val extFromMime: String? = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            val name: String? =
                if (rawName != null && !rawName.contains('.') && extFromMime != null) "$rawName.$extFromMime" else rawName
            return ProbeResult(
                totalBytes = total,
                supportsRange = ranged && total > 0,
                fileName = name,
                mimeType = mime,
                etag = resp.header("ETag"),
                lastModified = resp.header("Last-Modified"),
            )
        }
    }

    fun httpError(code: Int): FatalHttpException = FatalHttpException(
        code,
        when (code) {
            401, 403 -> "সার্ভার অনুমতি দেয়নি ($code) — লিংকের মেয়াদ শেষ হতে পারে"
            404, 410 -> "ফাইলটি সার্ভারে পাওয়া যায়নি ($code)"
            416 -> "সার্ভার এই অংশটি দিতে পারছে না (416)"
            else -> "সার্ভার ত্রুটি ($code)"
        },
    )

    fun isFatal(code: Int) = code in 400..499 && code != 408 && code != 429

    private fun parseDisposition(header: String?): String? {
        if (header == null) return null
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let {
            val charset = it.groupValues[1].ifBlank { "UTF-8" }
            return runCatching { URLDecoder.decode(it.groupValues[2].trim(), charset) }.getOrNull()
        }
        Regex("filename\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(header)?.let {
            return it.groupValues[1]
        }
        Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let {
            return it.groupValues[1].trim()
        }
        return null
    }

    fun nameFromUrl(url: String): String? {
        val seg = Uri.parse(url).lastPathSegment ?: return null
        return seg.takeIf { it.isNotBlank() }
    }
}
