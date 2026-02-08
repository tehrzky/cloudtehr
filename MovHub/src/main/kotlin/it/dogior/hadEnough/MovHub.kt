package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/**
 * CloudStream source for https://movhub.to
 */
class MovHub(
    override var lang: String = "en",
    private val showLogo: Boolean = true
) : MainAPI() {

    // -----------------------------------------------------------------
    // 1️⃣ Basic metadata
    // -----------------------------------------------------------------
    override var mainUrl = Companion.mainUrl          // https://movhub.to/
    override var name = Companion.name                // MovHub
    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    override val hasMainPage = true

    companion object {
        const val mainUrl = "https://movhub.to/"
        const val name = "MovHub"
        private const val TAG = "MovHub"

        // Inertia‑style headers – required for the first request
        private var inertiaVersion = ""
        private val headers = mutableMapOf<String, String>(
            "X-Inertia" to "true",
            "X-Requested-With" to "XMLHttpRequest",
            "Cookie" to ""          // will be filled by setupHeaders()
        )
    }

    // -----------------------------------------------------------------
    // 2️⃣ TMDB helpers (optional logos)
    // -----------------------------------------------------------------
    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val tmdbApiKey = BuildConfig.TMDB_API
    private val tmdbHeaders = mapOf(
        "Authorization" to "Bearer $tmdbApiKey",
        "Accept" to "application/json"
    )

    // -----------------------------------------------------------------
    // 3️⃣ Home‑page sections (you can add/remove categories here)
    // -----------------------------------------------------------------
    private val sections = mainPageOf(
        "$mainUrl/browse/top10"      to "Top 10 of Today",
        "$mainUrl/browse/trending"   to "Trending Titles",
        "$mainUrl/browse/latest"     to "Recently Added",
        "$mainUrl/browse/upcoming"   to "Upcoming...",
        "$mainUrl/browse/genre?g=Animation"       to "Animation",
        "$mainUrl/browse/genre?g=Adventure"       to "Adventure",
        "$mainUrl/browse/genre?g=Action"          to "Action",
        "$mainUrl/browse/genre?g=Comedy"          to "Comedy",
        "$mainUrl/browse/genre?g=Crime"           to "Crime",
        "$mainUrl/browse/genre?g=Documentary"    to "Documentary",
        "$mainUrl/browse/genre?g=Drama"          to "Drama",
        "$mainUrl/browse/genre?g=Family"         to "Family",
        "$mainUrl/browse/genre?g=Science%20Fiction" to "Science Fiction",
        "$mainUrl/browse/genre?g=Fantasy"        to "Fantasy",
        "$mainUrl/browse/genre?g=Horror"         to "Horror",
        "$mainUrl/browse/genre?g=Reality"        to "Reality",
        "$mainUrl/browse/genre?g=Romance"        to "Romance",
        "$mainUrl/browse/genre?g=Thriller"       to "Thriller"
    )
    override val mainPage = sections

    // -----------------------------------------------------------------
    // 4️⃣ Helper – obtain the cookie and X‑Inertia‑Version header
    // -----------------------------------------------------------------
    private suspend fun setupHeaders() {
        val resp = app.get("$mainUrl/archive")
        // Build the cookie string
        headers["Cookie"] = resp.cookies.entries.joinToString("; ") {
            "${it.key}=${it.value}"
        }

        // Extract the inertia version from the page
        val page = resp.document
        val inertiaPageObject = page.select("#app").attr("data-page")
        inertiaVersion = inertiaPageObject
            .substringAfter("\"version\":\"")
            .substringBefore("\"")
        headers["X-Inertia-Version"] = inertiaVersion
    }

    // -----------------------------------------------------------------
    // 5️⃣ Convert raw MovHub titles into CloudStream SearchResponse objects
    // -----------------------------------------------------------------
    private fun buildSearchResponses(titles: List<MovHubTitle>): List<SearchResponse> {
        val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
        return titles.filter { it.type == "movie" || it.type == "tv" }.map { t ->
            val url = "$mainUrl/titles/${t.id}-${t.slug}"
            if (t.type == "tv") {
                newTvSeriesSearchResponse(t.name, url) {
                    posterUrl = "https://cdn.$domain/images/${t.getPoster()}"
                }
            } else {
                newMovieSearchResponse(t.name, url) {
                    posterUrl = "https://cdn.$domain/images/${t.getPoster()}"
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // 6️⃣ Home‑page pagination
    // -----------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Build the API endpoint – everything lives under /api
        var url = mainUrl.substringBeforeLast("/") + "/api" +
                request.data.substringAfter(mainUrl)

        val params = mutableMapOf("lang" to lang)

        // Genre pages have a "?g=…" query; strip it from the URL and add it as a param
        val section = request.data.substringAfterLast("/")
        if (section !in listOf("trending", "latest", "top10")) {
            val genre = url.substringAfterLast('=')
            url = url.substringBefore('?')
            params["g"] = genre
        }

        // Pagination – MovHub uses an offset (60 items per page)
        if (page > 0) {
            params["offset"] = ((page - 1) * 60).toString()
        }

        val resp = app.get(url, params = params)
        val json = resp.parseAs<MovHubSection>()
        val list = buildSearchResponses(json.titles)

        // Continue while we receive a full page (60 items)
        val hasNext = list.size == 60
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = false
            ),
            hasNext
        )
    }

    // -----------------------------------------------------------------
    // 7️⃣ Simple (non‑paginated) search
    // -----------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        if (headers["Cookie"].isNullOrEmpty()) setupHeaders()
        val resp = app.get(
            "${mainUrl}search",
            params = mapOf("q" to query),
            headers = headers
        )
        
        val result = resp.parseAs<MovHubInertiaResponse>()
        return buildSearchResponses(result.props.titles ?: emptyList())
    }

    // -----------------------------------------------------------------
    // 8️⃣ Paginated search (used by CloudStream when the user scrolls)
    // -----------------------------------------------------------------
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (headers["Cookie"].isNullOrEmpty()) setupHeaders()
        val url = "${mainUrl}api/search"
        val params = mutableMapOf(
            "q" to query,
            "lang" to lang
        )
        if (page > 0) {
            params["offset"] = ((page - 1) * 60).toString()
        }

        val resp = app.get(url, params = params, headers = headers)
        val result = resp.parseAs<MovHubSearchResponse>()

        val hasNext = (page < 3) || (page < result.lastPage)
        return newSearchResponseList(buildSearchResponses(result.data), hasNext = hasNext)
    }

    // -----------------------------------------------------------------
    // 9️⃣ Helper – fetch a poster (TMDB fallback if possible)
    // -----------------------------------------------------------------
    private suspend fun fetchPoster(title: MovHubTitleProp): String? {
        return if (title.tmdbId != null) {
            // Grab the TMDB poster page and read the highest‑resolution srcset entry
            val tmdbUrl = "https://www.themoviedb.org/${title.type}/${title.tmdbId}"
            val doc = app.get(tmdbUrl).document
            val srcSet = doc.select("img.poster.w-full").attr("srcset")
            srcSet.split(", ").last()
        } else {
            val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
            "https://cdn.$domain/images/background_placeholder.jpg"
        }
    }

    // -----------------------------------------------------------------
    // 🔟 Fetch TMDB logo (optional, shown next to the title)
    // -----------------------------------------------------------------
    private suspend fun fetchTmdbLogoUrl(
        type: TvType,
        tmdbId: Int?,
        appLangCode: String?
    ): String? {
        if (tmdbId == null) return null
        return try {
            val lang = appLangCode?.substringBefore("-")?.lowercase()
            val endpoint = if (type == TvType.Movie) {
                "$tmdbAPI/movie/$tmdbId/images"
            } else {
                "$tmdbAPI/tv/$tmdbId/images"
            }
            val resp = app.get(endpoint, headers = tmdbHeaders)
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.text)
            val logos = json.optJSONArray("logos") ?: return null
            if (logos.length() == 0) return null

            // Helper to build a full logo URL
            fun logoUrl(i: Int) = "https://image.tmdb.org/t/p/w500${logos.getJSONObject(i).optString("file_path")}"

            // Prefer a non‑SVG logo in the app language, then EN, then any SVG
            fun find(preferred: String?): String? {
                var svgFallback: String? = null
                for (i in 0 until logos.length()) {
                    val obj = logos.getJSONObject(i)
                    if (obj.optString("iso_639_1") == preferred) {
                        val path = obj.optString("file_path")
                        return if (path.endsWith(".svg", true)) {
                            svgFallback ?: logoUrl(i)
                        } else {
                            logoUrl(i)
                        }
                    }
                }
                return svgFallback
            }

            find(lang) ?: find("en") ?: run {
                // Last resort: first non‑SVG, otherwise first entry
                for (i in 0 until logos.length()) {
                    if (!logos.getJSONObject(i).optString("file_path").endsWith(".svg", true))
                        return@run logoUrl(i)
                }
                logoUrl(0)
            }
        } catch (e: Exception) {
            Log.d(TAG, "TMDB logo error: ${e.message}")
            null
        }
    }

    // -----------------------------------------------------------------
    // 11️⃣ Load a title (movie or series) – builds the detailed page
    // -----------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        if (headers["Cookie"].isNullOrEmpty()) setupHeaders()
        val resp = app.get(url, headers = headers)
        val json = resp.parseAs<MovHubInertiaResponse>().props
        val title = json.title!!

        val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
        val poster = fetchPoster(title)
        val background = "https://cdn.$domain/images/background_placeholder.jpg"
        val year = title.releaseDate?.substringBefore('-')?.toIntOrNull()
        val genres = title.genres.map { it.name }
        val trailers = title.trailers?.mapNotNull { it.getYoutubeUrl() }

        // Optional TMDB logo
        val logoUrl = if (showLogo && title.tmdbId != null) {
            val type = if (title.type == "tv") TvType.TvSeries else TvType.Movie
            fetchTmdbLogoUrl(type, title.tmdbId, lang)
        } else null

        // -----------------------------------------------------------------
        // TV SERIES
        // -----------------------------------------------------------------
        if (title.type == "tv") {
            val episodes = getEpisodes(json)

            return newTvSeriesLoadResponse(
                title.name,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                backgroundPosterUrl = background
                logoUrl?.let { this.logoUrl = it }
                tags = genres
                this.year = year
                plot = title.plot
                title.age?.let { contentRating = "$it+" }
                recommendations = json.sliders?.firstOrNull()?.titles?.let { buildSearchResponses(it) }
                title.imdbId?.let { imdbid = it }
                title.tmdbId?.let { tmdbid = it.toString() }
                title.mainActors?.map { it.name }?.let { actors = it }
                title.score?.let { rating = it }
                trailers?.takeIf { it.isNotEmpty() }?.let { trailer = it }
            }
        }

        // -----------------------------------------------------------------
        // MOVIE
        // -----------------------------------------------------------------
        val loadData = LoadData(
            url = "$mainUrl/iframe/${title.id}&canPlayFHD=1",
            type = "movie",
            tmdbId = title.tmdbId
        )
        return newMovieLoadResponse(
            title.name,
            url,
            TvType.Movie,
            dataUrl = loadData.toJsonString()
        ) {
            posterUrl = poster
            backgroundPosterUrl = background
            logoUrl?.let { this.logoUrl = it }
            tags = genres
            this.year = year
            plot = title.plot
            title.age?.let { contentRating = "$it+" }
            recommendations = json.sliders?.firstOrNull()?.titles?.let { buildSearchResponses(it) }
            title.mainActors?.map { it.name }?.let { actors = it }
            title.score?.let { rating = it }
            title.imdbId?.let { imdbid = it }
            title.tmdbId?.let { tmdbid = it.toString() }
            title.runtime?.let { duration = it }
            trailers?.takeIf { it.isNotEmpty() }?.let { trailer = it }
        }
    }

    // -----------------------------------------------------------------
    // 12️⃣ Build the episode list for a TV series
    // -----------------------------------------------------------------
    private suspend fun getEpisodes(props: Props): List<Episode> {
        val episodeList = mutableListOf<Episode>()
        val title = props.title ?: return emptyList()

        title.seasons?.forEach { season ->
            // If the season is already loaded we can reuse its episodes
            val rawEpisodes = if (season.id == props.loadedSeason?.id) {
                props.loadedSeason?.episodes ?: emptyList()
            } else {
                // Need to request the season page
                if (inertiaVersion.isEmpty()) setupHeaders()
                val seasonUrl = "$mainUrl/titles/${title.id}-${title.slug}/season-${season.number}"
                val resp = app.get(seasonUrl, headers = headers)
                val response = resp.parseAs<MovHubInertiaResponse>()
                response.props.loadedSeason?.episodes ?: emptyList()
            }

            rawEpisodes.forEach { episode ->
                episodeList.add(
                    Episode(
                        data = "$mainUrl/iframe/${episode.id}",
                        name = "Episode ${episode.number}",
                        season = season.number,
                        episode = episode.number,
                        title = episode.name,
                        posterUrl = episode.poster?.let { posterId ->
                            val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
                            "https://cdn.$domain/images/$posterId"
                        },
                        description = episode.description,
                        date = episode.released,
                        duration = episode.runtime?.toString()
                    )
                )
            }
        }

        return episodeList
    }

    // -----------------------------------------------------------------
    // 13️⃣ Load video links
    // -----------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val loadData = data.parseAs<LoadData>()
            val url = loadData.url
            
            // Extract token from URL
            val httpUrl = url.toHttpUrl()
            val token = httpUrl.queryParameter("token") ?: 
                        httpUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: 
                        return false
            
            // Construct rapidshare URL
            val rapidUrl = "https://movhub.to/media/$token"
            
            // Use RapidShare extractor
            RapidShareExtractor().getUrl(
                url = rapidUrl,
                referer = mainUrl,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Helper function to convert object to JSON string
    private fun LoadData.toJsonString(): String {
        return "{\"url\":\"$url\",\"type\":\"$type\",\"tmdbId\":${tmdbId ?: "null"}}"
    }
}

// -----------------------------------------------------------------
// Data classes for MovHub API responses
// -----------------------------------------------------------------
private data class MovHubTitle(
    val id: Int,
    val slug: String,
    val type: String,
    val name: String,
    val score: Int? = null,
    val poster: String? = null
) {
    fun getPoster(): String {
        return poster ?: "poster_placeholder.png"
    }
}

private data class MovHubTitleProp(
    val id: Int,
    val slug: String,
    val type: String,
    val name: String,
    val plot: String? = null,
    val score: Float? = null,
    val age: Int? = null,
    val releaseDate: String? = null,
    val runtime: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val genres: List<Genre> = emptyList(),
    val mainActors: List<Actor> = emptyList(),
    val trailers: List<Trailer>? = null,
    val seasons: List<Season>? = null
)

private data class Genre(val name: String)
private data class Actor(val name: String)
private data class Trailer(val url: String) {
    fun getYoutubeUrl(): String? {
        return if (url.contains("youtube.com") || url.contains("youtu.be")) {
            url
        } else {
            null
        }
    }
}

private data class Season(
    val id: Int,
    val number: Int,
    val episodes: List<MovHubEpisode>? = null
)

private data class MovHubEpisode(
    val id: Int,
    val number: Int,
    val name: String? = null,
    val description: String? = null,
    val released: String? = null,
    val runtime: Int? = null,
    val poster: String? = null
)

private data class MovHubSection(
    val titles: List<MovHubTitle>
)

private data class MovHubInertiaResponse(
    val component: String,
    val props: Props,
    val url: String,
    val version: String
)

private data class Props(
    val title: MovHubTitleProp? = null,
    val titles: List<MovHubTitle>? = null,
    val sliders: List<Slider>? = null,
    val loadedSeason: LoadedSeason? = null
)

private data class Slider(
    val name: String,
    val titles: List<MovHubTitle>? = null
)

private data class LoadedSeason(
    val id: Int,
    val number: Int,
    val episodes: List<MovHubEpisode>? = null
)

private data class MovHubSearchResponse(
    val data: List<MovHubTitle>,
    val lastPage: Int
)

private data class LoadData(
    val url: String,
    val type: String,
    val tmdbId: Int? = null
)
