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
        println("=== TOKUZL: getMainPage for ${request.name} page $page ===")
        
        val url = if (page > 1) "${request.data}/page/$page" else request.data
        println("Fetching: $url")
        
        try {
            val response = app.get(url, timeout = 30)
            val document = response.document
            
            // For debugging - print page info
            println("Response code: ${response.code}")
            println("Page title: ${document.title()}")
            
            val home = mutableListOf<SearchResponse>()
            
            // METHOD 1: Look for posts in articles
            document.select("article, .post, .item-post").forEach { article ->
                try {
                    // Get title and link
                    val titleElement = article.selectFirst("h2 a, h3 a, .entry-title a, .post-title a")
                    if (titleElement != null) {
                        val title = titleElement.text().trim()
                        val href = titleElement.attr("href")
                        
                        if (title.isNotEmpty() && href.contains(".html")) {
                            println("Found: '$title' -> $href")
                            
                            // Get image
                            var posterUrl: String? = null
                            val img = article.selectFirst("img")
                            if (img != null) {
                                posterUrl = img.attr("src").ifEmpty { img.attr("data-src") }
                                if (posterUrl != null && !posterUrl.startsWith("http")) {
                                    posterUrl = fixUrl(posterUrl)
                                }
                            }
                            
                            home.add(
                                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    println("Error parsing article: ${e.message}")
                }
            }
            
            // METHOD 2: If no articles found, try direct links
            if (home.isEmpty()) {
                document.select("a").forEach { link ->
                    val href = link.attr("href")
                    val text = link.text().trim()
                    
                    if (href.contains("/kamen-rider") || href.contains("/super-sentai") || 
                        href.contains("/ultraman") || href.contains("/power-ranger")) {
                        
                        if (text.isNotEmpty() && href.contains(".html")) {
                            println("Found direct link: '$text' -> $href")
                            
                            home.add(
                                newTvSeriesSearchResponse(text, fixUrl(href), TvType.TvSeries)
                            )
                        }
                    }
                }
            }
            
            // Remove duplicates
            val uniqueHome = home.distinctBy { it.url }
            
            println("=== TOKUZL: Found ${uniqueHome.size} unique items ===")
            
            val hasNext = document.select("a.next, .nav-next, .pagination-next").isNotEmpty()
            return newHomePageResponse(request.name, uniqueHome, hasNext = hasNext)
            
        } catch (e: Exception) {
            println("=== TOKUZL ERROR in getMainPage: ${e.message} ===")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("=== TOKUZL: Searching for '$query' ===")
        
        val encodedQuery = query.replace(" ", "+")
        val url = "$mainUrl/?s=$encodedQuery"
        
        try {
            val document = app.get(url, timeout = 30).document
            
            return document.select("article, .post").mapNotNull { article ->
                val link = article.selectFirst("h2 a, h3 a, .entry-title a")
                if (link != null) {
                    val title = link.text().trim()
                    val href = link.attr("href")
                    
                    if (title.isNotEmpty() && href.contains(".html")) {
                        val posterElement = article.selectFirst("img")
                        val posterUrl = posterElement?.attr("src") ?: posterElement?.attr("data-src")
                        
                        newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                            this.posterUrl = posterUrl?.let { fixUrl(it) }
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            println("=== TOKUZL ERROR in search: ${e.message} ===")
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        println("=== TOKUZL: Loading '$url' ===")
        
        try {
            val document = app.get(url, timeout = 30).document
            
            val title = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() 
                ?: "Unknown Title"
            
            val poster = document.selectFirst("img.size-full, img.wp-post-image, img.entry-image")?.attr("src")
                ?: document.selectFirst("img[src*=.jpg], img[src*=.png]")?.attr("src")
            
            val plot = document.selectFirst("div.entry-content, article .post-content")?.text()?.trim()
                ?: "No description available."
            
            // Create episodes list
            val episodes = mutableListOf<Episode>()
            
            // Look for episode links
            document.select("a[href*=.html]").forEach { link ->
                val href = link.attr("href")
                val text = link.text().trim().lowercase()
                
                if (text.contains("episode") || text.contains("ep") || 
                    text.matches(Regex("""ep\s*\d+""")) ||
                    text.matches(Regex("""episode\s*\d+"""))) {
                    
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
            
            // If no episodes found, use the main page
            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode(url) {
                        this.name = "Watch"
                        this.episode = 1
                        this.season = 1
                    }
                )
            }
            
            val sortedEpisodes = episodes.sortedBy { it.episode }
            
            println("=== TOKUZL: Loaded '$title' with ${sortedEpisodes.size} episodes ===")
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
            }
            
        } catch (e: Exception) {
            println("=== TOKUZL ERROR in load: ${e.message} ===")
            
            // Return basic response on error
            return newTvSeriesLoadResponse("Error Loading", url, TvType.TvSeries, emptyList()) {
                this.plot = "Error: ${e.message}"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("=== TOKUZL: loadLinks for '$data' ===")
        
        // Return true to indicate we can handle links
        // Cloudstream will use UniversalExtractor
        return true
    }
}