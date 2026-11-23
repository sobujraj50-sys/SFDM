package com.sfdm.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class VariantInfo(val quality: String, val sizeBytes: Long, val resumable: Boolean)

/** What a link points to, shown in the "new download" dialog before the user starts it. */
data class LinkInfo(
    val fileName: String?,
    val mimeType: String?,
    val variants: List<VariantInfo>,
)

class InspectException(message: String) : Exception(message)

object Inspector {

    /** Looks up name, size, type and available qualities without downloading the file. */
    suspend fun inspect(client: OkHttpClient, url: String): LinkInfo = withContext(Dispatchers.IO) {
        try {
            val (variants, suggested) = LinkResolver.variants(client, url)
            val probes = coroutineScope {
                variants.map { v ->
                    async {
                        v to runCatching { probe(client, v.url) }.getOrNull()
                    }
                }.awaitAll()
            }
            val first = probes.firstOrNull { it.second != null }?.second
                ?: throw InspectException("লিংক থেকে তথ্য পাওয়া যায়নি")
            if (first.mimeType?.startsWith("text/html") == true) {
                throw InspectException("এটা একটা ওয়েবপেজের লিংক, সরাসরি ফাইলের লিংক না")
            }
            LinkInfo(
                fileName = suggested ?: first.fileName,
                mimeType = first.mimeType,
                variants = probes.map { (v, p) -> VariantInfo(v.quality, p?.totalBytes ?: -1, p?.supportsRange == true) },
            )
        } catch (e: InspectException) {
            throw e
        } catch (e: FatalHttpException) {
            throw InspectException(e.message ?: "সার্ভার ত্রুটি")
        } catch (e: ExtractionException) {
            throw InspectException(e.message ?: "ভিডিও পাওয়া যায়নি")
        } catch (e: Exception) {
            throw InspectException("সংযোগ করা যায়নি: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun probe(client: OkHttpClient, url: String): ProbeResult = try {
        Probe.run(client, url)
    } catch (e: FatalHttpException) {
        if (e.code == 400 || e.code == 416) Probe.run(client, url, useRange = false) else throw e
    }
}
