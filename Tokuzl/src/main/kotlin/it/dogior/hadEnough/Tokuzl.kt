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
        println("=== TOKUZL DEBUG: getMainPage called ===")
        println("Request name: ${request.name}")
        println("Request data: ${request.data}")
        println("Page: $page")
        
        val url = if (page > 1) "${request.data}/page/$page" else request.data
        println("Fetching URL: $url")
        
        try {
            // Try with a longer timeout and different user agent
            val response = app.get(
                url, 
                timeout = 90,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "DNT" to "1",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-User" to "?1",
                    "Cache-Control" to "max-age=0"
                )
            )
            
            val document = response.document
            println("Successfully fetched page. Response code: ${response.code}")
            println("Page title: ${document.title()}")
            
            // Debug: Print first 1000 chars of HTML
            val html = document.html()
            println("HTML length: ${html.length}")
            if (html.length > 1000) {
                println("First 1000 chars of HTML:\n${html.substring(0, 1000)}")
            }
            
            // Try different selectors to find content
            val selectors = listOf(
                "article",
                ".post",
                ".entry",
                "h2 a",
                "h3 a",
                "h4 a",
                ".entry-title a",
                ".post-title a",
                "a[href*=.html]"
            )
            
            val home = mutableListOf<SearchResponse>()
            
            for (selector in selectors) {
                val elements = document.select(selector)
                println("Selector '$selector' found ${elements.size} elements")
                
                if (elements.isNotEmpty() && home.isEmpty()) {
                    println("Using selector: $selector")
                    
                    elements.take(20).forEachIndexed { index, element ->
                        val link = if (element.tagName() == "a") element else element.selectFirst("a")
                        if (link != null) {
                            val title = link.text().trim()
                            val href = link.attr("href")
                            
                            if (title.isNotEmpty() && href.contains(".html")) {
                                println("Found item $index: '$title' -> $href")
                                
                                // Try to find poster
                                var posterUrl: String? = null
                                val parent = link.parent() ?: element.parent()
                                val img = parent.selectFirst("img")
                                if (img != null) {
                                    posterUrl = img.attr("src") ?: img.attr("data-src")
                                    println("Found poster: $posterUrl")
                                }
                                
                                home.add(
                                    newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                                        this.posterUrl = posterUrl
                                    }
                                )
                            }
                        }
                    }
                    
                    if (home.isNotEmpty()) {
                        println("Successfully found ${home.size} items using selector: $selector")
                        break
                    }
                }
            }
            
            if (home.isEmpty()) {
                println("WARNING: No items found with any selector!")
                // Add a dummy item for testing
                home.add(
                    newTvSeriesSearchResponse("Test Item", "$mainUrl/test.html", TvType.TvSeries) {
                        this.posterUrl = null
                    }
                )
            }
            
            val hasNext = document.select("a.next, .next-page, .pagination-next").isNotEmpty()
            println("Has next page: $hasNext")
            
            println("=== TOKUZL DEBUG: Returning ${home.size} items ===")
            
            return newHomePageResponse(request.name, home, hasNext = hasNext)
            
        } catch (e: Exception) {
            println("=== TOKUZL DEBUG: ERROR in getMainPage ===")
            println("Error type: ${e.javaClass.simpleName}")
            println("Error message: ${e.message}")
            e.printStackTrace()
            
            // Return empty home page with error info
            return newHomePageResponse(
                request.name,
                listOf(
                    newTvSeriesSearchResponse("ERROR: ${e.message ?: "Unknown error"}", mainUrl, TvType.TvSeries)
                ),
                hasNext = false
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("=== TOKUZL DEBUG: search called ===")
        println("Query: $query")
        
        val encodedQuery = query.replace(" ", "+")
        val url = "$mainUrl/?s=$encodedQuery"
        println("Search URL: $url")
        
        try {
            val document = app.get(url, timeout = 60).document
            println("Search results found")
            
            return document.select("article").mapNotNull { article ->
                val link = article.selectFirst("h3 a") ?: return@mapNotNull null
                val title = link.text().trim()
                if (title.isEmpty()) return@mapNotNull null
                
                val href = fixUrl(link.attr("href"))
                val posterElement = article.selectFirst("img")
                val posterUrl = posterElement?.attr("src") ?: posterElement?.attr("data-src")
                
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            println("=== TOKUZL DEBUG: ERROR in search ===")
            println("Error: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        println("=== TOKUZL DEBUG: load called ===")
        println("URL: $url")
        
        try {
            val document = app.get(url, timeout = 60).document
            
            val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
            println("Title: $title")
            
            val poster = document.selectFirst("img[src*=.jpg], img[src*=.png], img[src*=.jpeg]")?.attr("src")
            println("Poster: $poster")
            
            val episodes = listOf(
                newEpisode(url) {
                    this.name = "Episode 1"
                    this.episode = 1
                    this.season = 1
                }
            )
            
            println("=== TOKUZL DEBUG: Load successful ===")
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = "Description will be added soon."
            }
            
        } catch (e: Exception) {
            println("=== TOKUZL DEBUG: ERROR in load ===")
            println("Error: ${e.message}")
            
            // Return a basic response even if there's an error
            return newTvSeriesLoadResponse("Error Loading", url, TvType.TvSeries, emptyList()) {
                this.posterUrl = null
                this.plot = "Error loading content: ${e.message}"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("=== TOKUZL DEBUG: loadLinks called ===")
        println("Data URL: $data")
        return true
    }
}