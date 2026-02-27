package com.cinemax21

import com.fasterxml.jackson.annotation.JsonProperty

data class AesData(
    @JsonProperty("m") val m: String,
)

data class ResponseHash(
    @JsonProperty("embed_url") val embed_url: String,
    @JsonProperty("key") val key: String,
)

data class AniIds(var id: Int? = null, var idMal: Int? = null)

data class TmdbDate(
    val today: String,
    val nextWeek: String,
)

data class VixsrcSource(
    val name: String,
    val url: String,
    val referer: String,
)

data class VidrockSource(
    @JsonProperty("resolution") val resolution: Int? = null,
    @JsonProperty("url") val url: String? = null,
)

data class VidrockSubtitle(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("file") val file: String? = null,
)

data class VidsrccxSource(
    @JsonProperty("secureUrl") val secureUrl: String? = null,
)

data class WyzieSubtitle(
    @JsonProperty("display") val display: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class VidFastSources(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("tracks") val tracks: ArrayList<Tracks>? = null,
) {
    data class Tracks(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )
}

data class VidFastServers(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("data") val data: String? = null,
) {
    data class Stream(
        @JsonProperty("playlist") val playlist: String? = null,
    )
}

data class VidlinkSources(
    @JsonProperty("stream") val stream: Stream? = null,
) {
    data class Stream(
        @JsonProperty("playlist") val playlist: String? = null,
    )
}

data class MappleSubtitle(
    @JsonProperty("display") val display: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class MappleSources(
    @JsonProperty("data") val data: Data? = null,
) {
    data class Data(
        @JsonProperty("stream_url") val stream_url: String? = null,
    )
}

data class PrimeboxSources(
    @JsonProperty("streams") val streams: HashMap<String, String>? = null,
    @JsonProperty("subtitles") val subtitles: ArrayList<Subtitles>? = null,
) {
    data class Subtitles(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )
}

data class RageSources(
    @JsonProperty("url") val url: String? = null,
)

data class VidsrcccServer(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("hash") val hash: String? = null,
)

data class VidsrcccResponse(
    @JsonProperty("data") val data: ArrayList<VidsrcccServer>? = arrayListOf(),
)

data class VidsrcccResult(
    @JsonProperty("data") val data: VidsrcccSources? = null,
)

data class VidsrcccSources(
    @JsonProperty("subtitles") val subtitles: ArrayList<VidsrcccSubtitles>? = arrayListOf(),
    @JsonProperty("source") val source: String? = null,
)

data class VidsrcccSubtitles(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("file") val file: String? = null,
)

data class UpcloudSources(
    @JsonProperty("file") val file: String? = null,
)

data class UpcloudResult(
    @JsonProperty("sources") val sources: ArrayList<UpcloudSources>? = arrayListOf(),
)

data class AniMedia(
    @JsonProperty("id") var id: Int? = null,
    @JsonProperty("idMal") var idMal: Int? = null
)

data class AniPage(@JsonProperty("media") var media: java.util.ArrayList<AniMedia> = arrayListOf())

data class AniData(@JsonProperty("Page") var Page: AniPage? = AniPage())

data class AniSearch(@JsonProperty("data") var data: AniData? = AniData())

data class GpressSources(
    @JsonProperty("src") val src: String,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: Int? = null,
    @JsonProperty("max") val max: String,
)

data class KisskhEpisodes(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("number") val number: Int?,
)

data class WatchsomuchTorrents(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("movieId") val movieId: Int? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
)

data class WatchsomuchMovies(
    @JsonProperty("torrents") val torrents: ArrayList<WatchsomuchTorrents>? = arrayListOf(),
)

data class WatchsomuchSubtitles(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("label") val label: String? = null,
)

data class WatchsomuchResponses(
    @JsonProperty("movie") val movie: WatchsomuchMovies? = null,
)

data class WatchsomuchSubResponses(
    @JsonProperty("subtitles") val subtitles: ArrayList<WatchsomuchSubtitles>? = arrayListOf(),
)

data class IndexMedia(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("driveId") val driveId: String? = null,
    @JsonProperty("mimeType") val mimeType: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("modifiedTime") val modifiedTime: String? = null,
)

data class IndexData(
    @JsonProperty("files") val files: ArrayList<IndexMedia>? = arrayListOf(),
)

data class IndexSearch(
    @JsonProperty("data") val data: IndexData? = null,
)

data class JikanExternal(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class JikanData(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("external") val external: ArrayList<JikanExternal>? = arrayListOf(),
)

data class VidsrctoResult(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class AnilistExternalLinks(
    @JsonProperty("id") var id: Int? = null,
    @JsonProperty("site") var site: String? = null,
    @JsonProperty("url") var url: String? = null,
    @JsonProperty("type") var type: String? = null,
)

data class AnilistMedia(@JsonProperty("externalLinks") var externalLinks: ArrayList<AnilistExternalLinks> = arrayListOf())

data class AnilistData(@JsonProperty("Media") var Media: AnilistMedia? = AnilistMedia())

data class MALSyncSites(
    @JsonProperty("Zoro") val zoro: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @JsonProperty("9anime") val nineAnime: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
)

data class MALSyncResponses(
    @JsonProperty("Sites") val sites: MALSyncSites? = null,
)

data class HianimeResponses(
    @JsonProperty("html") val html: String? = null,
    @JsonProperty("link") val link: String? = null,
)

data class MalSyncRes(
    @JsonProperty("Sites") val Sites: Map<String, Map<String, Map<String, String>>>? = null,
)

data class GokuData(
    @JsonProperty("link") val link: String? = null,
)

data class GokuServer(
    @JsonProperty("data") val data: GokuData? = GokuData(),
)

data class AllMovielandEpisodeFolder(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("file") val file: String? = null,
)

data class AllMovielandSeasonFolder(
    @JsonProperty("episode") val episode: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("folder") val folder: ArrayList<AllMovielandEpisodeFolder>? = arrayListOf(),
)

data class AllMovielandServer(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("folder") val folder: ArrayList<AllMovielandSeasonFolder>? = arrayListOf(),
)

data class AllMovielandPlaylist(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("href") val href: String? = null,
)

data class DumpMedia(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("domainType") val domainType: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("releaseTime") val releaseTime: String? = null,
)

data class DumpQuickSearchData(
    @JsonProperty("searchResults") val searchResults: ArrayList<DumpMedia>? = arrayListOf(),
)

data class SubtitlingList(
    @JsonProperty("languageAbbr") val languageAbbr: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("subtitlingUrl") val subtitlingUrl: String? = null,
)

data class DefinitionList(
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("description") val description: String? = null,
)

data class EpisodeVo(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("seriesNo") val seriesNo: Int? = null,
    @JsonProperty("definitionList") val definitionList: ArrayList<DefinitionList>? = arrayListOf(),
    @JsonProperty("subtitlingList") val subtitlingList: ArrayList<SubtitlingList>? = arrayListOf(),
)

data class DumpMediaDetail(
    @JsonProperty("episodeVo") val episodeVo: ArrayList<EpisodeVo>? = arrayListOf(),
)

data class EMovieServer(
    @JsonProperty("value") val value: String? = null,
)

data class EMovieSources(
    @JsonProperty("file") val file: String? = null,
)

data class EMovieTraks(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
)

data class ShowflixResultsMovies(
    @JsonProperty("movieName") val movieName: String? = null,
    @JsonProperty("streamwish") val streamwish: String? = null,
    @JsonProperty("filelions") val filelions: String? = null,
    @JsonProperty("streamruby") val streamruby: String? = null,
)

data class ShowflixResultsSeries(
    @JsonProperty("seriesName") val seriesName: String? = null,
    @JsonProperty("streamwish") val streamwish: HashMap<String, List<String>>? = hashMapOf(),
    @JsonProperty("filelions") val filelions: HashMap<String, List<String>>? = hashMapOf(),
    @JsonProperty("streamruby") val streamruby: HashMap<String, List<String>>? = hashMapOf(),
)

data class ShowflixSearchMovies(
    @JsonProperty("results") val resultsMovies: ArrayList<ShowflixResultsMovies>? = arrayListOf(),
)

data class ShowflixSearchSeries(
    @JsonProperty("results") val resultsSeries: ArrayList<ShowflixResultsSeries>? = arrayListOf(),
)

data class SFMoviesSeriess(
    @JsonProperty("title") var title: String? = null,
    @JsonProperty("svideos") var svideos: String? = null,
)

data class SFMoviesAttributes(
    @JsonProperty("title") var title: String? = null,
    @JsonProperty("video") var video: String? = null,
    @JsonProperty("releaseDate") var releaseDate: String? = null,
    @JsonProperty("seriess") var seriess: ArrayList<ArrayList<SFMoviesSeriess>>? = arrayListOf(),
    @JsonProperty("contentId") var contentId: String? = null,
)

data class SFMoviesData(
    @JsonProperty("id") var id: Int? = null,
    @JsonProperty("attributes") var attributes: SFMoviesAttributes? = SFMoviesAttributes()
)

data class SFMoviesSearch(
    @JsonProperty("data") var data: ArrayList<SFMoviesData>? = arrayListOf(),
)

data class RidoContentable(
    @JsonProperty("imdbId") var imdbId: String? = null,
    @JsonProperty("tmdbId") var tmdbId: Int? = null,
)

data class RidoItems(
    @JsonProperty("slug") var slug: String? = null,
    @JsonProperty("contentable") var contentable: RidoContentable? = null,
)

data class RidoData(
    @JsonProperty("url") var url: String? = null,
    @JsonProperty("items") var items: ArrayList<RidoItems>? = arrayListOf(),
)

data class RidoResponses(
    @JsonProperty("data") var data: ArrayList<RidoData>? = arrayListOf(),
)

data class RidoSearch(
    @JsonProperty("data") var data: RidoData? = null,
)

data class SmashySources(
    @JsonProperty("sourceUrls") var sourceUrls: ArrayList<String>? = arrayListOf(),
    @JsonProperty("subtitleUrls") var subtitleUrls: String? = null,
)

data class AoneroomResponse(
    @JsonProperty("data") val data: Data? = null,
) {
    data class Data(
        @JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
        @JsonProperty("list") val list: ArrayList<List>? = arrayListOf(),
    ) {
        data class Items(
            @JsonProperty("subjectId") val subjectId: String? = null,
            @JsonProperty("title") val title: String? = null,
            @JsonProperty("releaseDate") val releaseDate: String? = null,
        )

        data class List(
            @JsonProperty("resourceLink") val resourceLink: String? = null,
            @JsonProperty("extCaptions") val extCaptions: ArrayList<ExtCaptions>? = arrayListOf(),
            @JsonProperty("se") val se: Int? = null,
            @JsonProperty("ep") val ep: Int? = null,
            @JsonProperty("resolution") val resolution: Int? = null,
        ) {
            data class ExtCaptions(
                @JsonProperty("lanName") val lanName: String? = null,
                @JsonProperty("url") val url: String? = null,
            )
        }
    }
}

data class CinemaTvResponse(
    @JsonProperty("streams") val streams: HashMap<String, String>? = null,
    @JsonProperty("subtitles") val subtitles: ArrayList<Subtitles>? = arrayListOf(),
) {
    data class Subtitles(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("file") val file: Any? = null,
    )
}

data class NepuSearch(
    @JsonProperty("data") val data: ArrayList<Data>? = arrayListOf(),
) {
    data class Data(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null,
    )
}

data class CinemaOsSecretKeyRequest(
    val tmdbId: String,
    val seasonId: String,
    val episodeId: String
)

data class CinemaOSReponse(
    val data: CinemaOSReponseData,
    val encrypted: Boolean,
)

data class CinemaOSReponseData(
    val encrypted: String,
    val cin: String,
    val mao: String,
    val salt: String,
)

data class Player4uLinkData(
    val name: String,
    val url: String,
)

data class DramaSearchResponse(
    @JsonProperty("data") val data: ArrayList<SearchItem>? = arrayListOf(),
    @JsonProperty("success") val success: Boolean? = null
)

data class SearchItem(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("year") val year: String? = null 
)

data class RiveStreamSource(
    val data: List<String>
)

data class RiveStreamResponse(
    val data: RiveStreamData,
)

data class RiveStreamData(
    val sources: List<RiveStreamSourceData>,
)

data class RiveStreamSourceData(
    val quality: String,
    val url: String,
    val source: String,
    val format: String,
)

data class MovieboxResponse(
    @JsonProperty("data") val data: MovieboxData? = null,
)

data class MovieboxData(
    @JsonProperty("items") val items: ArrayList<MovieboxItem>? = arrayListOf(),
    @JsonProperty("streams") val streams: ArrayList<MovieboxStreamItem>? = arrayListOf(),
    @JsonProperty("captions") val captions: ArrayList<MovieboxCaptionItem>? = arrayListOf(),
)

data class MovieboxItem(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("detailPath") val detailPath: String? = null,
)

data class MovieboxStreamItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("resolutions") val resolutions: String? = null,
)

data class MovieboxCaptionItem(
    @JsonProperty("lanName") val lanName: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class Moviebox2SearchResponse(
    @JsonProperty("data") val data: Moviebox2SearchData? = null
)

data class Moviebox2SearchData(
    @JsonProperty("results") val results: ArrayList<Moviebox2SearchResult>? = arrayListOf()
)

data class Moviebox2SearchResult(
    @JsonProperty("subjects") val subjects: ArrayList<Moviebox2Subject>? = arrayListOf()
)

data class Moviebox2Subject(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("subjectType") val subjectType: Int? = null
)

data class Moviebox2PlayResponse(
    @JsonProperty("data") val data: Moviebox2PlayData? = null
)

data class Moviebox2PlayData(
    @JsonProperty("streams") val streams: ArrayList<Moviebox2Stream>? = arrayListOf()
)

data class Moviebox2Stream(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("resolutions") val resolutions: String? = null,
    @JsonProperty("signCookie") val signCookie: String? = null
)

data class Moviebox2SubtitleResponse(
    @JsonProperty("data") val data: Moviebox2SubtitleData? = null
)

data class Moviebox2SubtitleData(
    @JsonProperty("extCaptions") val extCaptions: ArrayList<Moviebox2Caption>? = arrayListOf()
)

data class Moviebox2Caption(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("lanName") val lanName: String? = null,
    @JsonProperty("lan") val lan: String? = null
)
