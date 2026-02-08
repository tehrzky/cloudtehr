package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element

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
        
        // Try different selectors for MovHub website
        val items = mutableListOf<SearchResponse>()
        
        // Selector 1: Try common grid/item selectors
        val selectors = listOf(
            "div.poster", 
            "div.movie-item",
            "div.grid-item",
            "div.card",
            "a[href*=\"/titles/\"]",
            "div[class*=\"movie\"]",
            "div[class*=\"item\"]"
        )
        
        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                val item = extractSearchItem(element)
                if (item != null) {
                    items.add(item)
                }
            }
        }
        
        // If no items found, try extracting from all links
        if (items.isEmpty()) {
            document.select("a").forEach { link ->
                val href = link.attr("href")
                if (href.contains("/titles/") && href.contains("-")) {
                    val title = link.text().trim()
                    if (title.isNotEmpty()) {
                        val poster = link.selectFirst("img")?.attr("src") ?: ""
                        val isSeries = href.contains("/tv/") || href.contains("/series/")
                        
                        val searchItem = if (isSeries) {
                            newTvSeriesSearchResponse(title, href) {
                                this.posterUrl = fixPosterUrl(poster)
                            }
                        } else {
                            newMovieSearchResponse(title, href) {
                                this.posterUrl = fixPosterUrl(poster)
                            }
                        }
                        items.add(searchItem)
                    }
                }
            }
        }
        
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = false)
    }

    private fun extractSearchItem(element: Element): SearchResponse? {
        return try {
            // Try to find title link
            val link = element.selectFirst("a") ?: return null
            val href = link.attr("href")
            val title = link.attr("title") ?: link.text().trim()
            
            if (title.isEmpty() || !href.contains("/titles/")) {
                return null
            }
            
            // Find poster image
            val poster = element.selectFirst("img")?.attr("src") ?: 
                        element.selectFirst("img")?.attr("data-src") ?: ""
            
            // Determine type
            val isSeries = href.contains("/tv/") || 
                          href.contains("/series/") ||
                          element.selectFirst(".type")?.text()?.contains("series", true) == true
            
            if (isSeries) {
                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = fixPosterUrl(poster)
                }
            } else {
                newMovieSearchResponse(title, href) {
                    this.posterUrl = fixPosterUrl(poster)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fixPosterUrl(url: String): String {
        if (url.isEmpty()) return ""
        return if (url.startsWith("http")) {
            url
        } else if (url.startsWith("//")) {
            "https:$url"
        } else if (url.startsWith("/")) {
            "$mainUrl$url"
        } else {
            "$mainUrl/$url"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search", params = mapOf("q" to query)).document
        
        return document.select("a").mapNotNull { link ->
            val href = link.attr("href")
            if (href.contains("/titles/") && href.contains("-")) {
                val title = link.text().trim()
                if (title.isNotEmpty() && title.contains(query, true)) {
                    val poster = link.selectFirst("img")?.attr("src") ?: ""
                    val isSeries = href.contains("/tv/") || href.contains("/series/")
                    
                    if (isSeries) {
                        newTvSeriesSearchResponse(title, href) {
                            this.posterUrl = fixPosterUrl(poster)
                        }
                    } else {
                        newMovieSearchResponse(title, href) {
                            this.posterUrl = fixPosterUrl(poster)
                        }
                    }
                } else null
            } else null
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title
        val title = document.selectFirst("h1, h2, .title, .movie-title")?.text() ?: "Unknown"
        
        // Extract plot
        val plot = document.selectFirst(".plot, .description, .synopsis, p")?.text() ?: ""
        
        // Extract poster
        val poster = document.selectFirst("img.poster, img[src*=\"poster\"], .poster img")?.attr("src") ?: ""
        
        // Extract year
        val yearText = document.selectFirst(".year, .release-date, [class*=\"date\"]")?.text() ?: ""
        val year = yearText.filter { it.isDigit() }.take(4).toIntOrNull()
        
        // Check if it's a series by looking for season/episode info
        val hasSeasons = document.select(".season, .episodes, [class*=\"season\"]").isNotEmpty()
        
        if (hasSeasons) {
            // TV Series
            val episodes = getEpisodes(document)
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixPosterUrl(poster)
                this.plot = plot
                this.year = year
            }
        } else {
            // Movie
            // Find iframe for video source
            val iframeUrl = document.selectFirst("iframe, [src*=\"iframe\"], [src*=\"media\"]")?.attr("src") ?: url
            val loadData = LoadData(
                url = iframeUrl,
                type = "movie"
            )
            
            return newMovieLoadResponse(title, url, TvType.Movie, loadData.toJson()) {
                this.posterUrl = fixPosterUrl(poster)
                this.plot = plot
                this.year = year
            }
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Try to find seasons
        val seasonElements = document.select(".season, [class*=\"season-\"], [id*=\"season\"]")
        
        if (seasonElements.isNotEmpty()) {
            seasonElements.forEachIndexed { seasonIndex, season ->
                val seasonNumber = seasonIndex + 1
                
                season.select(".episode, .episode-item, [class*=\"episode\"]").forEach { episode ->
                    val episodeNumber = episode.selectFirst(".episode-num, .number")?.text()?.toIntOrNull() ?: 0
                    val episodeTitle = episode.selectFirst(".episode-title, .title")?.text() ?: "Episode $episodeNumber"
                    val episodeUrl = episode.selectFirst("a")?.attr("href") ?: ""
                    val episodePlot = episode.selectFirst(".plot, .description")?.text() ?: ""
                    
                    // FIXED: Use newEpisode method correctly
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
        } else {
            // Fallback: create dummy episodes
            // FIXED: Use newEpisode method correctly
            episodes.add(
                newEpisode(url) {
                    this.name = "Episode 1"
                    this.season = 1
                    this.episode = 1
                    this.description = "Watch this episode"
                }
            )
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
            return loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
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
