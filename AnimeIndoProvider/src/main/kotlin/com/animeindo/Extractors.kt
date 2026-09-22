package com.animeindo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

/**
 * Gdplayer (gdplayer.to) – used by GDRIVE server buttons
 */
open class Gdplayer : ExtractorApi() {
    override val name = "Gdplayer"
    override val mainUrl = "https://gdplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer ?: mainUrl).document
        val script = res.selectFirst("script:containsData(kaken)")?.data()
            ?: res.selectFirst("script:containsData(player)")?.data()
        val kaken = script
            ?.substringAfter("kaken = \"")
            ?.substringBefore("\"")
            ?.takeIf { it.isNotBlank() }
            ?: return

        val json = app.get(
            "$mainUrl/api/?$kaken=&_=${APIHolder.unixTimeMS}",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url
            )
        ).parsedSafe<Response>() ?: return

        json.sources?.forEach { src ->
            val file = src.file ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    file,
                    INFER_TYPE
                ) {
                    this.quality = getQuality(json.title)
                    this.referer = mainUrl
                }
            )
        }
    }

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    data class Response(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("sources") val sources: ArrayList<Sources>? = null,
    ) {
        data class Sources(
            @JsonProperty("file") val file: String? = null,
            @JsonProperty("type") val type: String? = null,
        )
    }
}

/**
 * play.xtwap.top / btube3.php – B-TUBE server
 * Tries to extract JWPlayer sources or nested iframes / blogger embeds.
 */
class XtwapBtube : ExtractorApi() {
    override val name = "B-TUBE"
    override val mainUrl = "https://play.xtwap.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(
            url,
            referer = referer ?: "https://anime-indo.lol/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
            )
        ).document

        // 1) JWPlayer sources
        val script = doc.select("script").mapNotNull { it.data() }.joinToString("\n")
        val sources = Regex("""["']file["']\s*:\s*["']([^"']+)["']""").findAll(script)
            .map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .toList()

        sources.forEach { file ->
            callback.invoke(
                newExtractorLink(name, name, file, INFER_TYPE) {
                    this.referer = mainUrl
                }
            )
        }

        // 2) Nested iframe
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, url, subtitleCallback, callback)
            }
        }

        // 3) Blogger / video embeds via data or source tags
        doc.select("source[src], video source").forEach { src ->
            val file = src.attr("src").trim()
            if (file.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(name, name, file, INFER_TYPE) {
                        this.referer = mainUrl
                    }
                )
            }
        }
    }
}

/**
 * play.xtwap.top / cepat.php – CEPAT server
 */
class XtwapCepat : ExtractorApi() {
    override val name = "CEPAT"
    override val mainUrl = "https://play.xtwap.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(
            url,
            referer = referer ?: "https://anime-indo.lol/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
            )
        ).document

        val script = doc.select("script").mapNotNull { it.data() }.joinToString("\n")

        // JWPlayer file
        Regex("""["']file["']\s*:\s*["']([^"']+)["']""").findAll(script)
            .map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .forEach { file ->
                callback.invoke(
                    newExtractorLink(name, name, file, INFER_TYPE) {
                        this.referer = mainUrl
                    }
                )
            }

        // sources array
        Regex("""sources\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(script)?.groupValues?.getOrNull(1)?.let { arr ->
                Regex("""["']file["']\s*:\s*["']([^"']+)["']""").findAll(arr)
                    .map { it.groupValues[1] }
                    .filter { it.startsWith("http") }
                    .forEach { file ->
                        callback.invoke(
                            newExtractorLink(name, name, file, INFER_TYPE) {
                                this.referer = mainUrl
                            }
                        )
                    }
            }

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, url, subtitleCallback, callback)
            }
        }
    }
}
