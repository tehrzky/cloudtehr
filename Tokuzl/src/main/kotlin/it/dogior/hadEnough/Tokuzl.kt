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
            
            println("Response code: ${response.code}")
            println("Page title: ${document.title()}")
            
            val home = mutableListOf<SearchResponse>()
            
            // METHOD 1: Look for posts in articles
            document.select("article, .post, .item-post, .content-item").forEach { article ->
                try {
                    val titleElement = article.selectFirst("h2 a, h3 a, .entry-title a, .post-title a, a[title]")
                    if (titleElement != null) {
                        val title = titleElement.text().trim()
                        val href = titleElement.attr("href")
                        
                        if (title.isNotEmpty() && href.isNotEmpty()) {
                            println("Found: '$title' -> $href")
                            
                            var posterUrl: String? = null
                            val img = article.selectFirst("img")
                            if (img != null) {
                                posterUrl = img.attr("src").ifEmpty { 
                                    img.attr("data-src").ifEmpty { 
                                        img.attr("data-lazy-src") 
                                    } 
                                }
                                if (!posterUrl.isNullOrEmpty() && !posterUrl.startsWith("http")) {
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
            
            // METHOD 2: Fallback - look for content links
            if (home.isEmpty()) {
                document.select("div.post-content a, div.entry-content a").forEach { link ->
                    val href = link.attr("href")
                    val text = link.text().trim()
                    
                    if (text.isNotEmpty() && href.isNotEmpty() && 
                        (href.contains(mainUrl) || href.startsWith("/"))) {
                        
                        println("Found direct link: '$text' -> $href")
                        
                        home.add(
                            newTvSeriesSearchResponse(text, fixUrl(href), TvType.TvSeries)
                        )
                    }
                }
            }
            
            val uniqueHome = home.distinctBy { it.url }
            
            println("=== TOKUZL: Found ${uniqueHome.size} unique items ===")
            
            val hasNext = document.select("a.next, .nav-next, .pagination-next, a[rel=next]").isNotEmpty()
            return newHomePageResponse(request.name, uniqueHome, hasNext = hasNext)
            
        } catch (e: Exception) {
            println("=== TOKUZL ERROR in getMainPage: ${e.message} ===")
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("=== TOKUZL: Searching for '$query' ===")
        
        val encodedQuery = query.replace(" ", "+")
        val url = "$mainUrl/?s=$encodedQuery"
        
        try {
            val document = app.get(url, timeout = 30).document
            
            return document.select("article, .post, .search-item").mapNotNull { article ->
                val link = article.selectFirst("h2 a, h3 a, .entry-title a, a[title]")
                if (link != null) {
                    val title = link.text().trim()
                    val href = link.attr("href")
                    
                    if (title.isNotEmpty() && href.isNotEmpty()) {
                        val posterElement = article.selectFirst("img")
                        val posterUrl = posterElement?.attr("src") 
                            ?: posterElement?.attr("data-src")
                            ?: posterElement?.attr("data-lazy-src")
                        
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
            e.printStackTrace()
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        println("=== TOKUZL: Loading '$url' ===")
        
        try {
            val document = app.get(url, timeout = 30).document
            
            val title = document.selectFirst("h1.entry-title, h1.post-title, h1, .entry-title")?.text()?.trim() 
                ?: "Unknown Title"
            
            val poster = document.selectFirst("img.size-full, img.wp-post-image, img.entry-image, meta[property='og:image']")
                ?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img[src*=.jpg], img[src*=.png]")?.attr("src")
            
            val plot = document.selectFirst("div.entry-content p, article .post-content p, .synopsis")?.text()?.trim()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?: "No description available."
            
            val episodes = mutableListOf<Episode>()
            
            // Look for iframe sources or video embeds in the page
            document.select("iframe[src], video source, a[href*='.mp4'], a[href*='.m3u8']").forEach { element ->
                val href = element.attr("src").ifEmpty { element.attr("href") }
                if (href.isNotEmpty()) {
                    episodes.add(
                        newEpisode(fixUrl(href)) {
                            this.name = "Watch"
                            this.episode = 1
                            this.season = 1
                        }
                    )
                }
            }
            
            // Look for episode links
            document.select("a[href*=episode], a[href*=ep-], .episode-link a").forEachIndexed { index, link ->
                val href = link.attr("href")
                val text = link.text().trim().lowercase()
                
                val epNum = try {
                    Regex("""\d+""").find(text)?.value?.toIntOrNull() ?: (index + 1)
                } catch (e: Exception) { index + 1 }
                
                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                        this.season = 1
                    }
                )
            }
            
            // If no episodes found, use the main page as single episode
            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode(url) {
                        this.name = "Watch"
                        this.episode = 1
                        this.season = 1
                    }
                )
            }
            
            val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }
            
            println("=== TOKUZL: Loaded '$title' with ${sortedEpisodes.size} episodes ===")
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
            }
            
        } catch (e: Exception) {
            println("=== TOKUZL ERROR in load: ${e.message} ===")
            e.printStackTrace()
            
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
        
        try {
            // If data is a direct video URL
            if (data.contains(".mp4") || data.contains(".m3u8")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = data,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = if (data.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
            
            // Otherwise fetch the page and extract video sources
            val document = app.get(data).document
            
            // Look for iframes
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    println("Found iframe: $src")
                    loadExtractor(fixUrl(src), subtitleCallback, callback)
                }
            }
            
            // Look for video sources
            document.select("video source[src], source[src]").forEach { source ->
                val src = source.attr("src")
                if (src.isNotEmpty()) {
                    println("Found video source: $src")
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Direct",
                            url = fixUrl(src),
                            referer = data,
                            quality = Qualities.Unknown.value,
                            type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
            
            return true
            
        } catch (e: Exception) {
            println("=== TOKUZL ERROR in loadLinks: ${e.message} ===")
            e.printStackTrace()
            return false
        }
    }
}