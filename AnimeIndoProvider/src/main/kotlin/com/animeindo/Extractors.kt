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

/**
 * Gdplayer (gdplayer.to) – GDRIVE server buttons
 */
class Gdplayer : ExtractorApi() {
    override val name = "Gdplayer"
    override val mainUrl = "https://gdplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Try embed path and fallback /f/ download path
        val candidates = listOf(url, url.replace("/x/?", "/f/?"))
        for (candidate in candidates) {
            val res = runCatching {
                app.get(
                    candidate,
                    referer = referer ?: "https://anime-indo.lol/",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                )
            }.getOrNull() ?: continue

            if (res.code == 404) continue
            val doc = res.document
            val script = doc.select("script").mapNotNull { it.data() }.joinToString("\n")
            val kaken = Regex("""kaken\s*=\s*["']([^"']+)["']""")
                .find(script)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }

            if (kaken != null) {
                val json = app.get(
                    "$mainUrl/api/?$kaken=&_=${APIHolder.unixTimeMS}",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to candidate
                    )
                ).parsedSafe<Response>()

                json?.sources?.forEach { src ->
                    val file = src.file ?: return@forEach
                    callback.invoke(
                        newExtractorLink(name, name, file, INFER_TYPE) {
                            this.quality = getQuality(json.title)
                            this.referer = mainUrl
                        }
                    )
                }
                if (!json?.sources.isNullOrEmpty()) return
            }

            // Direct source / iframe fallback
            doc.select("source[src], video source").forEach { el ->
                val file = el.attr("src").trim()
                if (file.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(name, name, file, INFER_TYPE) {
                            this.referer = mainUrl
                        }
                    )
                }
            }
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.startsWith("http")) {
                    loadExtractor(src, candidate, subtitleCallback, callback)
                }
            }
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
 * Unified extractor for play.xtwap.top (B-TUBE / CEPAT / etc.)
 * B-TUBE returns a direct googlevideo / blogger mp4 in <source src="...">.
 */
class Xtwap : ExtractorApi() {
    override val name = "Xtwap"
    override val mainUrl = "https://play.xtwap.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val label = when {
            url.contains("btube", true) -> "B-TUBE"
            url.contains("cepat", true) -> "CEPAT"
            else -> name
        }

        val doc = app.get(
            url,
            referer = referer ?: "https://anime-indo.lol/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        ).document

        // 1) <source src="..."> (B-TUBE video.js style)
        doc.select("source[src], video source, video[src]").forEach { el ->
            val file = el.attr("src").ifBlank { el.attr("data-src") }.trim()
            if (file.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(label, label, file, INFER_TYPE) {
                        this.referer = mainUrl
                        this.headers = mapOf("Referer" to mainUrl)
                    }
                )
            }
        }

        // 2) JWPlayer / file: "..."
        val script = doc.select("script").mapNotNull { it.data() }.joinToString("\n")
        Regex("""["']file["']\s*:\s*["'](https?://[^"']+)["']""")
            .findAll(script)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { file ->
                callback.invoke(
                    newExtractorLink(label, label, file, INFER_TYPE) {
                        this.referer = mainUrl
                    }
                )
            }

        // 3) sources:[{file:"..."}]
        Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+)["']""")
            .findAll(script)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { file ->
                callback.invoke(
                    newExtractorLink(label, label, file, INFER_TYPE) {
                        this.referer = mainUrl
                    }
                )
            }

        // 4) Nested iframe
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.startsWith("http") && !src.contains("xtwap.top")) {
                loadExtractor(src, url, subtitleCallback, callback)
            }
        }
    }
}
