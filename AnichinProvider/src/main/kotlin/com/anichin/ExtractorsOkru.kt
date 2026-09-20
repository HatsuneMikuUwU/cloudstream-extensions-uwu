package com.anichin

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray

class OkRuSSL : Odnoklassniki() {
    override var name = "OK.ru"
    override var mainUrl = "https://ok.ru"
}

class OkRuHTTP : Odnoklassniki() {
    override var name = "OK.ru"
    override var mainUrl = "http://ok.ru"
}

open class Odnoklassniki : ExtractorApi() {
    override val name = "OK.ru"
    override val mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = normalizeEmbedUrl(url)
        val embedReferer = referer ?: "https://anichin.moe/"
        val embedHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Connection" to "keep-alive",
            "User-Agent" to USER_AGENT,
            "Referer" to embedReferer,
        )
        val mediaHeaders = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Origin" to "https://ok.ru",
            "User-Agent" to USER_AGENT,
            "Referer" to embedUrl,
        )

        val videoReq = app.get(
            embedUrl,
            referer = embedReferer,
            headers = embedHeaders,
        ).text.cleanOkRuPayload()

        val hlsManifest = extractOkRuField(videoReq, "hlsManifestUrl")
        if (!hlsManifest.isNullOrBlank()) {
            val hlsLinks = runCatching {
                M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = hlsManifest,
                    referer = embedUrl,
                    headers = mediaHeaders,
                )
            }.getOrElse { error ->
                Log.w("AnichinOkRu", "Failed to resolve OK.ru HLS manifest", error)
                emptyList()
            }

            if (hlsLinks.isNotEmpty()) {
                hlsLinks.forEach(callback)
                return
            }
        }

        val metadataWebm = extractOkRuField(videoReq, "metadataWebmUrl")
        if (!metadataWebm.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = metadataWebm,
                    type = INFER_TYPE,
                ) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mediaHeaders
                }
            )
            return
        }

        val videosStr = Regex(""""videos"\s*:\s*(\[[^\]]*])""")
            .find(videoReq)
            ?.groupValues
            ?.getOrNull(1)
            ?: throw ErrorLoadingException("Video not found")

        val videos = parseOkRuVideos(videosStr).takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("Video not found")

        videos.forEach { video ->
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            val quality = video.name.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW", "360p")
                .replace("SD", "480p")
                .replace("HD", "720p")
                .replace("FULL", "1080p")
                .replace("QUAD", "1440p")
                .replace("ULTRA", "4k")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    type = INFER_TYPE,
                ) {
                    this.referer = embedUrl
                    this.quality = getQualityFromName(quality)
                    this.headers = mediaHeaders
                }
            )
        }
    }

    private fun normalizeEmbedUrl(url: String): String {
        return url
            .replace("https://odnoklassniki.ru", "https://ok.ru")
            .replace("http://odnoklassniki.ru", "https://ok.ru")
            .replace("http://ok.ru", "https://ok.ru")
            .replace("/video/", "/videoembed/")
    }

    private fun extractOkRuField(raw: String, field: String): String? {
        return Regex(""""$field"\s*:\s*"([^"]+)"""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.cleanOkRuPayload()
            ?.takeIf { it.startsWith("http", true) }
    }

    private fun parseOkRuVideos(value: String): List<OkRuVideo> {
        return runCatching {
            val array = JSONArray(value)
            val results = mutableListOf<OkRuVideo>()

            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()

                if (name.isNotBlank() && url.isNotBlank()) {
                    results.add(OkRuVideo(name = name, url = url))
                }
            }

            results
        }.getOrElse { error ->
            Log.w("AnichinOkRu", "Failed to parse OK.ru videos", error)
            emptyList()
        }
    }

    private fun String.cleanOkRuPayload(): String {
        return this
            .replace("\\&quot;", "\"")
            .replace("&quot;", "\"")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\&", "&")
            .replace("\\=", "=")
            .replace("\\\\", "\\")
            .replace(Regex("\\\\u([0-9A-Fa-f]{4})")) { matchResult ->
                Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
            }
    }

    private data class OkRuVideo(
        val name: String,
        val url: String,
    )
}
