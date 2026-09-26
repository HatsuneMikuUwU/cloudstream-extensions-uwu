package com.animesail

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class AnimeSailMp4UploadExtractor : ExtractorApi() {
    override val name = "Mp4Upload"
    override val mainUrl = "https://mp4upload.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val doc = try {
            app.get(fixed, headers = mapOf("User-Agent" to USER_AGENT), referer = fixed).text
        } catch (_: Exception) {
            return
        }

        if (doc.contains("Video not found", ignoreCase = true)) return

        val unpacked = try {
            JsUnpacker(doc).takeIf { it.detect() }?.unpack()
        } catch (_: Exception) {
            null
        } ?: doc

        val streamUrl = Regex("""src\s*:\s*["'](https?://[^"']+?\.mp4upload\.com[^"']*)["']""")
            .find(unpacked)?.groupValues?.getOrNull(1)
            ?: Regex("""player\.src\(\s*\{?\s*["']?src["']?\s*:?\s*["'](https?://[^"']+)["']""")
                .find(unpacked)?.groupValues?.getOrNull(1)
            ?: Regex("""https?://[^\s"'\\]+\.mp4upload\.com(?::\d+)?/d/[^\s"'\\]+""", RegexOption.IGNORE_CASE)
                .find(unpacked)?.value
            ?: return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                streamUrl,
                if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
                this.referer = "$mainUrl/"
                this.quality = getQualityFromName(doc)
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
            }
        )
    }
}

class AnimeSailAceFileExtractor : ExtractorApi() {
    override val name = "AceFile"
    override val mainUrl = "https://acefile.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val doc = try {
            app.get(fixed, headers = mapOf("User-Agent" to USER_AGENT), referer = referer).text
        } catch (_: Exception) {
            return
        }

        val unpacked = try {
            JsUnpacker(doc).takeIf { it.detect() }?.unpack()
        } catch (_: Exception) {
            null
        } ?: doc

        val sources = mutableListOf<String>()
        Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""").findAll(unpacked)
            .forEach { sources.add(it.groupValues[1]) }

        if (sources.isEmpty()) {
            Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                .findAll(unpacked).forEach { sources.add(it.value) }
        }

        sources.map { it.replace("\\/", "/") }.distinct().forEach { stream ->
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    stream,
                    if (stream.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.referer = fixed
                    this.quality = getQualityFromName(unpacked)
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fixed)
                }
            )
        }
    }
}

class AnimeSailPixeldrainExtractor : ExtractorApi() {
    override val name = "Pixeldrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""pixeldrain\.com/(?:u|d|api/file)/([A-Za-z0-9]+)""")
            .find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""/([A-Za-z0-9]{6,})/?$""").find(url)?.groupValues?.getOrNull(1)
            ?: return

        var quality = Qualities.Unknown.value
        var label = name
        try {
            val info = app.get("$mainUrl/api/file/$id/info").text
            val json = JSONObject(info)
            val fname = json.optString("name", "")
            if (fname.isNotBlank()) {
                quality = getQualityFromName(fname)
                label = "$name ($fname)"
            }
        } catch (_: Exception) {
        }

        callback.invoke(
            newExtractorLink(
                name,
                label,
                "$mainUrl/api/file/$id?download",
                INFER_TYPE
            ) {
                this.referer = "$mainUrl/u/$id"
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/u/$id"
                )
            }
        )
    }
}

class AnimeSailBuzzheavierExtractor : ExtractorApi() {
    override val name = "Buzzheavier"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val slug = Regex("""buzzheavier\.com/([A-Za-z0-9]+)""").find(fixed)?.groupValues?.getOrNull(1)

        val page = try {
            app.get(fixed, headers = mapOf("User-Agent" to USER_AGENT), referer = referer).text
        } catch (_: Exception) {
            return
        }

        val streams = Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8|mkv)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(page).map { it.value }.toMutableList()

        if (streams.isEmpty() && slug != null) {
            try {
                val dl = app.get(
                    "$mainUrl/$slug/download",
                    headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*"),
                    referer = fixed,
                    allowRedirects = false
                )
                dl.headers["location"]?.let { streams.add(it) }
            } catch (_: Exception) {
            }
        }

        streams.distinct().forEach { stream ->
            callback.invoke(
                newExtractorLink(name, name, stream, INFER_TYPE) {
                    this.referer = fixed
                    this.quality = getQualityFromName(stream)
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fixed, "Accept" to "*/*")
                }
            )
        }
    }
}

class AnimeSailAbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    private val sourceEndpoints = listOf(
        "https://abyss.to/api/source/",
        "https://abyssplayer.com/api/source/"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val id = Regex("""(?:abyssplayer\.com|abyss\.to)/([A-Za-z0-9]+)""").find(fixed)
            ?.groupValues?.getOrNull(1)
            ?: Regex("""/([A-Za-z0-9]{6,})/?$""").find(fixed)?.groupValues?.getOrNull(1)
            ?: return

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
                    callback.invoke(
                        newExtractorLink(name, name, stream, INFER_TYPE) {
                            this.referer = fixed
                            this.quality = quality
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
                    out.add(file to getQualityFromName(item.optString("label").ifBlank { item.optString("type") }))
                }
            }
        } catch (_: Exception) {
            Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .findAll(jsonText).forEach { out.add(it.value to Qualities.Unknown.value) }
        }
        return out.distinctBy { it.first }
    }
}

class AnimeSailFiledonExtractor : ExtractorApi() {
    override val name = "Filedon"
    override val mainUrl = "https://filedon.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = (if (url.startsWith("//")) "https:$url" else url).replace("/view/", "/embed/")
        val doc = app.get(
            fixed,
            headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,*/*;q=0.8"),
            referer = referer
        ).text

        val streams = mutableListOf<String>()
        Regex("""https?://[^"\\\s]+\.(?:mp4|m3u8)[^"\\\s]*""", RegexOption.IGNORE_CASE)
            .findAll(doc)
            .map { it.value.replace("\\u0026", "&").replace("\\/", "/") }
            .forEach { streams.add(it) }

        streams.distinct().forEach { stream ->
            callback.invoke(
                newExtractorLink(name, name, stream, INFER_TYPE) {
                    this.referer = fixed
                    this.quality = getQualityFromName(doc)
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fixed, "Accept" to "*/*")
                }
            )
        }
    }
}

class AnimeSailDoplyExtractor : ExtractorApi() {
    override val name = "Doply"
    override val mainUrl = "https://doply.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val doc = try {
            app.get(fixed, headers = mapOf("User-Agent" to USER_AGENT), referer = referer).text
        } catch (_: Exception) {
            return
        }

        val direct = Regex("""https?://[^\s"'<>\\]+\.(?:mp4|m3u8)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(doc).map { it.value.replace("\\/", "/") }.distinct().toList()

        if (direct.isNotEmpty()) {
            direct.forEach { stream ->
                callback.invoke(
                    newExtractorLink(name, name, stream, INFER_TYPE) {
                        this.referer = fixed
                        this.quality = getQualityFromName(doc)
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fixed)
                    }
                )
            }
            return
        }

        try {
            val passMd5Path = Regex("""['"](/pass_md5/[^'"]+)['"]""").find(doc)?.groupValues?.getOrNull(1)
                ?: return
            val token = Regex("""token=([A-Za-z0-9]+)""").find(doc)?.groupValues?.getOrNull(1).orEmpty()
            val base = passMd5Path.let { "$mainUrl$it" }
            val md5Res = app.get(base, headers = mapOf("User-Agent" to USER_AGENT), referer = fixed).text
            if (md5Res.isBlank()) return
            val randomStr = (10000..99999).random()
            val finalUrl = "$md5Res$randomStr?token=$token&expiry=${System.currentTimeMillis()}"
            callback.invoke(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.VIDEO) {
                    this.referer = fixed
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fixed)
                }
            )
        } catch (_: Exception) {
        }
    }
}

class AnimeSailVikingFileExtractor : ExtractorApi() {
    override val name = "VikingFile"
    override val mainUrl = "https://vikingfile.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val doc = try {
            app.get(fixed, headers = mapOf("User-Agent" to USER_AGENT), referer = referer).text
        } catch (_: Exception) {
            return
        }

        Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .find(doc)?.value?.let { direct ->
                callback.invoke(
                    newExtractorLink(name, name, direct, INFER_TYPE) {
                        this.referer = fixed
                        this.quality = getQualityFromName(doc)
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fixed)
                    }
                )
                return
            }

        val fields = listOf("op", "id", "rand", "referer", "method_free", "method_premium").associateWith { field ->
            Regex("""name=["']$field["']\s+value=["']([^"']*)["']""").find(doc)?.groupValues?.getOrNull(1).orEmpty()
        }
        if (fields["id"].isNullOrBlank()) return

        try {
            val res = app.post(
                fixed,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to fixed,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = fields.filterValues { it.isNotBlank() }
            ).text

            Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .findAll(res).map { it.value }.distinct().forEach { stream ->
                    callback.invoke(
                        newExtractorLink(name, name, stream, INFER_TYPE) {
                            this.referer = fixed
                            this.quality = getQualityFromName(res)
                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fixed)
                        }
                    )
                }
        } catch (_: Exception) {
        }
    }
}

class AnimeSailMixdropCloneExtractor : ExtractorApi() {
    override val name = "Mixdrop"
    override val mainUrl = "https://miiiixdrop.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val doc = try {
            app.get(fixed, headers = mapOf("User-Agent" to USER_AGENT), referer = referer).text
        } catch (_: Exception) {
            return
        }

        val wurl = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").find(doc)?.groupValues?.getOrNull(1)
            ?: return
        val streamUrl = if (wurl.startsWith("//")) "https:$wurl" else wurl

        callback.invoke(
            newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                this.referer = mainUrl
                this.quality = Regex("""MDCore\.quality\s*=\s*["']?(\d{3,4})""").find(doc)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
            }
        )
    }
}
