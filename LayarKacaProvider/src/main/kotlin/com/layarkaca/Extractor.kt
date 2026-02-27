package com.layarkaca

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

fun getHighQuality(label: String?): Int {
    return when {
        label == null -> Qualities.Unknown.value
        label.contains("1080") -> Qualities.P1080.value
        label.contains("720") -> Qualities.P720.value
        label.contains("480") -> Qualities.P480.value
        label.contains("360") -> Qualities.P360.value
        else -> getQualityFromName(label)
    }
}

open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val finalReferer = referer ?: "$mainUrl/"
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val response = app.get(url, referer = finalReferer)
            val playerScript = response.document.selectXpath("//script[contains(text(),'var urlPlay')]").html()
            
            if (playerScript.isNotBlank()) {
                val m3u8Url = playerScript.substringAfter("var urlPlay = '").substringBefore("'")
                val originUrl = try { URI(finalReferer).let { "${it.scheme}://${it.host}" } } catch (e: Exception) { mainUrl }
                
                val headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to finalReferer,
                    "Origin" to originUrl
                )
                
                sources.add(newExtractorLink(
                    source = name, 
                    name = "$name HD", 
                    url = m3u8Url, 
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = finalReferer
                    this.quality = Qualities.P1080.value // Memaksa ke HD jika manifest mendukung multi-kualitas
                    this.headers = headers
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sources
    }
}

open class P2PExtractor : ExtractorApi() {
    override var name = "P2P"
    override var mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false
    
    data class HownetworkResponse(val file: String?, val link: String?, val label: String?)

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val id = url.substringAfter("id=").substringBefore("&")
        val apiUrl = "$mainUrl/api2.php?id=$id"
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to url,
            "X-Requested-With" to "XMLHttpRequest"
        )
        
        val formBody = mapOf("r" to "https://playeriframe.sbs/", "d" to "cloud.hownetwork.xyz")
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val response = app.post(apiUrl, headers = headers, data = formBody).text
            val json = tryParseJson<HownetworkResponse>(response)
            val videoUrl = json?.file ?: json?.link
            
            if (!videoUrl.isNullOrBlank()) {
                sources.add(newExtractorLink(
                    source = name, 
                    name = name, 
                    url = videoUrl, 
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = getHighQuality(json.label)
                })
            }
        } catch (e: Exception) { e.printStackTrace() }
        return sources
    }
}

open class F16Extractor : ExtractorApi() {
    override var name = "F16"
    override var mainUrl = "https://f16px.com"
    override val requiresReferer = false

    data class F16Playback(val playback: PlaybackData?)
    data class PlaybackData(val iv: String?, val payload: String?, val key_parts: List<String>?)
    data class DecryptedSource(val url: String?, val label: String?)
    data class DecryptedResponse(val sources: List<DecryptedSource>?)

    private fun String.fixBase64(): String {
        var s = this
        while (s.length % 4 != 0) s += "="
        return s
    }

    private fun randomHex(length: Int): String {
        val chars = "0123456789abcdef"
        return (1..length).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val videoId = url.substringAfter("/e/").substringBefore("?")
            val apiUrl = "$mainUrl/api/videos/$videoId/embed/playback"
            val pageUrl = "$mainUrl/e/$videoId"
            
            val viewerId = randomHex(32) 
            val deviceId = randomHex(32)
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to pageUrl,
                "Origin" to mainUrl,
                "Content-Type" to "application/json",
                "x-embed-origin" to "playeriframe.sbs"
            )

            val jsonPayload = mapOf(
                "fingerprint" to mapOf(
                    "token" to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.dummy_payload.dummy_sig",
                    "viewer_id" to viewerId,
                    "device_id" to deviceId,
                    "confidence" to 0.99
                )
            )
            
            val responseText = app.post(apiUrl, headers = headers, json = jsonPayload).text
            val json = tryParseJson<F16Playback>(responseText)
            val pb = json?.playback

            if (pb?.payload != null && pb.iv != null && pb.key_parts?.size == 2) {
                val part1 = Base64.decode(pb.key_parts[0].fixBase64(), Base64.URL_SAFE)
                val part2 = Base64.decode(pb.key_parts[1].fixBase64(), Base64.URL_SAFE)
                val combinedKey = part1 + part2 

                val decryptedJson = decryptAesGcm(pb.payload, combinedKey, pb.iv)

                if (decryptedJson != null) {
                    val result = tryParseJson<DecryptedResponse>(decryptedJson)
                    result?.sources?.forEach { source ->
                        if (!source.url.isNullOrBlank()) {
                            sources.add(newExtractorLink(
                                source = name,
                                name = "$name ${source.label ?: "HD"}",
                                url = source.url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = getHighQuality(source.label)
                            })
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("F16Extractor", "Error: ${e.message}")
        }
        return sources
    }

    private fun decryptAesGcm(encryptedBase64: String, keyBytes: ByteArray, ivBase64: String): String? {
        return try {
            val iv = Base64.decode(ivBase64.fixBase64(), Base64.URL_SAFE)
            val cipherText = Base64.decode(encryptedBase64.fixBase64(), Base64.URL_SAFE)
            val spec = GCMParameterSpec(128, iv)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }
}
