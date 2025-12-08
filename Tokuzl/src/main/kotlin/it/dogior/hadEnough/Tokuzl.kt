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
        val document = app.get(url).document
        
        // Select all show containers using the working selector
        val home = document.select("h3 a[href*=.html]").mapNotNull { link ->
            val title = link.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            
            val href = fixUrl(link.attr("href"))
            // Find the closest img tag (usually in parent's sibling or parent)
            val posterUrl = link.parent()?.parent()?.selectFirst("img")?.attr("src")
            
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
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("h3 a[href*=.html]").mapNotNull { link ->
            val title = link.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            
            val href = fixUrl(link.attr("href"))
            val posterUrl = link.parent()?.parent()?.selectFirst("img")?.attr("src")
            
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("img[src*=wp-content]")?.attr("src")
        val plot = document.select("p").text()
        
        // Extract year from various possible locations
        val yearText = document.select("p:contains(Year), span:contains(Year)").text()
        val year = yearText.substringAfter("Year").trim().take(4).toIntOrNull()
        
        // Get all episodes from the numbered list
        val episodes = document.select("ul li a[href*=?ep=]").mapNotNull { ep ->
            val epNum = ep.text().trim().toIntOrNull() ?: return@mapNotNull null
            val epHref = fixUrl(ep.attr("href"))
            
            newEpisode(epHref) {
                this.name = "Episode $epNum"
                this.episode = epNum
            }
        }

        // If no episodes found, use the page itself
        if (episodes.isEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                newEpisode(url) {
                    this.name = "Watch"
                    this.episode = 1
                }
            )) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
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
        val document = app.get(data).document
        
        // Find iframes
        val iframes = document.select("iframe")
        
        if (iframes.isEmpty()) {
            // No iframe found, try to find script with video source
            val scripts = document.select("script")
            scripts.forEach { script ->
                val content = script.data()
                if (content.contains("m3u8") || content.contains("p2pplay") || content.contains("video")) {
                    // Extract any m3u8 URLs
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                    m3u8Regex.findAll(content).forEach { match ->
                        val m3u8Url = match.groupValues[1].replace("\\/", "/")
                        
                        try {
                            M3u8Helper.generateM3u8(
                                name,
                                m3u8Url,
                                data
                            ).forEach(callback)
                        } catch (e: Exception) {
                            callback.invoke(
                                ExtractorLink(
                                    name,
                                    name,
                                    m3u8Url,
                                    data,
                                    Qualities.Unknown.value,
                                    true
                                )
                            )
                        }
                    }
                }
            }
        } else {
            // Process each iframe
            iframes.forEach { iframeElement ->
                val iframeSrc = iframeElement.attr("src")
                
                if (iframeSrc.isNotEmpty()) {
                    val iframeUrl = when {
                        iframeSrc.startsWith("//") -> "https:$iframeSrc"
                        iframeSrc.startsWith("/") -> "$mainUrl$iframeSrc"
                        else -> iframeSrc
                    }
                    
                    // Use loadExtractor for standard extractors
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                    
                    // Also try to extract m3u8 directly from iframe
                    try {
                        val iframeDoc = app.get(iframeUrl, referer = data)
                        val iframeHtml = iframeDoc.text
                        
                        val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                        m3u8Regex.findAll(iframeHtml).forEach { match ->
                            val m3u8Url = match.groupValues[1].replace("\\/", "/")
                            
                            M3u8Helper.generateM3u8(
                                name,
                                m3u8Url,
                                iframeUrl
                            ).forEach(callback)
                        }
                    } catch (e: Exception) {
                        // Silently fail
                    }
                }
            }
        }
        
        return true
    }
}