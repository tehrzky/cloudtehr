package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Tokuzl : MainAPI() {
    override var mainUrl = "https://tokuzl.net"
    override var name = "Tokuzl"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

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
        
        try {
            val document = app.get(url).document
            val items = mutableListOf<SearchResponse>()
            
            // Try multiple selectors to find content
            val contentSelectors = listOf(
                "div.post-content a",
                "div.entry-content a", 
                ".post-body a",
                "article a",
                ".content a"
            )
            
            for (selector in contentSelectors) {
                document.select(selector).forEach { link ->
                    val href = link.attr("href")
                    val text = link.text().trim()
                    
                    // Only get actual show pages
                    if (href.isNotEmpty() && 
                        text.isNotEmpty() && 
                        href.contains(mainUrl) &&
                        (href.contains("/kamen-rider/") || 
                         href.contains("/super-sentai/") || 
                         href.contains("/ultraman/") ||
                         href.contains("/power-ranger/") ||
                         href.contains("/garo/") ||
                         href.contains("/metal-heroes/")) &&
                        !href.contains("/page/")) {
                        
                        // Get poster from nearby img
                        var poster: String? = null
                        val parent = link.parent()
                        parent?.let {
                            val img = it.selectFirst("img")
                            poster = img?.attr("src") ?: img?.attr("data-src")
                            if (!poster.isNullOrEmpty() && !poster!!.startsWith("http")) {
                                poster = fixUrl(poster!!)
                            }
                        }
                        
                        items.add(
                            newTvSeriesSearchResponse(text, fixUrl(href), TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
                
                if (items.isNotEmpty()) break
            }
            
            return newHomePageResponse(
                request.name, 
                items.distinctBy { it.url }, 
                hasNext = document.select("a.next, .nav-next").isNotEmpty()
            )
            
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
            
            document.select("a").mapNotNull { link ->
                val href = link.attr("href")
                val title = link.text().trim()
                
                if (title.isNotEmpty() && 
                    href.contains(mainUrl) && 
                    title.length > 3 &&
                    (href.contains("/kamen-rider/") || 
                     href.contains("/super-sentai/") || 
                     href.contains("/ultraman/") ||
                     href.contains("/power-ranger/") ||
                     href.contains("/garo/") ||
                     href.contains("/metal-heroes/"))) {
                    
                    val poster = link.parent()?.selectFirst("img")?.attr("src")
                    
                    newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster?.let { fixUrl(it) }
                    }
                } else null
            }.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("img")?.attr("src")
        val description = document.selectFirst(".entry-content p, .post-content p")?.text()
        
        val episodes = mutableListOf<Episode>()
        
        // Find all iframes and video sources
        document.select("iframe[src]").forEachIndexed { index, iframe ->
            episodes.add(newEpisode(fixUrl(iframe.attr("src"))) {
                this.name = "Episode ${index + 1}"
                this.episode = index + 1
            })
        }
        
        // If no iframes, just use the page URL
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "Watch"
                this.episode = 1
            })
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Handle direct video URLs
        if (data.contains(".mp4") || data.contains(".m3u8")) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Direct",
                    url = data,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value
                )
            )
            return true
        }
        
        // Load the page and extract sources
        val document = app.get(data).document
        
        // Extract iframes
        document.select("iframe[src]").forEach { iframe ->
            loadExtractor(fixUrl(iframe.attr("src")), subtitleCallback, callback)
        }
        
        // Extract video tags
        document.select("video source[src]").forEach { source ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Video",
                    url = fixUrl(source.attr("src")),
                    referer = data,
                    quality = Qualities.Unknown.value
                )
            )
        }
        
        return true
    }
}