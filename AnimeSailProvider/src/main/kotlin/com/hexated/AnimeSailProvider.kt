package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"),
            cookies = mapOf("_as_ipin_ct" to "ID"),
            referer = ref
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/rilisan-anime-terbaru/page/" to "Episode Anime Terbaru",
        "$mainUrl/rilisan-donghua-terbaru/page/" to "Episode Donghua Terbaru",
        "$mainUrl/movie-terbaru/page/" to "Movie Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data + page).document
        val home = document.select("article").map {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore(
                    "-episode"
                )

                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }

            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select(".tt > h2").text().trim()
        val posterUrl = fixUrlNull(this.selectFirst("div.limit img")?.attr("src"))
        val epNum = this.selectFirst(".tt > h2")?.text()?.let {
            Regex("Episode\\s?(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = request(link).document

        return document.select("div.listupd article").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title")?.text().toString()
            .replace("Subtitle Indonesia", "").trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        val episodes = document.select("ul.daftar > li").map {
            val link = fixUrl(it.select("a").attr("href"))
            val name = it.select("a").text()
            val episode =
                Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()
            newEpisode(link) { this.episode = episode }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus =
                getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            plot = document.selectFirst("div.entry-content > p")?.text()
            this.tags =
                document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = request(data).document

        // Menggunakan amap untuk proses async per item
        document.select(".mobius > .mirror > option").amap {
            // Gunakan try-catch biasa, bukan safeApiCall, agar support suspend function
            try {
                val dataEm = it.attr("data-em")
                if (dataEm.isBlank()) return@amap

                // Decode base64 link
                val iframe = fixUrl(
                    Jsoup.parse(base64Decode(dataEm)).select("iframe").attr("src")
                )
                
                val label = it.text()
                val quality = getIndexQuality(label)

                when {
                    // 1. GENERIC INTERNAL PLAYER HANDLER
                    // Menangkap semua player yang ada di /utils/player/ (Kodir, Arch, Race, Hex, Vip, dll)
                    iframe.contains("/utils/player/") -> {
                        val playerDoc = request(iframe, ref = data).document
                        
                        // Cek apakah pakai tag <source> (Style Arch/Race/Hex)
                        val source = playerDoc.select("source").attr("src")
                        
                        if (source.isNotBlank()) {
                            val sourceName = when {
                                iframe.contains("/arch/") -> "Arch"
                                iframe.contains("/race/") -> "Race"
                                iframe.contains("/hexupload/") -> "Hexupload"
                                iframe.contains("/pomf/") -> "Pomf"
                                else -> "Internal"
                            }
                            callback.invoke(
                                newExtractorLink(
                                    source = sourceName,
                                    name = "$sourceName $label",
                                    url = source,
                                    INFER_TYPE
                                ) {
                                    referer = mainUrl
                                    this.quality = quality
                                }
                            )
                        } 
                        // Jika tidak ada <source>, cek di script (Style Kodir)
                        else {
                            val script = playerDoc.select("script").html()
                            // Regex cari link http...mp4/m3u8 di dalam variable javascript
                            val linkRaw = Regex("""src\s*=\s*['"](http.*?)['"]""").find(script)?.groupValues?.get(1)
                                ?: playerDoc.toString().substringAfter("= `").substringBefore("`;") // Fallback

                            val finalLink = if (linkRaw.contains("http")) {
                                Jsoup.parse(linkRaw).select("source").attr("src").ifBlank { linkRaw }
                            } else null

                            if (!finalLink.isNullOrBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "Kodir",
                                        name = "Kodir $label",
                                        url = finalLink,
                                        INFER_TYPE
                                    ) {
                                        referer = mainUrl
                                        this.quality = quality
                                    }
                                )
                            }
                        }
                    }

                    // 2. REDIRECT HANDLER (Aghanim / Rasa Cintaku)
                    iframe.contains("aghanim.xyz") || iframe.contains("rasa-cintaku") -> {
                        val id = Regex("""id=([^&]+)""").find(iframe)?.groupValues?.get(1)
                        if (id != null) {
                            val link = "https://rasa-cintaku-semakin-berantai.xyz/v/$id"
                            loadFixedExtractor(link, quality, mainUrl, subtitleCallback, callback)
                        }
                    }

                    // 3. EXTERNAL PLAYER (Framezilla, Uservideo, dll)
                    iframe.contains("framezilla") || iframe.contains("uservideo") -> {
                         val innerLink = request(iframe, ref = data).document.select("iframe").attr("src")
                         if(innerLink.isNotBlank()){
                             loadFixedExtractor(fixUrl(innerLink), quality, mainUrl, subtitleCallback, callback)
                         }
                    }

                    // SKIP BAD SOURCES
                    iframe.contains("/fichan/") || iframe.contains("/blogger/") -> return@amap

                    // 4. FALLBACK (Sisanya biarkan Extractor bawaan CS menangani)
                    else -> {
                        loadFixedExtractor(iframe, quality, mainUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // RunBlocking dihapus, langsung panggil fungsi suspend loadExtractor
        loadExtractor(url, referer, subtitleCallback) { link ->
            callback.invoke(
                newExtractorLink(
                    source = link.name,
                    name = link.name,
                    url = link.url,
                    INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = if (link.type == ExtractorLinkType.M3U8) link.quality else quality
                        ?: Qualities.Unknown.value
                    this.type = link.type
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
