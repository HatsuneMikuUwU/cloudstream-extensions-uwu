package com.nekopoi

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities

class Playmogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doodUrl = url.replace("playmogo.com", "dood.to")
                             .replace("playmogo.net", "dood.to")
            
            loadExtractor(
                doodUrl,
                referer,
                subtitleCallback,
                callback
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class Streampoi : ExtractorApi() {
    override val name = "Streampoi"
    override val mainUrl = "https://streampoi.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, referer = referer).text

            val regexPattern = """(?i)['"]?file['"]?\s*:\s*['"](https?://[^'"]+)['"]"""
            var fileUrl = Regex(regexPattern).find(html)?.groupValues?.getOrNull(1)

            if (fileUrl == null) {
                val packed = Regex(
                    """\}\s*\(\s*'((?:[^'\\]|\\.)*+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'\s*\.split\s*\(\s*'\|\s*'\s*\)""",
                    RegexOption.IGNORE_CASE
                ).find(html)

                if (packed != null) {
                    val encoded = packed.groupValues[1]
                    val radix = packed.groupValues[2].toIntOrNull() ?: return
                    val count = packed.groupValues[3].toIntOrNull() ?: return
                    val dictionary = packed.groupValues[4].split("|")

                    var result = encoded
                    for (i in (count - 1) downTo 0) {
                        val replacement = dictionary.getOrNull(i)
                        if (!replacement.isNullOrEmpty()) {
                            val word = i.toString(radix)
                            result = result.replace(Regex("\\b" + Regex.escape(word) + "\\b"), replacement)
                        }
                    }
                    
                    fileUrl = Regex(regexPattern).find(result)?.groupValues?.getOrNull(1)
                }
            }

            if (fileUrl == null) return

            val isM3u8 = fileUrl.contains(".m3u8", ignoreCase = true)

            if (isM3u8) {
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = fileUrl,
                    referer = url,
                    headers = mapOf("Referer" to url, "Origin" to mainUrl)
                )
                if (links.isNotEmpty()) {
                    links.forEach { callback(it) }
                    return
                }
            }

            callback.invoke(
                newExtractorLink(name, name, fileUrl) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.headers = mapOf("Referer" to url, "Origin" to mainUrl)
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
