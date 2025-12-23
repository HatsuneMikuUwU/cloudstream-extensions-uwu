package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

object SoraExtractor : SoraStream() {

    suspend fun invokeGomovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeGpress(
            title,
            year,
            season,
            episode,
            callback,
            gomoviesAPI,
            "Gomovies",
            base64Decode("X3NtUWFtQlFzRVRi"),
            base64Decode("X3NCV2NxYlRCTWFU"),
        )
    }

    private suspend fun invokeGpress(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        api: String,
        name: String,
        mediaSelector: String,
        episodeSelector: String,
    ) {
        fun String.decrypt(key: String): List<GpressSources>? {
            return tryParseJson<List<GpressSources>>(base64Decode(this).xorDecrypt(key))
        }

        val slug = getEpisodeSlug(season, episode)
        val query = if (season == null) {
            title
        } else {
            "$title Season $season"
        }

        var cookies = mapOf(
            "_identitygomovies7" to """5a436499900c81529e3740fd01c275b29d7e2fdbded7d760806877edb1f473e0a%3A2%3A%7Bi%3A0%3Bs%3A18%3A%22_identitygomovies7%22%3Bi%3A1%3Bs%3A52%3A%22%5B2800906%2C%22L2aGGTL9aqxksKR0pLvL66TunKNe1xXb%22%2C2592000%5D%22%3B%7D""",
        )

        var res = app.get("$api/search/$query", cookies = cookies)
        cookies = gomoviesCookies ?: res.cookies.filter { it.key == "advanced-frontendgomovies7" }
            .also { gomoviesCookies = it }
        val doc = res.document
        val media = doc.select("div.$mediaSelector").map {
            Triple(it.attr("data-filmName"), it.attr("data-year"), it.select("a").attr("href"))
        }.let { el ->
            if (el.size == 1) {
                el.firstOrNull()
            } else {
                el.find {
                    if (season == null) {
                        (it.first.equals(title, true) || it.first.equals(
                            "$title ($year)",
                            true
                        )) && it.second.equals("$year")
                    } else {
                        it.first.equals("$title - Season $season", true)
                    }
                }
            } ?: el.find { it.first.contains("$title", true) && it.second.equals("$year") }
        } ?: return

        val iframe = if (season == null) {
            media.third
        } else {
            app.get(
                fixUrl(
                    media.third,
                    api
                )
            ).document.selectFirst("div#$episodeSelector a:contains(Episode ${slug.second})")
                ?.attr("href")
        } ?: return

        res = app.get(fixUrl(iframe, api), cookies = cookies)
        val url = res.document.select("meta[property=og:url]").attr("content")
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        val qualities = intArrayOf(2160, 1440, 1080, 720, 480, 360)

        val (serverId, episodeId) = if (season == null) {
            url.substringAfterLast("/") to "0"
        } else {
            url.substringBeforeLast("/").substringAfterLast("/") to url.substringAfterLast("/")
                .substringBefore("-")
        }
        val serverRes = app.get(
            "$api/user/servers/$serverId?ep=$episodeId",
            cookies = cookies,
            headers = headers
        )
        val script = getAndUnpack(serverRes.text)
        val key = """key\s*="\s*(\d+)"""".toRegex().find(script)?.groupValues?.get(1) ?: return
        serverRes.document.select("ul li").amap { el ->
            val server = el.attr("data-value")
            val encryptedData = app.get(
                "$url?server=$server&_=$unixTimeMS",
                cookies = cookies,
                referer = url,
                headers = headers
            ).text
            val links = encryptedData.decrypt(key)
            links?.forEach { video ->
                qualities.filter { it <= video.max.toInt() }.forEach {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            video.src.split("360", limit = 3).joinToString(it.toString()),
                            ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = "$api/"
                            this.quality = it
                        }
                    )
                }
            }
        }

    }

    suspend fun invokeIdlix(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.createSlug()
        val url = if (season == null) {
            "$idlixAPI/movie/$fixTitle-$year"
        } else {
            "$idlixAPI/episode/$fixTitle-season-$season-episode-$episode"
        }
        invokeWpmovies("Idlix", url, subtitleCallback, callback, encrypt = true)
    }

    private suspend fun invokeWpmovies(
        name: String? = null,
        url: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        fixIframe: Boolean = false,
        encrypt: Boolean = false,
        hasCloudflare: Boolean = false,
        interceptor: Interceptor? = null,
    ) {

        val res = app.get(url ?: return, interceptor = if (hasCloudflare) interceptor else null)
        val referer = getBaseUrl(res.url)
        val document = res.document
        document.select("ul#playeroptionsul > li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }.amap { (id, nume, type) ->
            val json = app.post(
                url = "$referer/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type
                ),
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest"),
                referer = url,
                interceptor = if (hasCloudflare) interceptor else null
            ).text
            val source = tryParseJson<ResponseHash>(json)?.let {
                when {
                    encrypt -> {
                        val meta = tryParseJson<Map<String, String>>(it.embed_url)?.get("m")
                            ?: return@amap
                        val key = generateWpKey(it.key ?: return@amap, meta)
                        AesHelper.cryptoAESHandler(
                            it.embed_url,
                            key.toByteArray(),
                            false
                        )?.fixUrlBloat()
                    }

                    fixIframe -> Jsoup.parse(it.embed_url).select("IFRAME").attr("SRC")
                    else -> it.embed_url
                }
            } ?: return@amap
            when {
                source.startsWith("https://jeniusplay.com") -> {
                    Jeniusplay2().getUrl(source, "$referer/", subtitleCallback, callback)
                }

                !source.contains("youtube") -> {
                    loadExtractor(source, "$referer/", subtitleCallback, callback)
                }

                else -> {
                    return@amap
                }
            }

        }
    }

    suspend fun invokeVidsrccc(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {

        val url = if (season == null) {
            "$vidsrcccAPI/v2/embed/movie/$tmdbId"
        } else {
            "$vidsrcccAPI/v2/embed/tv/$tmdbId/$season/$episode"
        }

        val script =
            app.get(url).document.selectFirst("script:containsData(userId)")?.data() ?: return

        val userId = script.substringAfter("userId = \"").substringBefore("\";")
        val v = script.substringAfter("v = \"").substringBefore("\";")

        val vrf = VidsrcHelper.encryptAesCbc("$tmdbId", "RNckJONvMn_$userId")

        val serverUrl = if (season == null) {
            "$vidsrcccAPI/api/$tmdbId/servers?id=$tmdbId&type=movie&v=$v&vrf=$vrf&imdbId=$imdbId"
        } else {
            "$vidsrcccAPI/api/$tmdbId/servers?id=$tmdbId&type=tv&v=$v&vrf=$vrf&imdbId=$imdbId&season=$season&episode=$episode"
        }

        app.get(serverUrl).parsedSafe<VidsrcccResponse>()?.data?.amap {
            val sources =
                app.get("$vidsrcccAPI/api/source/${it.hash}").parsedSafe<VidsrcccResult>()?.data
                    ?: return@amap

            when {
                it.name.equals("VidPlay") -> {

                    callback.invoke(
                        newExtractorLink(
                            "VidPlay",
                            "VidPlay",
                            sources.source ?: return@amap,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$vidsrcccAPI/"
                        }
                    )

                    sources.subtitles?.map {
                        subtitleCallback.invoke(
                            SubtitleFile(
                                it.label ?: return@map,
                                it.file ?: return@map
                            )
                        )
                    }
                }

                it.name.equals("UpCloud") -> {
                    val scriptData = app.get(
                        sources.source ?: return@amap,
                        referer = "$vidsrcccAPI/"
                    ).document.selectFirst("script:containsData(source =)")?.data()
                    val iframe = Regex("source\\s*=\\s*\"([^\"]+)").find(
                        scriptData ?: return@amap
                    )?.groupValues?.get(1)?.fixUrlBloat()

                    val iframeRes =
                        app.get(iframe ?: return@amap, referer = "https://lucky.vidbox.site/").text

                    val id = iframe.substringAfterLast("/").substringBefore("?")
                    val key = Regex("\\w{48}").find(iframeRes)?.groupValues?.get(0) ?: return@amap

                    app.get(
                        "${iframe.substringBeforeLast("/")}/getSources?id=$id&_k=$key",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                        ),
                        referer = iframe
                    ).parsedSafe<UpcloudResult>()?.sources?.amap file@{ source ->
                        callback.invoke(
                            newExtractorLink(
                                "UpCloud",
                                "UpCloud",
                                source.file ?: return@file,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$vidsrcccAPI/"
                            }
                        )
                    }

                }

                else -> {
                    return@amap
                }
            }
        }


    }

    suspend fun invokeVidsrc(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val api = "https://cloudnestra.com"
        val url = if (season == null) {
            "$vidSrcAPI/embed/movie?imdb=$imdbId"
        } else {
            "$vidSrcAPI/embed/tv?imdb=$imdbId&season=$season&episode=$episode"
        }

        app.get(url).document.select(".serversList .server").amap { server ->
            when {
                server.text().equals("CloudStream Pro", ignoreCase = true) -> {
                    val hash =
                        app.get("$api/rcp/${server.attr("data-hash")}").text.substringAfter("/prorcp/")
                            .substringBefore("'")
                    val res = app.get("$api/prorcp/$hash").text
                    val m3u8Link = Regex("https:.*\\.m3u8").find(res)?.value

                    callback.invoke(
                        newExtractorLink(
                            "Vidsrc",
                            "Vidsrc",
                            m3u8Link ?: return@amap,
                            ExtractorLinkType.M3U8
                        )
                    )
                }

                server.text().equals("2Embed", ignoreCase = true) -> {
                    return@amap
                }

                server.text().equals("Superembed", ignoreCase = true) -> {
                    return@amap
                }

                else -> {
                    return@amap
                }
            }
        }

    }

    suspend fun invokeXprime(
        tmdbId: Int?,
        title: String? = null,
        year: Int? = null,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val servers = listOf("rage", "primebox")
        val serverName = servers.map { it.capitalize() }
        val referer = "https://xprime.tv/"
        runAllAsync(
            {
                val url = if (season == null) {
                    "$xprimeAPI/${servers.first()}?id=$tmdbId"
                } else {
                    "$xprimeAPI/${servers.first()}?id=$tmdbId&season=$season&episode=$episode"
                }

                val source = app.get(url).parsedSafe<RageSources>()?.url

                callback.invoke(
                    newExtractorLink(
                        serverName.first(),
                        serverName.first(),
                        source ?: return@runAllAsync,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                    }
                )
            },
            {
                val url = if (season == null) {
                    "$xprimeAPI/${servers.last()}?name=$title&fallback_year=$year"
                } else {
                    "$xprimeAPI/${servers.last()}?name=$title&fallback_year=$year&season=$season&episode=$episode"
                }

                val sources = app.get(url).parsedSafe<PrimeboxSources>()

                sources?.streams?.map { source ->
                    callback.invoke(
                        newExtractorLink(
                            serverName.last(),
                            serverName.last(),
                            source.value,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.quality = getQualityFromName(source.key)
                        }
                    )
                }

                sources?.subtitles?.map { subtitle ->
                    subtitleCallback.invoke(
                        SubtitleFile(
                            subtitle.label ?: "",
                            subtitle.file ?: return@map
                        )
                    )
                }

            }
        )
    }

    suspend fun invokeWatchsomuch(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val id = imdbId?.removePrefix("tt")
        val epsId = app.post(
            "${watchSomuchAPI}/Watch/ajMovieTorrents.aspx", data = mapOf(
                "index" to "0",
                "mid" to "$id",
                "wsk" to "30fb68aa-1c71-4b8c-b5d4-4ca9222cfb45",
                "lid" to "",
                "liu" to ""
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<WatchsomuchResponses>()?.movie?.torrents?.let { eps ->
            if (season == null) {
                eps.firstOrNull()?.id
            } else {
                eps.find { it.episode == episode && it.season == season }?.id
            }
        } ?: return

        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

        val subUrl = if (season == null) {
            "${watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part="
        } else {
            "${watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${seasonSlug}E${episodeSlug}"
        }

        app.get(subUrl).parsedSafe<WatchsomuchSubResponses>()?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.label?.substringBefore("&nbsp")?.trim() ?: "",
                    fixUrl(sub.url ?: return@map null, watchSomuchAPI)
                )
            )
        }


    }

    suspend fun invokeMapple(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val mediaType = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "$mappleAPI/watch/$mediaType/$tmdbId"
        } else {
            "$mappleAPI/watch/$mediaType/$season-$episode/$tmdbId"
        }

        val data = if (season == null) {
            """[{"mediaId":$tmdbId,"mediaType":"$mediaType","tv_slug":"","source":"mapple","sessionId":"session_1760391974726_qym92bfxu"}]"""
        } else {
            """[{"mediaId":$tmdbId,"mediaType":"$mediaType","tv_slug":"$season-$episode","source":"mapple","sessionId":"session_1760391974726_qym92bfxu"}]"""
        }

        val headers = mapOf(
            "Next-Action" to "403f7ef15810cd565978d2ac5b7815bb0ff20258a5",
        )

        val res = app.post(
            url,
            requestBody = data.toRequestBody(RequestBodyTypes.TEXT.toMediaTypeOrNull()),
            headers = headers
        ).text
        val videoLink =
            tryParseJson<MappleSources>(res.substringAfter("1:").trim())?.data?.stream_url

        callback.invoke(
            newExtractorLink(
                "Mapple",
                "Mapple",
                videoLink ?: return,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "$mappleAPI/"
                this.headers = mapOf(
                    "Accept" to "*/*"
                )
            }
        )

        val subRes = app.get(
            "$mappleAPI/api/subtitles?id=$tmdbId&mediaType=$mediaType${if (season == null) "" else "&season=1&episode=1"}",
            referer = "$mappleAPI/"
        ).text
        tryParseJson<ArrayList<MappleSubtitle>>(subRes)?.map { subtitle ->
            subtitleCallback.invoke(
                SubtitleFile(
                    subtitle.display ?: "",
                    fixUrl(subtitle.url ?: return@map, mappleAPI)
                )
            )
        }

    }

    suspend fun invokeVidlink(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "$vidlinkAPI/$type/$tmdbId"
        } else {
            "$vidlinkAPI/$type/$tmdbId/$season/$episode"
        }

        val videoLink = app.get(
            url, interceptor = WebViewResolver(
                Regex("""$vidlinkAPI/api/b/$type/A{32}"""), timeout = 15_000L
            )
        ).parsedSafe<VidlinkSources>()?.stream?.playlist

        callback.invoke(
            newExtractorLink(
                "Vidlink",
                "Vidlink",
                videoLink ?: return,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "$vidlinkAPI/"
            }
        )

    }

    suspend fun invokeVidfast(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "$vidfastAPI/$type/$tmdbId"
        } else {
            "$vidfastAPI/$type/$tmdbId/$season/$episode"
        }

        val res = app.get(
            url, interceptor = WebViewResolver(
                Regex("""$vidfastAPI/hezushon/"""),
                timeout = 15_000L
            )
        )

        tryParseJson<ArrayList<VidFastServers>>(res.text)?.filter { it.description?.contains("Original audio") == true }
            ?.amapIndexed { index, server ->
                val source =
                    app.get("${res.url.substringBeforeLast("/").substringBeforeLast("/")}/HYaF/${server.data}", referer = "$vidfastAPI/",
                        headers = mapOf(
                            "X-Csrf-Token" to "V8mmyuaJMUuox6S6Y8OVt5fdfQOyiZfQ",
                            "X-Requested-With" to "XMLHttpRequest"
                        ))
                        .parsedSafe<VidFastSources>()

                callback.invoke(
                    newExtractorLink(
                        "Vidfast",
                        "Vidfast [${server.name}]",
                        source?.url ?: return@amapIndexed,
                        INFER_TYPE
                    )
                )

                if (index == 1) {
                    source.tracks?.map { subtitle ->
                        subtitleCallback.invoke(
                            SubtitleFile(
                                subtitle.label ?: return@map,
                                subtitle.file ?: return@map
                            )
                        )
                    }
                }

            }


    }

    suspend fun invokeWyzie(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val url = if (season == null) {
            "$wyzieAPI/search?id=$tmdbId"
        } else {
            "$wyzieAPI/search?id=$tmdbId&season=$season&episode=$episode"
        }

        val res = app.get(url).text

        tryParseJson<ArrayList<WyzieSubtitle>>(res)?.map { subtitle ->
            subtitleCallback.invoke(
                SubtitleFile(
                    subtitle.display ?: return@map,
                    subtitle.url ?: return@map,
                )
            )
        }

    }

    suspend fun invokeVixsrc(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val proxy = "https://proxy.heistotron.uk"
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "$vixsrcAPI/$type/$tmdbId"
        } else {
            "$vixsrcAPI/$type/$tmdbId/$season/$episode"
        }

        val res =
            app.get(url).document.selectFirst("script:containsData(window.masterPlaylist)")?.data()
                ?: return

        val video1 =
            Regex("""'token':\s*'(\w+)'[\S\s]+'expires':\s*'(\w+)'[\S\s]+url:\s*'(\S+)'""").find(res)
                ?.let {
                    val (token, expires, path) = it.destructured
                    "$path?token=$token&expires=$expires&h=1&lang=en"
                } ?: return

        val video2 =
            "$proxy/p/${base64Encode("$proxy/api/proxy/m3u8?url=${encode(video1)}&source=sakura|ananananananananaBatman!".toByteArray())}"

        listOf(
            VixsrcSource("Vixsrc [Alpha]",video1,url),
            VixsrcSource("Vixsrc [Beta]",video2, "$mappleAPI/"),
        ).map {
            callback.invoke(
                newExtractorLink(
                    it.name,
                    it.name,
                    it.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = it.referer
                    this.headers = mapOf(
                        "Accept" to "*/*"
                    )
                }
            )
        }

    }

    suspend fun invokeVidsrccx(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val filePath =
            if (season == null) "/media/$tmdbId/master.m3u8" else "/media/$tmdbId-$season-$episode/master.m3u8"
        val video = app.post(
            "https://8ball.piracy.cloud/api/generate-secure-url", requestBody = mapOf(
                "filePath" to filePath
            ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        ).parsedSafe<VidsrccxSource>()?.secureUrl

        callback.invoke(
            newExtractorLink(
                "VidsrcCx",
                "VidsrcCx",
                video ?: return,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "$vidsrccxAPI/"
                this.headers = mapOf(
                    "Accept" to "*/*"
                )
            }
        )

    }

    suspend fun invokeSuperembed(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        api: String = "https://streamingnow.mov"
    ) {
        val path = if (season == null) "" else "&s=$season&e=$episode"
        val token = app.get("$superembedAPI/directstream.php?video_id=$tmdbId&tmdb=1$path").url.substringAfter(
                "?play="
            )

        val (server, id) = app.post(
            "$api/response.php", data = mapOf(
                "token" to token
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document.select("ul.sources-list li:contains(vipstream-S)")
            .let { it.attr("data-server") to it.attr("data-id") }

        val playUrl = "$api/playvideo.php?video_id=$id&server_id=$server&token=$token&init=1"
        val playRes = app.get(playUrl).document
        val iframe = playRes.selectFirst("iframe.source-frame")?.attr("src") ?: run {
            val captchaId = playRes.select("input[name=captcha_id]").attr("value")
            app.post(playUrl, requestBody = "captcha_id=TEduRVR6NmZ3Sk5Jc3JpZEJCSlhTM25GREs2RCswK0VQN2ZsclI5KzNKL2cyV3dIaFEwZzNRRHVwMzdqVmoxV0t2QlBrNjNTY04wY2NSaHlWYS9Jc09nb25wZTV2YmxDSXNRZVNuQUpuRW5nbkF2dURsQUdJWVpwOWxUZzU5Tnh0NXllQjdYUG83Y0ZVaG1XRGtPOTBudnZvN0RFK0wxdGZvYXpFKzVNM2U1a2lBMG40REJmQ042SA%3D%3D&captcha_answer%5B%5D=8yhbjraxqf3o&captcha_answer%5B%5D=10zxn5vi746w&captcha_answer%5B%5D=gxfpe17tdwub".toRequestBody(RequestBodyTypes.TEXT.toMediaTypeOrNull())
            ).document.selectFirst("iframe.source-frame")?.attr("src")
        }
        val json = app.get(iframe ?: return).text.substringAfter("Playerjs(").substringBefore(");")

        val video = """file:"([^"]+)""".toRegex().find(json)?.groupValues?.get(1)

        callback.invoke(
            newExtractorLink(
                "Superembed",
                "Superembed",
                video ?: return,
                INFER_TYPE
            ) {
                this.headers = mapOf(
                    "Accept" to "*/*"
                )
            }
        )

        """subtitle:"([^"]+)""".toRegex().find(json)?.groupValues?.get(1)?.split(",")?.map {
            val (subLang, subUrl) = Regex("""\[(\w+)](http\S+)""").find(it)?.destructured
                ?: return@map
            subtitleCallback.invoke(
                SubtitleFile(
                    subLang.trim(),
                    subUrl.trim()
                )
            )
        }


    }

    suspend fun invokeVidrock(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        subAPI: String = "https://sub.vdrk.site"
    ) {

        val type = if (season == null) "movie" else "tv"
        val url = "$vidrockAPI/$type/$tmdbId${if(type == "movie") "" else "/$season/$episode"}"
        val encryptData = VidrockHelper.encrypt(tmdbId, type, season, episode)

        app.get("$vidrockAPI/api/$type/$encryptData", referer = url).parsedSafe<LinkedHashMap<String,HashMap<String,String>>>()
            ?.map { source ->
                if(source.key == "source2") {
                    val json = app.get(source.value["url"] ?: return@map, referer = "${vidrockAPI}/").text
                    tryParseJson<ArrayList<VidrockSource>>(json)?.reversed()?.map mirror@{
                        callback.invoke(
                            newExtractorLink(
                                "Vidrock",
                                "Vidrock [Source2]",
                                it.url ?: return@mirror,
                                INFER_TYPE
                            ) {
                                this.quality = it.resolution ?: Qualities.Unknown.value
                                this.headers = mapOf(
                                    "Range" to "bytes=0-",
                                    "Referer" to "${vidrockAPI}/"
                                )
                            }
                        )
                    }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            "Vidrock",
                            "Vidrock [${source.key.capitalize()}]",
                            source.value["url"] ?: return@map,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "${vidrockAPI}/"
                            this.headers = mapOf(
                                "Origin" to vidrockAPI
                            )
                        }
                    )
                }
            }

        val subUrl = "$subAPI/$type/$tmdbId${if(type == "movie") "" else "/$season/$episode"}"
        val res = app.get(subUrl).text
        tryParseJson<ArrayList<VidrockSubtitle>>(res)?.map { subtitle ->
            subtitleCallback.invoke(
                SubtitleFile(
                    subtitle.label?.replace(Regex("\\d"), "")?.replace(Regex("\\s+Hi"), "")?.trim() ?: return@map,
                    subtitle.file ?: return@map,
                )
            )
        }

    }

    suspend fun invokeEleven(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val path = "fcd552c4321aeac1e62c5304913b3420be75a19d390807281a425aabbb5dc4c0"
        val url = if(season == null) {
            "$elevenAPI/movie/$tmdbId"
        } else {
            "$elevenAPI/tv/$tmdbId/$season/$episode"
        }

        val res = app.get(
            url, interceptor = WebViewResolver(
                Regex("""$elevenAPI/.*/sr"""),
                timeout = 15_000L
            )
        ).text

        tryParseJson<ArrayList<ElevenServers>>(res)?.amap { server ->
            val source = app.get(
                "$elevenAPI/$path/${server.data}",
                referer = "$elevenAPI/",
                headers = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).parsedSafe<ElevenSource>()?.url

            callback.invoke(
                newExtractorLink(
                    "Eleven",
                    "Eleven [${server.name}]",
                    source ?: return@amap,
                    INFER_TYPE
                ) {
                    this.referer = "${elevenAPI}/"
                    this.headers = mapOf(
                        "Origin" to elevenAPI
                    )
                }
            )

        }

    }

}

