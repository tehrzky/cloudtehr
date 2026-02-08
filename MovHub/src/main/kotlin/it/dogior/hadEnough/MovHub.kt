package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/**
 * CloudStream source for https://movhub.to
 *
 * Keeps the original package name (it.dogior.hadEnough) so you can
 * drop the file straight into a repository that already contains the
 * Android UI code – the two parts will not clash because they are
 * compiled in different modules.
 */
class MovHub(
    /** MovHub only offers English, but we keep the parameter for compatibility */
    override var lang: String = "en",
    /** Show a TMDB logo next to the title if a TMDB id exists */
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
    // 5️⃣ Helper – obtain the cookie and X‑Inertia‑Version header
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
    // 6️⃣ Convert raw MovHub titles into CloudStream SearchResponse objects
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
    // 7️⃣ Home‑page pagination
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
        val json = parseJson<MovHubSection>(resp.body.string())
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
    // 8️⃣ Simple (non‑paginated) search
    // -----------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        if (headers["Cookie"].isNullOrEmpty()) setupHeaders()
        val resp = app.get(
            "${mainUrl}search",
            params = mapOf("q" to query),
            headers = headers
        ).body.string()

        val result = parseJson<MovHubInertiaResponse>(resp)
        return buildSearchResponses(result.props.titles ?: emptyList())
    }

    // -----------------------------------------------------------------
    // 9️⃣ Paginated search (used by CloudStream when the user scrolls)
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

        val resp = app.get(url, params = params, headers = headers).body.string()
        val result = parseJson<MovHubSearchResponse>(resp)

        val hasNext = (page < 3) || (page < result.lastPage)
        return newSearchResponseList(buildSearchResponses(result.data), hasNext = hasNext)
    }

    // -----------------------------------------------------------------
    // 10️⃣ Helper – fetch a poster (TMDB fallback if possible)
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
            "https://cdn.$domain/images/${title.getBackgroundImageId()}"
        }
    }

    // -----------------------------------------------------------------
    // 11️⃣ Fetch TMDB logo (optional, shown next to the title)
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
            val json = JSONObject(resp.body?.string() ?: return null)
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
    // 12️⃣ Load a title (movie or series) – builds the detailed page
    // -----------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        if (headers["Cookie"].isNullOrEmpty()) setupHeaders()
        val resp = app.get(url, headers = headers)
        val json = parseJson<MovHubInertiaResponse>(resp.body.string()).props
        val title = json.title!!

        val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
        val poster = fetchPoster(title)
        val background = "https://cdn.$domain/images/${title.getBackgroundImageId()}"
        val year = title.releaseDate?.substringBefore('-')?.toIntOrNull()
        val genres = title.genres.map { it.name.capitalize() }
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
                title.imdbId?.let { addImdbId(it) }
                title.tmdbId?.let { addTMDbId(it.toString()) }
                addActors(title.mainActors?.map { it.name })
                addScore(title.score)
                trailers?.takeIf { it.isNotEmpty() }?.let { addTrailer(it) }
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
            dataUrl = loadData.toJson()
        ) {
            posterUrl = poster
            backgroundPosterUrl = background
            logoUrl?.let { this.logoUrl = it }
            tags = genres
            this.year = year
            plot = title.plot
            title.age?.let { contentRating = "$it+" }
            recommendations = json.sliders?.firstOrNull()?.titles?.let { buildSearchResponses(it) }
            addActors(title.mainActors?.map { it.name })
            addScore(title.score)
            title.imdbId?.let { addImdbId(it) }
            title.tmdbId?.let { addTMDbId(it.toString()) }
            title.runtime?.let { duration = it }
            trailers?.takeIf { it.isNotEmpty() }?.let { addTrailer(it) }
        }
    }

    // -----------------------------------------------------------------
    // 13️⃣ Build the episode list for a TV series
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
                parseJson<MovHubInertiaResponse>(resp.body.string()).props.loaded
