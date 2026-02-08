package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.HttpUrl.Companion.toHttpUrl

class MovHub : MainAPI() {
    override var mainUrl = "https://movhub.to"
    override var name = "MovHub"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    // Simple main page sections
    override val mainPage = mainPageOf(
        "$mainUrl/browse/trending" to "Trending",
        "$mainUrl/browse/latest" to "Latest",
        "$mainUrl/browse/top10" to "Top 10"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val document = app.get(url).document
        
        val items = document.select("div.grid-item").mapNotNull { element ->
            val titleElement = element.selectFirst("a.title")
            val title = titleElement?.text() ?: return@mapNotNull null
            val href = titleElement.attr("href")
            val poster = element.selectFirst("img")?.attr("src") ?: ""
            
            // Determine if it's a movie or series by URL pattern
            val isSeries = href.contains("/tv/") || href.contains("/series/")
            
            if (isSeries) {
                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search", params = mapOf("q" to query)).document
        
        return document.select("div.search-item").mapNotNull { element ->
            val titleElement = element.selectFirst("a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val href = titleElement.attr("href")
            val poster = element.selectFirst("img")?.attr("src") ?: ""
            val typeText = element.selectFirst(".type")?.text()?.lowercase() ?: ""
            
            val isSeries = typeText.contains("series") || typeText.contains("tv")
            
            if (isSeries) {
                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.title")?.text() ?: "Unknown"
        val plot = document.selectFirst(".plot")?.text() ?: ""
        val poster = document.selectFirst("img.poster")?.attr("src") ?: ""
        val year = document.selectFirst(".year")?.text()?.toIntOrNull()
        
        // Check if it's a series by looking for season/episode info
        val hasSeasons = document.select(".season-item").isNotEmpty()
        
        if (hasSeasons) {
            // TV Series
            val episodes = getEpisodes(document)
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            // Movie
            val iframeUrl = document.selectFirst("iframe")?.attr("src")
            val loadData = LoadData(
                url = iframeUrl ?: url,
                type = "movie"
            )
            
            return newMovieLoadResponse(title, url, TvType.Movie, loadData.toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        document.select(".season-item").forEachIndexed { seasonIndex, season ->
            val seasonNumber = seasonIndex + 1
            
            season.select(".episode-item").forEach { episode ->
                val episodeNumber = episode.selectFirst(".episode-num")?.text()?.toIntOrNull() ?: 0
                val episodeTitle = episode.selectFirst(".episode-title")?.text() ?: "Episode $episodeNumber"
                val episodeUrl = episode.selectFirst("a")?.attr("href") ?: ""
                val episodePlot = episode.selectFirst(".episode-plot")?.text() ?: ""
                
                // FIXED: Using newEpisode method instead of deprecated constructor
                episodes.add(
                    newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.description = episodePlot
                    }
                )
            }
        }
        
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        val url = loadData.url
        
        if (url.contains("movhub.to/media/")) {
            // Use RapidShare extractor
            val rapidUrl = url
            return loadExtractor(rapidUrl, "$mainUrl/", subtitleCallback, callback)
        }
        
        return false
    }
}

private data class LoadData(
    val url: String,
    val type: String
)

private fun LoadData.toJson(): String {
    return """{"url":"$url","type":"$type"}"""
}
