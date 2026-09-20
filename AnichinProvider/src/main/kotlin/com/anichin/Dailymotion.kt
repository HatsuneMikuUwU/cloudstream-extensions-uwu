package com.anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Geodailymotion : Dailymotion() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class Dailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"
    private val geoBaseUrl = "https://geo.dailymotion.com"

    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: return

        if (embedUrl.contains("geo.dailymotion.com", true)) {
            val loaded = resolveGeoPlayer(embedUrl, referer, subtitleCallback, callback)
            if (loaded) return
        }

        val id = getVideoId(embedUrl) ?: return
        resolveMetadataVideo(id, embedUrl, subtitleCallback, callback)
    }

    private suspend fun resolveGeoPlayer(
        embedUrl: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val accessId = getGeoAccessId(embedUrl) ?: return false
        val embedder = URLEncoder.encode(referer ?: "https://anichin.moe/", "UTF-8")
        val metadataUrl = "$geoBaseUrl/video/$accessId.json?legacy=true&embedder=$embedder"
        val response = runCatching {
            app.get(
                metadataUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Accept" to "application/json,text/plain,*/*",
                )
            ).text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(response) }.getOrNull() ?: return false
        emitSubtitles(json, subtitleCallback)

        val urls = extractQualityUrls(json)
        if (urls.isNotEmpty()) {
            urls.forEach { videoUrl ->
                getStream(videoUrl, "GeoDailymotion", embedUrl, callback)
            }
            return true
        }

        val canonicalId = json.optString("id").trim().takeIf { it.matches(videoIdRegex) } ?: return false
        return resolveMetadataVideo(canonicalId, embedUrl, subtitleCallback, callback)
    }

    private suspend fun resolveMetadataVideo(
        id: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val metaDataUrl = "$baseUrl/player/metadata/video/$id"
        val response = runCatching {
            app.get(
                metaDataUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "application/json,text/plain,*/*",
                )
            ).text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(response) }.getOrNull()
        val urls = if (json != null) {
            emitSubtitles(json, subtitleCallback)
            extractQualityUrls(json)
        } else {
            Regex(""""url"\s*:\s*"([^"]+)"""")
                .findAll(response)
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.contains(".m3u8", true) }
                .distinct()
                .toList()
        }

        urls.forEach { videoUrl ->
            getStream(videoUrl, this.name, referer, callback)
        }

        return urls.isNotEmpty()
    }

    private fun extractQualityUrls(json: JSONObject): List<String> {
        val urls = linkedSetOf<String>()
        val qualities = json.optJSONObject("qualities")

        qualities?.keys()?.forEach { quality ->
            val entries = qualities.optJSONArray(quality) ?: return@forEach
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val type = item.optString("type").lowercase()
                val url = item.optString("url").trim()
                    .replace("\\/", "/")
                    .takeIf { it.isNotBlank() }
                    ?: continue

                if (type.contains("mpegurl") || type.contains("x-mpegurl") || url.contains(".m3u8", true)) {
                    urls.add(url)
                }
            }
        }

        return urls.toList()
    }

    private fun emitSubtitles(json: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        val subtitles = json.optJSONObject("subtitles")
        subtitles?.keys()?.forEach { lang ->
            val value = subtitles.opt(lang)
            val entries = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("data") ?: JSONArray().put(value)
                else -> JSONArray()
            }

            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val label = item.optString("label", lang).ifBlank { lang }
                val urls = item.optJSONArray("urls")
                if (urls != null) {
                    for (urlIndex in 0 until urls.length()) {
                        val subUrl = urls.optString(urlIndex).trim()
                        if (subUrl.isNotBlank()) subtitleCallback(SubtitleFile(url = subUrl, lang = label))
                    }
                } else {
                    val subUrl = item.optString("url").trim()
                    if (subUrl.isNotBlank()) subtitleCallback(SubtitleFile(url = subUrl, lang = label))
                }
            }
        }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("geo.dailymotion.com", true)) return url
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("dai.ly", true)) return url
        return null
    }

    private fun getGeoAccessId(url: String): String? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        return listOf(
            Regex("""(?i)[?&]video=([A-Za-z0-9]+)"""),
            Regex("""(?i)/video/([A-Za-z0-9]+)\.json"""),
            Regex("""(?i)/video/([A-Za-z0-9]+)"""),
        ).firstNotNullOfOrNull { regex -> regex.find(decoded)?.groupValues?.getOrNull(1) }
            ?.takeIf { it.matches(videoIdRegex) }
    }

    private fun getVideoId(url: String): String? {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val id = when {
            decoded.contains("dai.ly", true) -> URI(decoded).path.trim('/').substringBefore("/")
            decoded.contains("geo.dailymotion.com", true) -> getGeoAccessId(decoded).orEmpty()
            else -> URI(decoded).path.substringAfterLast("/")
        }

        return if (id.matches(videoIdRegex)) id else null
    }

    private suspend fun getStream(
        streamLink: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        return generateM3u8(
            source = name,
            streamUrl = streamLink,
            referer = referer,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
            )
        ).forEach(callback)
    }
}
