package com.animasu

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Animasu : MainAPI() {
    override var mainUrl = "https://v1.animasu.app"
    override var name = "Animasu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime
            return when {
                t.contains("Tv", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            if (t == null) return ShowStatus.Completed
            return when {
                t.contains("Sedang Tayang", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "urutan=update" to "Baru diupdate",
        "status=&tipe=&urutan=publikasi" to "Baru ditambahkan",
        "status=&tipe=&urutan=populer" to "Terpopuler",
        "status=&tipe=&urutan=rating" to "Rating Tertinggi",
        "status=&tipe=Movie&urutan=update" to "Movie Terbaru",
        "status=&tipe=Movie&urutan=populer" to "Movie Terpopuler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/pencarian/?${request.data}&halaman=$page").document
        val home = document.select("div.listupd div.bs").map { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore("-episode")
                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select("div.tt").text().trim()
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val epNum = this.selectFirst("span.epx")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.select("div.listupd div.bs").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.infox h1")?.text().toString().replace("Sub Indo", "").trim()
        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()
        val table = document.selectFirst("div.infox div.spe")
        val type = getType(table?.selectFirst("span:contains(Jenis:)")?.ownText())
        val year = table?.selectFirst("span:contains(Rilis:)")?.ownText()?.substringAfterLast(",")?.trim()?.toIntOrNull()
        val status = table?.selectFirst("span:contains(Status:) font")?.text()
        val trailer = document.selectFirst("div.trailer iframe")?.attr("src")
        
        val episodes = document.select("ul#daftarepisode > li").map {
            val link = fixUrl(it.selectFirst("a")!!.attr("href"))
            val name = it.selectFirst("a")?.text() ?: ""
            val episode = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(link) { this.episode = episode }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(status)
            plot = document.select("div.sinopsis p").text()
            this.tags = table?.select("span:contains(Genre:) a")?.map { it.text() }
            addTrailer(trailer)
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
        val document = app.get(data).document
        
        document.select(".mobius > .mirror > option").mapNotNull {
            val value = it.attr("value")
            if (value.isBlank()) return@mapNotNull null
            
            val iframeSrc = Jsoup.parse(base64Decode(value)).select("iframe").attr("src")
            if (iframeSrc.isBlank()) return@mapNotNull null
            
            iframeSrc to it.text()
        }.amap { (iframe, quality) ->
            var finalUrl = iframe
            
            // 1. Bongkar Paksa Redirect Shortlink
            if (finalUrl.contains("short.icu") || finalUrl.contains("short.ink") || finalUrl.contains("goid.space")) {
                try {
                    val res = app.get(finalUrl)
                    finalUrl = res.url // Tangkap HTTP Redirect
                    
                    val iframeInside = res.document.selectFirst("iframe")?.attr("src")
                    if (!iframeInside.isNullOrBlank()) {
                        finalUrl = iframeInside
                    } else {
                        val match = Regex("""window\.location(?:\.href)?\s*=\s*['"](.*?)['"]""").find(res.text)
                        if (match != null) finalUrl = match.groupValues[1]
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"

            // 2. Sesuaikan Domain
            if (finalUrl.contains("uservideo.in") || finalUrl.contains("uservideo.nanime.in")) {
                finalUrl = finalUrl.replace(".in", ".xyz").replace(".nanime.in", ".xyz")
            }
            if (finalUrl.contains("uservideo.xyz") && !finalUrl.contains("new.uservideo.xyz")) {
                finalUrl = finalUrl.replace("uservideo.xyz", "new.uservideo.xyz")
            }
            if (finalUrl.contains("mega.nz/embed/")) {
                finalUrl = finalUrl.replace("mega.nz/embed/", "mega.nz/file/")
            }

            // 3. PANGGIL EXTRACTOR SECARA LANGSUNG (MENCEGAH ERROR CLOUDSTREAM)
            try {
                when {
                    finalUrl.contains("new.uservideo.xyz") -> {
                        Newuservideo().getUrl(finalUrl, "$mainUrl/", subtitleCallback, callback)
                    }
                    finalUrl.contains("vidhide") -> {
                        Vidhidepro().getUrl(finalUrl, "$mainUrl/", subtitleCallback, callback)
                    }
                    finalUrl.contains("archivd.net") -> {
                        Archivd().getUrl(finalUrl, "$mainUrl/", subtitleCallback, callback)
                    }
                    else -> {
                        loadFixedExtractor(finalUrl, quality, "$mainUrl/", subtitleCallback, callback)
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
        quality: String?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type
                    ) {
                        this.referer = link.referer
                        this.quality = if (link.type == ExtractorLinkType.M3U8 || link.name == "Uservideo") link.quality else getIndexQuality(quality)
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }
}

// ==========================================================
// KUMPULAN CLASS EXTRACTOR (Ditaruh di sini agar langsung bisa dipanggil)
// ==========================================================

class Newuservideo : ExtractorApi() {
    override val name: String = "Uservideo"
    override val mainUrl: String = "https://new.uservideo.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer)
        var targetUrl = url
        
        // Cek kalau ini halaman luar (embed wrapper), ambil src iframe aslinya
        val iframeSrc = res.document.selectFirst("iframe#videoFrame")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            targetUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
        }

        // Ambil isi HTML dari Player aslinya
        val playerHtml = app.get(targetUrl, referer = "$mainUrl/").text
        
        // Cari settingan Json
        val jsonMatch = "VIDEO_CONFIG\\s?=\\s?(\\{.*?\\});".toRegex().find(playerHtml) 
            ?: "VIDEO_CONFIG\\s?=\\s?(.*)".toRegex().find(playerHtml)
        val json = jsonMatch?.groupValues?.getOrNull(1)

        var isExtracted = false
        if (json != null) {
            AppUtils.tryParseJson<Sources>(json)?.streams?.forEach {
                val playUrl = it.playUrl ?: return@forEach
                isExtracted = true
                callback.invoke(
                    newExtractorLink(this.name, this.name, playUrl, INFER_TYPE) {
                        this.referer = "$mainUrl/"
                        this.quality = when (it.formatId) {
                            18 -> Qualities.P360.value
                            22 -> Qualities.P720.value
                            else -> Qualities.Unknown.value
                        }
                    }
                )
            }
        }
        
        // Jika JSON gagal atau diganti formatnya, pakai cara barbar regex m3u8
        if (!isExtracted) {
            val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8.*?)["']""")
            m3u8Regex.findAll(playerHtml).forEach {
                callback.invoke(
                    newExtractorLink(this.name, this.name, it.groupValues[1], INFER_TYPE) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P720.value // Asumsi resolusi tertinggi
                    }
                )
            }
        }
    }

    data class Streams(@JsonProperty("play_url") val playUrl: String? = null, @JsonProperty("format_id") val formatId: Int? = null)
    data class Sources(@JsonProperty("streams") val streams: ArrayList<Streams>? = null)
}

class Vidhidepro : Filesim() {
    override val mainUrl = "https://vidhidepro.com"
    override val name = "Vidhidepro"
}

class Archivd : ExtractorApi() {
    override val name: String = "Archivd"
    override val mainUrl: String = "https://archivd.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url).document
        val json = res.select("div#app").attr("data-page")
        val video = AppUtils.tryParseJson<Sources>(json)?.props?.datas?.data?.link?.media
        
        if (video != null) {
            callback.invoke(newExtractorLink(this.name, this.name, video, INFER_TYPE) { this.referer = "$mainUrl/" })
        }
    }

    data class Link(@JsonProperty("media") val media: String? = null)
    data class Data(@JsonProperty("link") val link: Link? = null)
    data class Datas(@JsonProperty("data") val data: Data? = null)
    data class Props(@JsonProperty("datas") val datas: Datas? = null)
    data class Sources(@JsonProperty("props") val props: Props? = null)
}
