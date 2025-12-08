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
    
    // Register the extractor properly
    override val extractorApis = listOf(
        UniversalExtractor()
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
        
        // Get episodes - IMPORTANT: Look for the correct episode structure
        val episodes = mutableListOf<Episode>()
        
        // Method 1: Look for episode links in lists
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
        
        // Method 2: If no episodes found, check for direct watch links
        if (episodes.isEmpty()) {
            document.select("a").forEach { link ->
                val href = link.attr("href")
                if (href.contains("watch") || href.contains("play")) {
                    episodes.add(
                        newEpisode(fixUrl(href)) {
                            this.name = "Watch"
                            this.episode = 1
                        }
                    )
                }
            }
        }
        
        // Method 3: If still no episodes, use the page itself
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
        
        // Strategy 1: Direct UniversalExtractor call for the page
        println("Tokuzl: Trying direct extraction from page...")
        try {
            UniversalExtractor().getUrl(data, data, subtitleCallback, callback)
            println("Tokuzl: Direct extraction attempted")
            foundLinks = true
        } catch (e: Exception) {
            println("Tokuzl: Direct extraction failed: ${e.message}")
        }
        
        // Strategy 2: Look for iframes and process them
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
                
                try {
                    // Directly call UniversalExtractor for iframe
                    UniversalExtractor().getUrl(iframeUrl, data, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    println("Tokuzl: Failed to extract from iframe: ${e.message}")
                }
            }
        }
        
        // Strategy 3: Look for direct video URLs
        val videoPatterns = listOf(
            """src=["']([^"']+\.(?:m3u8|mp4|mkv|avi))["']""",
            """data-src=["']([^"']+\.(?:m3u8|mp4|mkv|avi))["']""",
            """file["']?\s*:\s*["']([^"']+\.(?:m3u8|mp4|mkv|avi))["']"""
        )
        
        val html = document.html()
        videoPatterns.forEach { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.isNotEmpty()) {
                    val fullUrl = when {
                        videoUrl.startsWith("http") -> videoUrl
                        videoUrl.startsWith("//") -> "https:$videoUrl"
                        videoUrl.startsWith("/") -> "$mainUrl$videoUrl"
                        else -> videoUrl
                    }
                    
                    println("Tokuzl: Found direct video URL: $fullUrl")
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Video",
                            url = fullUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                        }
                    )
                    foundLinks = true
                }
            }
        }
        
        return foundLinks
    }
}