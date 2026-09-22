package com.animasu

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class BloggerExtractor : ExtractorApi() {
    override val name = "Blogger"
    override val mainUrl = "https://www.blogger.com"
    override val requiresReferer = true

    private val googleVideoReferer = "https://youtube.googleapis.com/"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url

        if (fixed.contains("blogger.googleusercontent.com", true) ||
            fixed.contains("googlevideo.com/videoplayback", true)
        ) {
            emit(fixed, fixed, callback)
            return
        }

        val redirectLocation = try {
            app.get(
                fixed,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to (referer ?: mainUrl)
                ),
                referer = referer,
                allowRedirects = false
            ).headers["location"]
        } catch (_: Exception) {
            null
        }

        if (!redirectLocation.isNullOrBlank()) {
            val normalized = normalizeVideoUrl(redirectLocation)
            if (normalized.contains("googlevideo.com/videoplayback", true) ||
                normalized.contains("blogger.googleusercontent.com", true)
            ) {
                emit(normalized, fixed, callback)
                return
            }
        }

        val page = try {
            app.get(
                fixed,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to (referer ?: mainUrl)
                ),
                referer = referer
            ).text
        } catch (_: Exception) {
            return
        }

        val decoded = decodeUnicodeEscapes(page)
        val urls = (
            Regex("""https://[^\s"']+""")
                .findAll(decoded)
                .map { it.value } +
                Regex("""https://[^\s"']+""")
                    .findAll(page)
                    .map { it.value }
            )
            .map { normalizeVideoUrl(it) }
            .filter {
                it.contains("googlevideo.com/videoplayback") ||
                    it.contains("blogger.googleusercontent.com")
            }
            .distinct()
            .toList()

        urls.forEach { videoUrl ->
            emit(videoUrl, fixed, callback)
        }
    }

    private suspend fun emit(videoUrl: String, pageUrl: String, callback: (ExtractorLink) -> Unit) {
        val directReferer = if (videoUrl.contains("googlevideo.com/", true)) {
            googleVideoReferer
        } else {
            pageUrl
        }
        val itag = Regex("""[?&]itag=(\d+)""").find(videoUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
        callback.invoke(
            newExtractorLink(name, name, videoUrl, INFER_TYPE) {
                this.referer = directReferer
                this.headers = mapOf(
                    "Referer" to directReferer,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                )
                this.quality = itagToQuality(itag)
            }
        )
    }

    private fun decodeUnicodeEscapes(input: String): String {
        var output = input
        val unicodeRegex = Regex("""\\u([0-9a-fA-F]{4})""")
        repeat(2) {
            output = unicodeRegex.replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        return output
            .replace("\\/", "/")
            .replace("\\=", "=")
            .replace("\\&", "&")
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
    }

    private fun normalizeVideoUrl(input: String): String {
        return decodeUnicodeEscapes(input)
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\", "")
    }

    private fun itagToQuality(itag: Int?): Int {
        return when (itag) {
            18 -> Qualities.P360.value
            22 -> Qualities.P720.value
            37 -> Qualities.P1080.value
            59 -> Qualities.P480.value
            43, 36 -> Qualities.P360.value
            17 -> Qualities.P144.value
            137 -> Qualities.P1080.value
            136 -> Qualities.P720.value
            135 -> Qualities.P480.value
            134 -> Qualities.P360.value
            133 -> Qualities.P240.value
            160 -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }
}

class YourUploadExtractor : ExtractorApi() {
    override val name = "YourUpload"
    override val mainUrl = "https://www.yourupload.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val doc = try {
            app.get(
                fixed,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                referer = referer
            ).text
        } catch (_: Exception) {
            return
        }

        val streamUrls = mutableListOf<String>()

        Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(doc)
            .map { it.value.replace("\\/", "/").replace("\\u0026", "&") }
            .forEach { streamUrls.add(it) }

        Regex(""""file"\s*:\s*"([^"]+)"""")
            .findAll(doc)
            .map { it.groupValues[1].replace("\\/", "/").replace("\\u0026", "&") }
            .forEach { streamUrls.add(it) }

        streamUrls.distinct().forEach { stream ->
            callback.invoke(
                newExtractorLink(name, name, stream, INFER_TYPE) {
                    this.referer = fixed
                    this.quality = getQualityFromName(doc)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to fixed,
                        "Accept" to "*/*"
                    )
                }
            )
        }
    }
}

class FiledonExtractor : ExtractorApi() {
    override val name = "Filedon"
    override val mainUrl = "https://filedon.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = normalize(url)
        val doc = try {
            app.get(
                fixed,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                referer = referer
            ).text
        } catch (_: Exception) {
            return
        }

        val dataPage = Regex("""data-page=["']([^"']+)["']""")
            .find(doc)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { unescapeHtml(it) }

        val qualityHint = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(dataPage ?: doc)
            ?.groupValues
            ?.getOrNull(1)

        val streamUrls = mutableListOf<String>()

        if (!dataPage.isNullOrBlank()) {
            Regex("""https?://[^"\\\s]+\.(?:mp4|m3u8)[^"\\\s]*""", RegexOption.IGNORE_CASE)
                .findAll(dataPage)
                .map { it.value.replace("\\u0026", "&").replace("\\/", "/") }
                .forEach { streamUrls.add(it) }

            listOf("download_url", "stream_url", "url", "file_url", "direct_url").forEach { key ->
                Regex(""""$key"\s*:\s*"([^"]+)"""")
                    .findAll(dataPage)
                    .map { it.groupValues[1].replace("\\u0026", "&").replace("\\/", "/") }
                    .filter { it.startsWith("http") && (it.contains(".mp4") || it.contains(".m3u8") || it.contains("r2.cloudflare") || it.contains("s3")) }
                    .forEach { streamUrls.add(it) }
            }
        }

        if (streamUrls.isEmpty()) {
            Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .findAll(doc)
                .map { it.value }
                .forEach { streamUrls.add(it) }
        }

        streamUrls.distinct().forEach { stream ->
            callback.invoke(
                newExtractorLink(name, name, stream, INFER_TYPE) {
                    this.referer = fixed
                    this.quality = getQualityFromName(qualityHint)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to fixed,
                        "Accept" to "*/*"
                    )
                }
            )
        }
    }

    private fun normalize(url: String): String {
        var u = if (url.startsWith("//")) "https:$url" else url
        u = u.replace("/view/", "/embed/")
        return u
    }

    private fun unescapeHtml(input: String): String {
        return input
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
    }
}

class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    private val sourceEndpoints = listOf(
        "https://abyss.to/api/source/",
        "https://abyssplayer.com/api/source/",
        "https://short.icu/"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val id = Regex("""(?:abyssplayer\.com|abyss\.to|hydrax\.[a-z]+|short\.icu)/(?:[a-z]+/)?([A-Za-z0-9]+)""")
            .find(fixed)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""/([A-Za-z0-9]{6,})/?$""").find(fixed)?.groupValues?.getOrNull(1)
            ?: return

        var found = false

        for (base in sourceEndpoints) {
            try {
                val res = app.post(
                    "$base$id",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to fixed,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/plain, */*"
                    ),
                    data = mapOf("r" to (referer ?: ""), "d" to mainUrl.removePrefix("https://"))
                ).text

                parseSources(res).forEach { (stream, quality) ->
                    found = true
                    callback.invoke(
                        newExtractorLink(name, name, stream, INFER_TYPE) {
                            this.referer = fixed
                            this.quality = quality
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to fixed
                            )
                        }
                    )
                }
            } catch (_: Exception) {
            }
        }

        if (!found) {
            try {
                val page = app.get(
                    fixed,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    referer = referer
                ).text
                Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                    .findAll(page)
                    .map { it.value }
                    .distinct()
                    .forEach { stream ->
                        callback.invoke(
                            newExtractorLink(name, name, stream, INFER_TYPE) {
                                this.referer = fixed
                                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fixed)
                            }
                        )
                    }
            } catch (_: Exception) {
            }
        }
    }

    private fun parseSources(jsonText: String): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        try {
            val root = JSONObject(jsonText)
            val data = root.optJSONArray("data") ?: root.optJSONArray("sources")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val file = item.optString("file").ifBlank { item.optString("src") }
                    if (file.isBlank()) continue
                    val label = item.optString("label").ifBlank { item.optString("type") }
                    out.add(file to getQualityFromName(label))
                }
            }
        } catch (_: Exception) {
            Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .findAll(jsonText)
                .forEach { out.add(it.value to Qualities.Unknown.value) }
        }
        return out.distinctBy { it.first }
    }
}
