package com.sfdm.app.engine

import com.sfdm.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The real file to download behind a page link. */
data class ResolvedLink(val url: String, val suggestedName: String?)

/** The page was reached but no downloadable video could be found in it. */
class ExtractionException(message: String) : IOException(message)

/**
 * Turns a page link (e.g. a Facebook share/reel link) into the direct video file URL.
 * Called again every time a download starts or resumes, so expired video links are refreshed automatically.
 * Direct file links are returned unchanged.
 */
object LinkResolver {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

    fun needsResolving(url: String): Boolean = !BuildConfig.PLAY_EDITION && isFacebook(url)

    fun resolve(client: OkHttpClient, url: String): ResolvedLink =
        if (needsResolving(url)) facebook(client, url) else ResolvedLink(url, null)

    // ------------------------------------------------------------------ Facebook

    private fun isFacebook(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "fb.watch" || host.endsWith("facebook.com") || host.endsWith("fb.com")
    }

    private val fbKeys = listOf(
        "browser_native_hd_url",
        "playable_url_quality_hd",
        "hd_src_no_ratelimit",
        "hd_src",
        "browser_native_sd_url",
        "playable_url",
        "sd_src_no_ratelimit",
        "sd_src",
    )

    private fun facebook(client: OkHttpClient, url: String): ResolvedLink {
        var lastPage: String? = null
        var finalUrl = url
        // Logged-out pages differ by browser; try desktop first, then mobile.
        for (ua in listOf(DESKTOP_UA, USER_AGENT)) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Dest", "document")
                .build()
            val html = client.newCall(req).execute().use { resp ->
                finalUrl = resp.request.url.toString()
                if (!resp.isSuccessful) {
                    if (Probe.isFatal(resp.code) && resp.code != 400) throw Probe.httpError(resp.code)
                    null
                } else {
                    resp.body?.string()
                }
            } ?: continue
            lastPage = html
            findVideoUrl(html)?.let { return ResolvedLink(it, facebookName(finalUrl)) }
        }
        if (finalUrl.contains("/login") || lastPage?.contains("id=\"login_form\"") == true) {
            throw ExtractionException("ভিডিওটি প্রাইভেট বা দেখতে লগইন লাগে — এখন শুধু পাবলিক ভিডিও নামানো যায়")
        }
        throw ExtractionException("এই Facebook লিংকে ডাউনলোডযোগ্য ভিডিও পাওয়া যায়নি")
    }

    private fun findVideoUrl(html: String): String? {
        for (key in fbKeys) {
            // JSON form:  "key":"https:\/\/video....mp4?..."
            Regex("\"$key\"\\s*:\\s*\"(https?:[^\"]+)\"").find(html)?.let { return unescape(it.groupValues[1]) }
            // HTML-escaped form inside data attributes:  &quot;key&quot;:&quot;https:...&quot;
            Regex("&quot;$key&quot;\\s*:\\s*&quot;(https?:.+?)&quot;").find(html)?.let { return unescape(it.groupValues[1]) }
        }
        // Mobile basic pages: <a href="/video_redirect/?src=https%3A%2F%2Fvideo...">
        Regex("video_redirect/\\?src=([^\"&]+)").find(html)?.let {
            return java.net.URLDecoder.decode(unescape(it.groupValues[1]), "UTF-8")
        }
        return null
    }

    private fun unescape(s: String): String {
        var r = s.replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
        r = Regex("\\\\u([0-9a-fA-F]{4})").replace(r) { it.groupValues[1].toInt(16).toChar().toString() }
        return r.replace("\\\\", "\\")
    }

    private fun facebookName(pageUrl: String): String {
        val id = Regex("/(?:reel|videos|watch/\\?v=|share/[rv]/)/?([A-Za-z0-9]+)").find(pageUrl)?.groupValues?.get(1)
            ?: Regex("[?&]v=(\\d+)").find(pageUrl)?.groupValues?.get(1)
            ?: System.currentTimeMillis().toString()
        return "facebook_$id.mp4"
    }
}
