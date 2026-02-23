package com.sora

import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.SoraExtractor.invokeEleven
import com.hexated.SoraExtractor.invokeGomovies
import com.hexated.SoraExtractor.invokeIdlix
import com.hexated.SoraExtractor.invokeMapple
import com.hexated.SoraExtractor.invokeSuperembed
import com.hexated.SoraExtractor.invokeVidfast
import com.hexated.SoraExtractor.invokeVidlink
import com.hexated.SoraExtractor.invokeVidrock
import com.hexated.SoraExtractor.invokeVidsrc
import com.hexated.SoraExtractor.invokeVidsrccc
import com.hexated.SoraExtractor.invokeVidsrccx
import com.hexated.SoraExtractor.invokeVixsrc
import com.hexated.SoraExtractor.invokeWatchsomuch
import com.hexated.SoraExtractor.invokeWyzie
import com.hexated.SoraExtractor.invokeXprime
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.math.roundToInt

open class SoraStream : TmdbProvider() {
    override var name = "SoraStream"
    override val hasMainPage = true
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    val wpRedisInterceptor by lazy { CloudflareKiller() }

    /** AUTHOR : Hexated & Sora */
    companion object {
        /** TOOLS */
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        const val gdbot = "https://gdtot.pro"
        const val anilistAPI = "https://graphql.anilist.co"
        const val malsyncAPI = "https://api.malsync.moe"
        const val jikanAPI = "https://api.jikan.moe/v4"

        private const val apiKey = "b030404650f279792a8d3287232358e3"

        /** ALL SOURCES */
        const val gomoviesAPI = "https://gomovies-online.cam"
        const val idlixAPI = "https://tv6.idlixku.com"
        const val vidsrcccAPI = "https://vidsrc.cc"
        const val vidSrcAPI = "https://vidsrc.net"
        const val xprimeAPI = "https://backend.xprime.tv"
        const val watchSomuchAPI = "https://watchsomuch.tv"
        const val mappleAPI = "https://mapple.uk"
        const val vidlinkAPI = "https://vidlink.pro"
        const val vidfastAPI = "https://vidfast.pro"
        const val wyzieAPI = "https://sub.wyzie.ru"
        const val vixsrcAPI = "https://vixsrc.to"
        const val vidsrccxAPI = "https://vidsrc.cx"
        const val superembedAPI = "https://multiembed.mov"
        const val vidrockAPI = "https://vidrock.net"
        const val elevenAPI = "https://111movies.com"

        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

    }

    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/all/day?api_key=$apiKey&region=US" to "Trending",
        "$tmdbAPI/movie/popular?api_key=$apiKey&region=US" to "Popular Movies",
        "$tmdbAPI/tv/popular?api_key=$apiKey&region=US&with_original_language=en" to "Popular TV Shows",
        "$tmdbAPI/tv/airing_today?api_key=$apiKey&region=US&with_original_language=en" to "Airing Today TV Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=213" to "Netflix",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=1024" to "Amazon",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2739" to "Disney+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=453" to "Hulu",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2552" to "Apple TV+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=49" to "HBO",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=4330" to "Paramount+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=3353" to "Peacock",
        "$tmdbAPI/movie/top_rated?api_key=$apiKey&region=US" to "Top Rated Movies",
        "$tmdbAPI/tv/top_rated?api_key=$apiKey&region=US" to "Top Rated TV Shows",
        "$tmdbAPI/movie/upcoming?api_key=$apiKey&region=US" to "Upcoming Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko" to "Korean Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243&sort_by=popularity.desc&air_date.lte=${getDate().today}&air_date.gte=${getDate().today}" to "Airing Today Anime",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243&sort_by=popularity.desc&air_date.lte=${getDate().nextWeek}&air_date.gte=${getDate().today}" to "On The Air Anime",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243" to "Anime",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_keywords=210024|222243" to "Anime Movies",
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val adultQuery =
            if (settingsForProvider.enableAdult) "" else "&without_keywords=190370|13059|226161|195669"
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home = app.get("${request.data}$adultQuery&page=$page")
            .parsedSafe<Results>()?.results?.mapNotNull { media ->
                media.toSearchResponse(type)
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
            this.score= Score.from10(voteAverage)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.get("$tmdbAPI/search/multi?api_key=$apiKey&language=en-US&query=$query&page=1&include_adult=${settingsForProvider.enableAdult}")
            .parsedSafe<Results>()?.results?.mapNotNull { media ->
                media.toSearchResponse()
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = getType(data.type)
        val append = "alternative_titles,credits,external_ids,keywords,videos,recommendations"
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&append_to_response=$append"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&append_to_response=$append"
        }
        val res = app.get(resUrl).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val orgTitle = res.originalTitle ?: res.originalName ?: return null
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val rating = res.vote_average.toString().toFloat()
        val genres = res.genres?.mapNotNull { it.name }

        val isCartoon = genres?.contains("Animation") ?: false
        val isAnime = isCartoon && (res.original_language == "zh" || res.original_language == "ja")
        val isAsian = !isAnime && (res.original_language == "zh" || res.original_language == "ko")
        val isBollywood = res.production_countries?.any { it.name == "India" } ?: false

        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name } }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: cast.originalName
                    ?: return@mapNotNull null, getImageUrl(cast.profilePath)
                ), roleString = cast.character
            )
        } ?: return null
        val recommendations =
            res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer = res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }

        return if (type == TvType.TvSeries) {
            val lastSeason = res.last_episode_to_air?.season_number
            val episodes = res.seasons?.mapNotNull { season ->
                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        newEpisode(
                            data = LinkData(
                                data.id,
                                res.external_ids?.imdb_id,
                                res.external_ids?.tvdb_id,
                                data.type,
                                eps.seasonNumber,
                                eps.episodeNumber,
                                title = title,
                                year = season.airDate?.split("-")?.first()?.toIntOrNull(),
                                orgTitle = orgTitle,
                                isAnime = isAnime,
                                airedYear = year,
                                lastSeason = lastSeason,
                                epsTitle = eps.name,
                                jpTitle = res.alternative_titles?.results?.find { it.iso_3166_1 == "JP" }?.title,
                                date = season.airDate,
                                airedDate = res.releaseDate
                                    ?: res.firstAirDate,
                                isAsian = isAsian,
                                isBollywood = isBollywood,
                                isCartoon = isCartoon
                            ).toJson()
                        ) {
                            this.name =
                                eps.name + if (isUpcoming(eps.airDate)) " • [UPCOMING]" else ""
                            this.season = eps.seasonNumber
                            this.episode = eps.episodeNumber
                            this.posterUrl = getImageUrl(eps.stillPath)
                            this.score = Score.from10(eps.voteAverage)
                            this.description = eps.overview
                        }.apply {
                            this.addDate(eps.airDate)
                        }
                    }
            }?.flatten() ?: listOf()
            newTvSeriesLoadResponse(
                title,
                url,
                if (isAnime) TvType.Anime else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = Score.from10(rating)
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                this.contentRating = fetchContentRating(data.id, "US")
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    data.id,
                    res.external_ids?.imdb_id,
                    res.external_ids?.tvdb_id,
                    data.type,
                    title = title,
                    year = year,
                    orgTitle = orgTitle,
                    isAnime = isAnime,
                    jpTitle = res.alternative_titles?.results?.find { it.iso_3166_1 == "JP" }?.title,
                    airedDate = res.releaseDate
                        ?: res.firstAirDate,
                    isAsian = isAsian,
                    isBollywood = isBollywood
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.comingSoon = isUpcoming(releaseDate)
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                this.actors = actors
                this.contentRating = fetchContentRating(data.id, "US")
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = parseJson<LinkData>(data)

        runAllAsync(
            {
                invokeIdlix(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeVidsrccc(
                    res.id,
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeVidsrc(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeWatchsomuch(
                    res.imdbId,
                    res.season,
                    res.episode,
                    subtitleCallback
                )
            },
            {
                invokeVixsrc(res.id, res.season, res.episode, callback)
            },
            {
                invokeVidlink(res.id, res.season, res.episode, callback)
            },
            {
                invokeVidfast(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeMapple(res.id, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeWyzie(res.id, res.season, res.episode, subtitleCallback)
            },
            {
                invokeVidsrccx(res.id, res.season, res.episode, callback)
            },
            {
                invokeSuperembed(
                    res.id,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeVidrock(
                    res.id,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeEleven(
                    res.id,
                    res.season,
                    res.episode,
                    callback
                )
            }
        )

        return true
    }

    data class LinkData(
        val id: Int? = null,
        val imdbId: String? = null,
        val tvdbId: Int? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val aniId: String? = null,
        val animeId: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val orgTitle: String? = null,
        val isAnime: Boolean = false,
        val airedYear: Int? = null,
        val lastSeason: Int? = null,
        val epsTitle: String? = null,
        val jpTitle: String? = null,
        val date: String? = null,
        val airedDate: String? = null,
        val isAsian: Boolean = false,
        val isBollywood: Boolean = false,
        val isCartoon: Boolean = false,
    )

    data class Data(
        val id: Int? = null,
        val type: String? = null,
        val aniId: String? = null,
        val malId: Int? = null,
    )

    data class Results(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class Genres(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class Keywords(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class KeywordResults(
        @JsonProperty("results") val results: ArrayList<Keywords>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: ArrayList<Keywords>? = arrayListOf(),
    )

    data class Seasons(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("air_date") val airDate: String? = null,
    )

    data class Cast(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Episodes(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class MediaDetailEpisodes(
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )

    data class Trailers(
        @JsonProperty("key") val key: String? = null,
    )

    data class ResultsTrailer(
        @JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
    )

    data class AltTitles(
        @JsonProperty("iso_3166_1") val iso_3166_1: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class ResultsAltTitles(
        @JsonProperty("results") val results: ArrayList<AltTitles>? = arrayListOf(),
    )

    data class ExternalIds(
        @JsonProperty("imdb_id") val imdb_id: String? = null,
        @JsonProperty("tvdb_id") val tvdb_id: Int? = null,
    )

    data class Credits(
        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class ResultsRecommendations(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class LastEpisodeToAir(
        @JsonProperty("episode_number") val episode_number: Int? = null,
        @JsonProperty("season_number") val season_number: Int? = null,
    )

    data class ProductionCountries(
        @JsonProperty("name") val name: String? = null,
    )

    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val vote_average: Any? = null,
        @JsonProperty("original_language") val original_language: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: KeywordResults? = null,
        @JsonProperty("last_episode_to_air") val last_episode_to_air: LastEpisodeToAir? = null,
        @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("external_ids") val external_ids: ExternalIds? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
        @JsonProperty("alternative_titles") val alternative_titles: ResultsAltTitles? = null,
        @JsonProperty("production_countries") val production_countries: ArrayList<ProductionCountries>? = arrayListOf(),
    )

}
