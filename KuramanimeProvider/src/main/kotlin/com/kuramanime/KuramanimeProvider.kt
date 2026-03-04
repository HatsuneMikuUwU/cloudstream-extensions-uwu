package com.kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v16.kuramanime.ink" 
    override var name = "Kuramanime"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override var sequentialMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String, s: Int): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true) && s == 1) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Selesai Tayang" -> ShowStatus.Completed
                "Sedang Tayang" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.product__item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/episode")) {
            Regex("(.*)/episode/.+").find(uri)?.groupValues?.get(1).toString() + "/"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = getProperAnimeLink(fixUrl(this.selectFirst("a")!!.attr("href")))
        val title = this.selectFirst("h5 a")?.text() ?: return null
        val posterUrl = fixUrl(this.select("div.product__item__pic.set-bg").attr("data-setbg"))
        val episode = this.select("div.ep span").text().let {
            Regex("Ep\\s(\\d+)\\s/").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$mainUrl/anime?search=$query&order_by=latest"
        ).document.select("div.product__item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".anime__details__title > h3")!!.text().trim()
        val poster = document.selectFirst(".anime__details__pic")?.attr("data-setbg")
        val tags = document.select("div.anime__details__widget > div > div:nth-child(2) > ul > li:nth-child(1)")
                .text().trim().replace("Genre: ", "").split(", ")

        val year = Regex("\\D").replace(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(5)")
                .text().trim().replace("Musim: ", ""), ""
        ).toIntOrNull()
        val status = getStatus(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(3)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".anime__details__text > p").text().trim()

        val episodes = mutableListOf<Episode>()

        for (i in 1..30) {
            val doc = app.get("$url?page=$i").document
            val content = doc.select("#episodeLists").attr("data-content")
            if (content.isBlank()) break

            val eps = Jsoup.parse(content)
                .select("a.btn.btn-sm.btn-danger")
                .mapNotNull {
                    val name = it.text().trim()
                    val episodeNum = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()
                    val link = it.attr("href")
                    newEpisode(link) { this.episode = episodeNum }
                }
            if (eps.isEmpty()) break else episodes.addAll(eps)
        }

        val type = getType(
            document.selectFirst("div.col-lg-6.col-md-6 ul li:contains(Tipe:) a")?.text()?.lowercase() ?: "tv", 
            episodes.size
        )
        val recommendations = document.select("div#randomList > a").mapNotNull {
            val epHref = it.attr("href")
            val epTitle = it.select("h5.sidebar-title-h5.px-2.py-2").text()
            val epPoster = it.select(".product__sidebar__view__item.set-bg").attr("data-setbg")
            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                this.posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
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
        val req = app.get(data)
        val document = req.document
        val cookies = req.cookies

        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""

        val links = mutableListOf<Pair<String, Int>>()
        
        document.selectFirst("#player")?.attr("data-hls-src")?.takeIf { it.isNotBlank() }?.let {
            links.add(it to Qualities.Unknown.value)
        }
        
        document.select("video#player > source").forEach {
            val src = it.attr("src")
            val quality = it.attr("size").toIntOrNull() ?: Qualities.Unknown.value
            if (src.isNotBlank()) links.add(src to quality)
        }

        links.forEach { (rawLink, quality) ->
            val link = fixUrl(rawLink)

            if (link.contains("pid=") && link.contains("sid=")) {
                val pid = Regex("pid=([^&]+)").find(link)?.groupValues?.get(1)
                val sid = Regex("sid=([^&]+)").find(link)?.groupValues?.get(1)

                if (pid != null && sid != null) {
                    try {
                        val tokenReq = app.post(
                            "$mainUrl/misc/token/drive-token",
                            headers = mapOf(
                                "X-CSRF-TOKEN" to csrfToken,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Accept" to "application/json"
                            ),
                            json = mapOf("pid" to pid, "sid" to sid),
                            cookies = cookies
                        )
                        
                        val accessToken = Regex(""""access_token"\s*:\s*"([^"]+)"""").find(tokenReq.text)?.groupValues?.get(1)
                        val gid = Regex(""""gid"\s*:\s*"([^"]+)"""").find(tokenReq.text)?.groupValues?.get(1)

                        if (accessToken != null && gid != null) {
                            callback.invoke(
                                newExtractorLink(
                                    "Kuramadrive",
                                    "Kuramadrive",
                                    "https://www.googleapis.com/drive/v3/files/$gid?alt=media",
                                    referer = "$mainUrl/",
                                    quality = quality,
                                    type = INFER_TYPE
                                ) {
                                    this.headers = mapOf(
                                        "Authorization" to "Bearer $accessToken",
                                        "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                                        "Range" to "bytes=0-"
                                    )
                                }
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else {
                callback.invoke(
                    newExtractorLink(
                        "Server Lokal",
                        "Server Lokal",
                        link,
                        referer = "$mainUrl/",
                        quality = quality,
                        type = INFER_TYPE
                    )
                )
            }
        }

        document.select("div#animeDownloadLink a").forEach { a ->
            val link = a.attr("href")
            if (link.startsWith("http")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
