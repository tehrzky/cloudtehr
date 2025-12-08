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
            
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
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
            
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
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
        
        // Extract year from title or content
        var year: Int? = null
        val yearMatch = Regex("""(19\d{2}|20\d{2})""").find(title + " " + plot)
        if (yearMatch != null) {
            year = yearMatch.value.toIntOrNull()
        }
        
        // Try to extract series info from article content
        var seasons = 1
        document.select("p, div").forEach { element ->
            val text = element.text().lowercase()
            if (text.contains("season") && text.contains("total")) {
                val seasonMatch = Regex("""season\s*(\d+)""").find(text)
                if (seasonMatch != null) {
                    seasons = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                }
            }
        }
        
        // Extract episodes - MOST IMPORTANT PART
        val episodes = mutableListOf<Episode>()
        
        // Method 1: Look for episode links in lists
        document.select("ul li a").forEach { link ->
            val href = link.attr("href")
            val linkText = link.text().trim()
            
            if (href.contains("tokuzl.net") && href.endsWith(".html") && 
                (linkText.contains("Episode", ignoreCase = true) || 
                 linkText.contains("EP", ignoreCase = true) ||
                 Regex("""Episode\s+\d+""", RegexOption.IGNORE_CASE).matches(linkText) ||
                 Regex("""EP\s+\d+""", RegexOption.IGNORE_CASE).matches(linkText))) {
                
                val epNum = try {
                    Regex("""\d+""").find(linkText)?.value?.toIntOrNull() ?: 1
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
        
        // Method 2: Look for episode tables
        document.select("table tr").forEach { row ->
            val cells = row.select("td")
            if (cells.size >= 2) {
                val link = cells.last().selectFirst("a")
                if (link != null) {
                    val href = link.attr("href")
                    val linkText = link.text().trim()
                    
                    if (href.contains(".html") && linkText.contains("Episode", ignoreCase = true)) {
                        val epNum = Regex("""\d+""").find(linkText)?.value?.toIntOrNull() ?: 1
                        
                        episodes.add(
                            newEpisode(fixUrl(href)) {
                                this.name = "Episode $epNum"
                                this.episode = epNum
                                this.season = 1
                            }
                        )
                    }
                }
            }
        }
        
        // Method 3: Look for direct watch links
        if (episodes.isEmpty()) {
            document.select("a[href*='tokuzl.net']").forEach { link ->
                val href = link.attr("href")
                val linkText = link.text().trim()
                
                if (href.contains("/?p=") || href.contains("/watch") || 
                    href.contains("/play") || linkText.contains("Watch", ignoreCase = true)) {
                    
                    // Try to extract episode number from URL or text
                    var epNum = 1
                    val urlEpMatch = Regex("""ep[=\-]?(\d+)""", RegexOption.IGNORE_CASE).find(href)
                    if (urlEpMatch != null) {
                        epNum = urlEpMatch.groupValues[1].toIntOrNull() ?: 1
                    } else {
                        val textEpMatch = Regex("""\d+""").find(linkText)
                        if (textEpMatch != null) {
                            epNum = textEpMatch.value.toIntOrNull() ?: 1
                        }
                    }
                    
                    episodes.add(
                        newEpisode(fixUrl(href)) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                            this.season = 1
                        }
                    )
                }
            }
        }
        
        // Method 4: Look for pagination or episode lists
        if (episodes.isEmpty()) {
            // Check if the page itself is an episode
            val pageTitle = title.lowercase()
            if (pageTitle.contains("episode") || pageTitle.contains("ep")) {
                val epNum = Regex("""\d+""").find(pageTitle)?.value?.toIntOrNull() ?: 1
                episodes.add(
                    newEpisode(url) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                        this.season = 1
                    }
                )
            } else {
                // If it's a series page, create at least one episode
                episodes.add(
                    newEpisode(url) {
                        this.name = "Episode 1"
                        this.episode = 1
                        this.season = 1
                    }
                )
            }
        }
        
        // Sort episodes by episode number
        episodes.sortBy { it.episode }
        
        println("Tokuzl: Loaded series '$title' with ${episodes.size} episodes")
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.recommendations = true
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("Tokuzl: loadLinks called for URL: $data")
        
        try {
            val document = app.get(data, timeout = 60).document
            val html = document.html()
            
            var foundLinks = false
            
            // Look for iframes first
            val iframes = document.select("iframe")
            println("Tokuzl: Found ${iframes.size} iframes")
            
            iframes.forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotEmpty()) {
                    val iframeUrl = when {
                        src.startsWith("http") -> src
                        src.startsWith("//") -> "https:$src"
                        src.startsWith("/") -> "$mainUrl$src"
                        else -> src
                    }
                    
                    println("Tokuzl: Found iframe: $iframeUrl")
                    
                    // Create extractor link for iframe
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "iframe",
                            url = iframeUrl
                        ) {
                            this.referer = data
                        }
                    )
                    foundLinks = true
                }
            }
            
            // Also look for direct video URLs in the page
            val videoPatterns = listOf(
                Regex("""src=["']([^"']+\.(?:m3u8|mp4|mkv))["']""", RegexOption.IGNORE_CASE),
                Regex("""data-src=["']([^"']+\.(?:m3u8|mp4|mkv))["']""", RegexOption.IGNORE_CASE)
            )
            
            videoPatterns.forEach { pattern ->
                pattern.findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1].trim()
                    if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                        println("Tokuzl: Found direct video URL: $videoUrl")
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Video",
                                url = videoUrl,
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
            
        } catch (e: Exception) {
            println("Tokuzl: Error in loadLinks: ${e.message}")
            return false
        }
    }
}