package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
            val document = app.get(url, timeout = 30).document
            
            println("Response code: 200")
            println("Page title: ${document.title()}")
            
            val home = mutableListOf<SearchResponse>()
            
            // Try multiple possible selectors for the site structure
            val selectors = listOf(
                "div.post-content a[href*=.html]",
                "div.entry-content a[href*=.html]", 
                ".post-body a[href*=.html]",
                "article a[href*=.html]",
                ".content a[href*=.html]",
                "a[href*='/20']" // Year-based URLs
            )
            
            for (selector in selectors) {
                val links = document.select(selector)
                println("Trying selector '$selector': found ${links.size} links")
                
                links.forEach { link ->
                    val href = link.attr("href")
                    val text = link.text().trim()
                    
                    // Filter for actual show links
                    if (text.isNotEmpty() && 
                        href.contains(mainUrl) && 
                        !href.contains("/page/") &&
                        !href.contains("#") &&
                        text.length > 5) {
                        
                        // Try to find associated image
                        var posterUrl: String? = null
                        val parent = link.parent()
                        if (parent != null) {
                            val img = parent.selectFirst("img")
                            if (img != null) {
                                posterUrl = img.attr("src")
                                    .ifEmpty { img.attr("data-src") }
                                    .ifEmpty { img.attr("data-lazy-src") }
                                if (!posterUrl.isNullOrEmpty() && !posterUrl.startsWith("http")) {
                                    posterUrl = fixUrl(posterUrl)
                                }
                            }
                        }
                        
                        home.add(
                            newTvSeriesSearchResponse(text, fixUrl(href), TvType.TvSeries) {
                                this.posterUrl = posterUrl
                            }
                        )
                    }
                }
                
                if (home.isNotEmpty()) break // Stop if we found content
            }
            
            val uniqueHome = home.distinctBy { it.url }
            println("=== TOKUZL: Found ${uniqueHome.size} unique items ===")
            
            val hasNext = document.select("a.next, .nav-next, a[rel=next]").isNotEmpty()
            return newHomePageResponse(request.name, uniqueHome, hasNext = hasNext)
            
        } catch (e: Exception) {
            println("=== TOKUZL ERROR in getMainPage: ${e.message} ===")
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("=== TOKUZL: Searching for '$query' ===")
        
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        
        try {
            val document = app.get(url, timeout = 30).document
            
            return document.select("a[href*=.html]").mapNotNull { link ->
                val href = link.attr("href")
                val title = link.text().trim()
                
                if (title.isNotEmpty() && href.contains(mainUrl) && title.length > 5) {
                    val posterElement = link.parent()?.selectFirst("img")
                    val posterUrl = posterElement?.attr("src") 
                        ?: posterElement?.attr("data-src")
                    
                    newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = posterUrl?.let { fixUrl(it) }
                    }
                } else null
            }.distinctBy { it.url }
        } catch (e: Exception) {
            println("=== TOKUZL ERROR in search: ${e.message} ===")
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        println("=== TOKUZL: Loading '$url' ===")
        
        try {
            val document = app.get(url, timeout = 30).document
            
            val title = document.selectFirst("h1, .entry-title, .post-title")?.text()?.trim() 
                ?: "Unknown Title"
            
            val poster = document.selectFirst("img[src*=.jpg], img[src*=.png]")?.attr("src")
            
            val plot = document.selectFirst(".entry-content, .post-content")?.text()?.trim()
                ?: "No description available."
            
            val episodes = mutableListOf<Episode>()
            
            // Find video iframes or links in the page
            document.select("iframe[src], a[href*='.mp4'], a[href*='.m3u8']").forEachIndexed { index, element ->
                val href = element.attr("src").ifEmpty { element.attr("href") }
                if (href.isNotEmpty()) {
                    episodes.add(
                        newEpisode(fixUrl(href)) {
                            this.name = "Episode ${index + 1}"
                            this.episode = index + 1
                        }
                    )
                }
            }
            
            // If no videos, use the page itself
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) {
                    this.name = "Watch"
                    this.episode = 1
                })
            }
            
            println("=== TOKUZL: Loaded '$title' with ${episodes.size} episodes ===")
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
            }
            
        } catch (e: Exception) {
            println("=== TOKUZL ERROR in load: ${e.message} ===")
            return newTvSeriesLoadResponse("Error", url, TvType.TvSeries, emptyList())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("=== TOKUZL: loadLinks for '$data' ===")
        
        try {
            // Try direct video URL first
            if (data.contains(".mp4") || data.contains(".m3u8")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Direct",
                        url = data,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value
                    )
                )
                return true
            }
            
            // Fetch page and extract sources
            val document = app.get(data).document
            
            // Extract from iframes
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                loadExtractor(fixUrl(src), subtitleCallback, callback)
            }
            
            // Extract from video tags
            document.select("video source[src]").forEach { source ->
                val src = source.attr("src")
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Video",
                        url = fixUrl(src),
                        referer = data,
                        quality = Qualities.Unknown.value
                    )
                )
            }
            
            return true
        } catch (e: Exception) {
            println("=== TOKUZL ERROR: ${e.message} ===")
            return false
        }
    }
}