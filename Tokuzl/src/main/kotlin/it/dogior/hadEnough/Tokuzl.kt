package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Tokuzl : MainAPI() {
    override var mainUrl = "https://tokuzl.net"
    override var name = "Tokuzl"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest Updates",
        "$mainUrl/kamen-rider" to "Kamen Rider",
        "$mainUrl/super-sentai" to "Super Sentai",
        "$mainUrl/ultraman" to "Ultraman",
        "$mainUrl/power-ranger" to "Power Rangers",
        "$mainUrl/garo" to "GARO",
        "$mainUrl/metal-heroes" to "Metal Heroes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}/page/$page" else request.data
        val document = app.get(url).document
        
        val home = document.select("article").mapNotNull { article ->
            val link = article.selectFirst("h3 a") ?: return@mapNotNull null
            val title = link.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            
            val href = fixUrl(link.attr("href"))
            val posterElement = article.selectFirst("img")
            val posterUrl = posterElement?.attr("src") ?: posterElement?.attr("data-src")
            
            // Determine if it's a series or movie
            val isMovie = title.contains("Movie", ignoreCase = true) || 
                         title.contains("Film", ignoreCase = true)
            
            val type = if (isMovie) TvType.Movie else TvType.TvSeries
            
            if (isMovie) {
                newMovieSearchResponse(title, href, type) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, href, type) {
                    this.posterUrl = posterUrl
                }
            }
        }
        
        return newHomePageResponse(
            request.name, 
            home, 
            hasNext = document.select("a.next.page-numbers").isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "+")
        val document = app.get("$mainUrl/?s=$encodedQuery").document
        
        return document.select("article").mapNotNull { article ->
            val link = article.selectFirst("h3 a") ?: return@mapNotNull null
            val title = link.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            
            val href = fixUrl(link.attr("href"))
            val posterElement = article.selectFirst("img")
            val posterUrl = posterElement?.attr("src") ?: posterElement?.attr("data-src")
            
            val isMovie = title.contains("Movie", ignoreCase = true) || 
                         title.contains("Film", ignoreCase = true)
            
            val type = if (isMovie) TvType.Movie else TvType.TvSeries
            
            if (isMovie) {
                newMovieSearchResponse(title, href, type) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, href, type) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() 
            ?: document.selectFirst("h1")?.text()?.trim() 
            ?: ""
        
        // Extract poster image
        val poster = document.selectFirst("img.size-full, img.wp-post-image, img.aligncenter")?.attr("src")
            ?: document.selectFirst("img[src*=.jpg], img[src*=.png], img[src*=.jpeg]")?.attr("src")
        
        // Extract plot/description
        val plotElement = document.selectFirst("div.entry-content") ?: document.selectFirst("article")
        val plot = plotElement?.select("p")?.take(3)?.joinToString("\n\n") { it.text().trim() } 
            ?: ""
        
        // Determine if it's a movie or series
        val isMovie = title.contains("Movie", ignoreCase = true) || 
                     url.contains("/movie/") || 
                     plot.contains("movie", ignoreCase = true)
        
        if (isMovie) {
            // Handle as movie
            val yearMatch = Regex("""(19\d{2}|20\d{2})""").find(title + " " + plot)
            val year = yearMatch?.value?.toIntOrNull()
            
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            // Handle as TV series
            val episodes = extractEpisodes(document, url)
            val yearMatch = Regex("""(19\d{2}|20\d{2})""").find(title + " " + plot)
            val year = yearMatch?.value?.toIntOrNull()
            
            println("Tokuzl: Loaded series '$title' with ${episodes.size} episodes")
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }
    }
    
    private fun extractEpisodes(document: Element, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Method 1: Look for episode links
        document.select("a[href*='.html']").forEach { link ->
            val href = link.attr("href")
            val text = link.text().trim()
            
            if (href.contains("tokuzl.net") && 
                (text.contains("Episode", ignoreCase = true) || 
                 text.contains("EP", ignoreCase = true) ||
                 Regex("""Episode\s+\d+""", RegexOption.IGNORE_CASE).matches(text) ||
                 Regex("""EP\s+\d+""", RegexOption.IGNORE_CASE).matches(text))) {
                
                val epNum = try {
                    Regex("""\d+""").find(text)?.value?.toIntOrNull() ?: 1
                } catch (e: Exception) { 1 }
                
                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                        this.season = 1
                    }
                )
            }
        }
        
        // Method 2: If no episodes found, create at least one
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(baseUrl) {
                    this.name = "Watch"
                    this.episode = 1
                    this.season = 1
                }
            )
        }
        
        return episodes.sortedBy { it.episode }
    }
}