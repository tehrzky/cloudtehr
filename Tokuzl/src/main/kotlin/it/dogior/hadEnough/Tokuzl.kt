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
    
    // Note: In Cloudstream 3, the property might be named differently or not exist
    // Remove the extractorApis property if it's causing issues
    // The extractor is registered in the plugin class instead

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
        
        val home = document.select("h3 a[href*=.html]").mapNotNull { link ->
            val title = link.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            
            val href = fixUrl(link.attr("href"))
            val posterElement = link.parent()?.parent()?.selectFirst("img")
            val posterUrl = posterElement?.attr("src") ?: posterElement?.attr("data-src")
            
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        
        return newHomePageResponse(
            request.name, 
            home, 
            hasNext = document.selectFirst("a:contains(Next)") != null
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
            
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("img[src*=wp-content]")?.attr("src")
        val plot = document.select("div.entry-content p").firstOrNull()?.text() ?: ""
        
        val yearMatch = Regex("""(\d{4})""").find(title)
        val year = yearMatch?.value?.toIntOrNull()
        
        // Get episodes
        val episodes = mutableListOf<Episode>()
        
        document.select("ul li a").forEach { link ->
            val href = link.attr("href")
            val linkText = link.text().trim().lowercase()
            
            if (href.contains(".html") && 
                (linkText.contains("episode") || 
                 linkText.contains("ep") || 
                 linkText.matches(Regex("""ep\s*\d+""")) ||
                 linkText.matches(Regex("""episode\s*\d+""")))) {
                
                val epNum = try {
                    Regex("""\d+""").find(linkText)?.value?.toIntOrNull() ?: 1
                } catch (e: Exception) { 1 }
                
                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                    }
                )
            }
        }
        
        // If no episodes found, use the page itself
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Watch"
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("Tokuzl: loadLinks called for URL: $data")
        
        val document = try {
            app.get(data, timeout = 60).document
        } catch (e: Exception) {
            println("Tokuzl: Failed to fetch page: ${e.message}")
            return false
        }
        
        var foundLinks = false
        
        // Look for iframes and process them
        val iframes = document.select("iframe")
        println("Tokuzl: Found ${iframes.size} iframes")
        
        iframes.forEachIndexed { index, iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotEmpty()) {
                val iframeUrl = when {
                    src.startsWith("http") -> src
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "$mainUrl$src"
                    else -> "$mainUrl/$src"
                }
                
                println("Tokuzl: Processing iframe $index: $iframeUrl")
                
                // Extract from iframe
                extractFromIframe(iframeUrl, data, subtitleCallback, callback)
                foundLinks = true
            }
        }
        
        // Also try to extract directly from the page
        extractFromIframe(data, data, subtitleCallback, callback)
        
        return foundLinks
    }
    
    private suspend fun extractFromIframe(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Create extractor instance and call it
            val extractor = UniversalExtractor()
            extractor.getUrl(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            println("Tokuzl: Extractor failed for $url: ${e.message}")
        }
    }
}