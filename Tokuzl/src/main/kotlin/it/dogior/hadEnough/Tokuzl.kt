package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import org.jsoup.nodes.Element

@CloudstreamPlugin
class TokuzlPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TokuzlProvider())
        registerExtractorAPI(P2PPlayExtractor())
    }
}

class TokuzlProvider : MainAPI() {
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
        
        // Select all show containers
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

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElem = this.selectFirst("a[href*=.html]") ?: return null
        val title = titleElem.text().trim()
        if (title.isEmpty()) return null
        
        val href = fixUrl(titleElem.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
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
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
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
        
        // Debug: Print all iframes found
        val iframes = document.select("iframe")
        println("Tokuzl: Found ${iframes.size} iframes")
        
        if (iframes.isEmpty()) {
            // No iframe found, try to find script with video source
            val scripts = document.select("script")
            scripts.forEach { script ->
                val content = script.data()
                if (content.contains("m3u8") || content.contains("p2pplay") || content.contains("video")) {
                    println("Tokuzl: Found potential video script")
                    
                    // Extract any m3u8 URLs
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                    m3u8Regex.findAll(content).forEach { match ->
                        val m3u8Url = match.groupValues[1].replace("\\/", "/")
                        println("Tokuzl: Found m3u8: $m3u8Url")
                        
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
                println("Tokuzl: Processing iframe: $iframeSrc")
                
                if (iframeSrc.isNotEmpty()) {
                    val iframeUrl = when {
                        iframeSrc.startsWith("//") -> "https:$iframeSrc"
                        iframeSrc.startsWith("/") -> "$mainUrl$iframeSrc"
                        else -> iframeSrc
                    }
                    
                    println("Tokuzl: Full iframe URL: $iframeUrl")
                    
                    // Use P2PPlay extractor for p2pplay domains
                    if (iframeUrl.contains("p2pplay", ignoreCase = true)) {
                        println("Tokuzl: Using P2PPlay extractor")
                        P2PPlayExtractor().getUrl(
                            url = iframeUrl,
                            referer = data,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    } else {
                        // Generic iframe handler
                        try {
                            val iframeDoc = app.get(iframeUrl, referer = data)
                            val iframeHtml = iframeDoc.text
                            
                            val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                            m3u8Regex.findAll(iframeHtml).forEach { match ->
                                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                                println("Tokuzl: Found m3u8 in iframe: $m3u8Url")
                                
                                M3u8Helper.generateM3u8(
                                    name,
                                    m3u8Url,
                                    iframeUrl
                                ).forEach(callback)
                            }
                        } catch (e: Exception) {
                            println("Tokuzl: Error processing iframe: ${e.message}")
                        }
                    }
                }
            }
        }
        
        return true
    }
}
