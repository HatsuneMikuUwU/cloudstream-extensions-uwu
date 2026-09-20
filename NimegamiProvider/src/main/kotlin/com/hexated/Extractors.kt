package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

class DlganExtractor : ExtractorApi() {
    override val name = "Dlgan"
    override val mainUrl = "https://dlgan.space/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, headers = mapOf("Referer" to (referer ?: mainUrl))).text

        Regex("""stream_url":"(https:[^"]+)""").findAll(html).forEach { match ->
            val stream = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\u0026", "&")

            val quality = Regex("""(\d{3,4}p)""").find(stream)?.value

            callback(
                newExtractorLink(name, "$name ${quality ?: ""}", stream, ExtractorLinkType.VIDEO) {
                    this.referer = referer ?: mainUrl
                    this.quality = getQualityFromName(quality)
                    this.headers = mapOf("Referer" to (referer ?: mainUrl))
                }
            )
        }
    }
}

class BerkasDriveExtractor : ExtractorApi() {
    override val name = "BerkasDrive"
    override val mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val id = Regex("id=([a-zA-Z0-9+/=]+)").find(url)?.groupValues?.getOrNull(1)

        if (id != null) {
            try {
                val api = "$mainUrl/new/streaming.php?action=stream-worker&id=$id"

                val response = app.get(
                    api,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Referer" to "$mainUrl/"
                    )
                ).text

                val json = JSONObject(response)

                if (json.getBoolean("ok")) {
                    val videoUrl = json.getString("url").replace("\\/", "/")
                    val quality = Regex("""(\d{3,4}p)""").find(videoUrl)?.value

                    callback(
                        newExtractorLink(
                            name,
                            "$name ${quality ?: ""}",
                            videoUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = getQualityFromName(quality)
                            this.headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "User-Agent" to "Mozilla/5.0"
                            )
                        }
                    )

                    return
                }
            } catch (_: Exception) {
            }
        }

        val res = app.get(url, referer = referer).document
        val video = res.selectFirst("video source")?.attr("src") ?: return

        callback(
            newExtractorLink(
                name,
                name,
                video,
                INFER_TYPE
            ) {
                this.referer = "$mainUrl/"
            }
        )
    }
}

/**
 * BerkasDrive's current host (stordl.halahgan.com). The page/site only gives a wrapper URL:
 *   https://stordl.halahgan.com/streaming/<id>?name=[Nimegami]_..._(720p).mp4
 * which is not a video. The real file comes from the resolver endpoint:
 *   /streaming//<id>?action=stream-url&id=<id>  ->  {"url":"https://stor.halahgan.com/....mp4"}
 */
open class StorDlExtractor : ExtractorApi() {
    override val name = "BerkasDrive"
    override val mainUrl = "https://stordl.halahgan.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(url)
        val id = Regex("""/streaming/+([^/?&#]+)""").find(url)?.groupValues?.getOrNull(1)
        val label = qualityLabelOf(url)

        // 1) resolver endpoint
        if (id != null) {
            val json = runCatching {
                app.get(
                    "$origin/streaming//$id?action=stream-url&id=$id",
                    referer = url,
                    headers = mapOf(
                        "Origin" to origin,
                        "Accept" to "application/json, text/plain, */*"
                    )
                ).text
            }.getOrNull()

            val direct = json?.let {
                Regex(""""(?:url|stream_url)"\s*:\s*"([^"]+)"""")
                    .find(it.cleanStream())?.groupValues?.getOrNull(1)
            }
            if (direct != null && emitDirect(direct, origin, label, callback)) return
        }

        // 2) fallback: read the wrapper page itself
        val html = runCatching {
            app.get(url, referer = referer ?: "$origin/").text.cleanStream()
        }.getOrNull() ?: return

        val candidates = linkedSetOf<String>()
        Regex(""""(?:stream_url|direct_url|file|url)"\s*:\s*"(https?://[^"]+)"""")
            .findAll(html).forEach { candidates.add(it.groupValues[1]) }
        Regex("""<source[^>]+src=["']([^"']+)["']""")
            .findAll(html).forEach { candidates.add(it.groupValues[1]) }
        Regex("""https?://[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*""")
            .findAll(html).forEach { candidates.add(it.value) }

        for (candidate in candidates) {
            if (emitDirect(candidate, origin, label, callback)) return
        }
    }

    private suspend fun emitDirect(
        rawUrl: String,
        origin: String,
        label: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoUrl = rawUrl.cleanStream().let {
            if (it.startsWith("//")) "https:$it" else it
        }
        if (!videoUrl.startsWith("http", true)) return false
        val isHls = videoUrl.contains(".m3u8", true)
        if (!isHls && !videoUrl.contains(".mp4", true) && !videoUrl.contains(".mkv", true)) return false

        val quality = label ?: qualityLabelOf(videoUrl)
        callback(
            newExtractorLink(
                name,
                "$name ${quality ?: ""}".trim(),
                videoUrl,
                if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$origin/"
                this.quality = getQualityFromName(quality)
            }
        )
        return true
    }

    private fun originOf(url: String): String {
        return runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(mainUrl)
    }

    private fun qualityLabelOf(url: String): String? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        return Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(decoded)?.value
    }

    private fun String.cleanStream(): String {
        return replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }
}

class DlganHalahganExtractor : StorDlExtractor() {
    override val mainUrl = "https://dlgan.halahgan.com"
}
