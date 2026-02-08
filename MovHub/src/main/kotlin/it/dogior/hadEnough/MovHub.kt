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
        
        // Debug: print the page title to verify we're getting content
        println("MovHub DEBUG - Page title: ${document.title()}")
        println("MovHub DEBUG - URL: $url")
        
        val items = mutableListOf<SearchResponse>()
        
        // Try to find all movie/show cards - be very broad with selectors
        val cardSelectors = listOf(
            "article",
            "div[class*='card']",
            "div[class*='item']",
            "div[class*='movie']",
            "div[class*='show']",
            "div[class*='poster']",
            "a[href*='/titles/']",
            "a[href*='/movie/']",
            "a[href*='/tv/']",
            ".grid > div",
            ".grid > a"
        )
        
        for (selector in cardSelectors) {
            val elements = document.select(selector)
            println("MovHub DEBUG - Selector '$selector' found ${elements.size} elements")
            
            if (elements.size > 0) {
                elements.forEach { element ->
                    val item = extractSearchItem(element)
                    if (item != null) {
                        items.add(item)
                    }
                }
                
                if (items.isNotEmpty()) {
                    println("MovHub DEBUG - Successfully extracted ${items.size} items with selector: $selector")
                    break // Found working selector, stop trying others
                }
            }
        }
        
        // Last resort: try finding all links that look like movie/show links
        if (items.isEmpty()) {
            println("MovHub DEBUG - Trying fallback link extraction")
            document.select("a[href]").forEach { link ->
                val href = link.attr("href")
                if ((href.contains("/titles/") || href.contains("/movie/") || href.contains("/tv/")) && 
                    !href.contains("/genre") && !href.contains("/search")) {
                    
                    val title = link.attr("title").ifEmpty { 
                        link.selectFirst("img")?.attr("alt")?.ifEmpty { link.text().trim() } ?: link.text().trim()
                    }
                    
                    if (title.length > 2) { // Avoid single character titles
                        val poster = link.selectFirst("img")?.let { img ->
                            img.attr("src").ifEmpty { 
                                img.attr("data-src").ifEmpty { 
                                    img.attr("data-lazy-src") 
                                }
                            }
                        } ?: ""
                        
                        val fullUrl = fixUrl(href)
                        val isSeries = href.contains("/tv/") || href.contains("/series/")
                        
                        val searchItem = if (isSeries) {
                            newTvSeriesSearchResponse(title, fullUrl) {
                                this.posterUrl = fixPosterUrl(poster)
                            }
                        } else {
                            newMovieSearchResponse(title, fullUrl) {
                                this.posterUrl = fixPosterUrl(poster)
                            }
                        }
                        items.add(searchItem)
                    }
                }
            }
        }
        
        println("MovHub DEBUG - Total items found: ${items.size}")
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = false)
    }

    private fun extractSearchItem(element: Element): SearchResponse? {
        return try {
            // Try to find the link
            val link = element.selectFirst("a[href*='/titles/'], a[href*='/movie/'], a[href*='/tv/']") 
                ?: if (element.tagName() == "a") element else return null
            
            val href = link.attr("href")
            if (href.isEmpty()) return null
            
            // Get title from multiple possible sources
            val title = link.attr("title").ifEmpty {
                link.selectFirst("img")?.attr("alt")?.ifEmpty {
                    link.selectFirst("h2, h3, h4, .title, [class*='title']")?.text()?.ifEmpty {
                        link.text().trim()
                    }
                } ?: link.text().trim()
            } ?: ""
            
            if (title.isEmpty() || title.length < 2) return null
            
            // Find poster image with multiple fallbacks
            val poster = element.selectFirst("img")?.let { img ->
                img.attr("src").ifEmpty { 
                    img.attr("data-src").ifEmpty { 
                        img.attr("data-lazy-src") 
                    }
                }
            } ?: ""
            
            // Determine type
            val isSeries = href.contains("/tv/") || 
                          href.contains("/series/") ||
                          element.selectFirst("[class*='series'], [class*='tv']") != null
            
            val fullUrl = fixUrl(href)
            
            if (isSeries) {
                newTvSeriesSearchResponse(title, fullUrl) {
                    this.posterUrl = fixPosterUrl(poster)
                }
            } else {
                newMovieSearchResponse(title, fullUrl) {
                    this.posterUrl = fixPosterUrl(poster)
                }
            }
        } catch (e: Exception) {
            println("MovHub DEBUG - Error extracting item: ${e.message}")
            null
        }
    }

    private fun fixUrl(url: String): String {
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

    private fun fixPosterUrl(url: String): String {
        if (url.isEmpty()) return ""
        return fixUrl(url)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        println("MovHub DEBUG - Search URL: $searchUrl")
        val document = app.get(searchUrl).document
        
        val results = mutableListOf<SearchResponse>()
        
        // Try the same extraction logic as main page
        document.select("a[href]").forEach { link ->
            val href = link.attr("href")
            if ((href.contains("/titles/") || href.contains("/movie/") || href.contains("/tv/")) && 
                !href.contains("/genre")) {
                
                val title = link.attr("title").ifEmpty { 
                    link.selectFirst("img")?.attr("alt") ?: link.text().trim()
                }
                
                if (title.isNotEmpty() && title.contains(query, true)) {
                    val poster = link.selectFirst("img")?.attr("src") ?: ""
                    val isSeries = href.contains("/tv/") || href.contains("/series/")
                    val fullUrl = fixUrl(href)
                    
                    if (isSeries) {
                        results.add(newTvSeriesSearchResponse(title, fullUrl) {
                            this.posterUrl = fixPosterUrl(poster)
                        })
                    } else {
                        results.add(newMovieSearchResponse(title, fullUrl) {
                            this.posterUrl = fixPosterUrl(poster)
                        })
                    }
                }
            }
        }
        
        println("MovHub DEBUG - Search results: ${results.size}")
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title with multiple selectors
        val title = document.selectFirst("h1, h2.title, .movie-title, [class*='title']")?.text() 
            ?: document.title()
        
        // Extract plot
        val plot = document.selectFirst(".plot, .description, .synopsis, .overview, p.description")?.text() ?: ""
        
        // Extract poster
        val poster = document.selectFirst("img.poster, .poster img, img[class*='poster'], meta[property='og:image']")
            ?.attr("content") 
            ?: document.selectFirst("img.poster, .poster img, img[class*='poster']")?.attr("src") 
            ?: ""
        
        // Extract year
        val yearText = document.selectFirst(".year, .release-date, [class*='year'], [class*='date']")?.text() ?: ""
        val year = yearText.filter { it.isDigit() }.take(4).toIntOrNull()
        
        // Check if it's a series
        val hasSeasons = document.select(".season, .episodes, [class*='season'], [class*='episode']").isNotEmpty()
        
        if (hasSeasons) {
            // TV Series
            val episodes = getEpisodes(document, url)
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixPosterUrl(poster)
                this.plot = plot
                this.year = year
            }
        } else {
            // Movie
            val iframeUrl = document.selectFirst("iframe[src]")?.attr("src") ?: url
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

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document, pageUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Try to find seasons
        val seasonElements = document.select(".season, [class*='season-'], [id*='season']")
        
        if (seasonElements.isNotEmpty()) {
            seasonElements.forEachIndexed { seasonIndex, season ->
                val seasonNumber = seasonIndex + 1
                
                season.select(".episode, .episode-item, [class*='episode']").forEach { episode ->
                    val episodeNumber = episode.selectFirst(".episode-num, .number")?.text()?.toIntOrNull() ?: 0
                    val episodeTitle = episode.selectFirst(".episode-title, .title")?.text() ?: "Episode $episodeNumber"
                    val episodeUrl = episode.selectFirst("a")?.attr("href") ?: ""
                    val episodePlot = episode.selectFirst(".plot, .description")?.text() ?: ""
                    
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
            // Fallback: create dummy episode
            episodes.add(
                newEpisode(pageUrl) {
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
        
        println("MovHub DEBUG - Loading links for: $url")
        
        if (url.contains("movhub.to") || url.contains("iframe") || url.contains("embed")) {
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
