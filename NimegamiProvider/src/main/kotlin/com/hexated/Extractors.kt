package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import org.json.JSONObject

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
