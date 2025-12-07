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
        val home = document.select("article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = document.selectFirst("a:contains(Next)") != null)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElem = this.selectFirst("h3 a") ?: return null
        val title = titleElem.text().trim()
        val href = fixUrl(titleElem.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article").mapNotNull {
            it.toSearchResult()
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
        
        // Try to find iframe or video source
        val iframe = document.selectFirst("iframe[src*=p2pplay]")?.attr("src")
            ?: document.selectFirst("iframe")?.attr("src")
        
        if (iframe != null) {
            val iframeDoc = app.get(fixUrl(iframe), referer = data).document
            
            // Extract m3u8 link from iframe
            val scriptData = iframeDoc.select("script").firstOrNull { 
                it.data().contains(".m3u8") 
            }?.data()
            
            if (scriptData != null) {
                val m3u8Regex = """https?://[^\s"']+\.m3u8[^\s"']*""".toRegex()
                val m3u8Links = m3u8Regex.findAll(scriptData).map { it.value }.toList()
                
                m3u8Links.forEach { m3u8Url ->
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        iframe
                    ).forEach(callback)
                }
            }
            
            // Try extracting from video tag
            val videoSrc = iframeDoc.selectFirst("video source")?.attr("src")
                ?: iframeDoc.selectFirst("video")?.attr("src")
            
            if (videoSrc != null && videoSrc.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name,
                    fixUrl(videoSrc),
                    iframe
                ).forEach(callback)
            }
        }
        
        // Fallback: try to extract directly from page
        val pageScript = document.select("script").firstOrNull {
            it.data().contains(".m3u8")
        }?.data()
        
        if (pageScript != null) {
            val m3u8Regex = """https?://[^\s"']+\.m3u8[^\s"']*""".toRegex()
            val m3u8Links = m3u8Regex.findAll(pageScript).map { it.value }.toList()
            
            m3u8Links.forEach { m3u8Url ->
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    data
                ).forEach(callback)
            }
        }
        
        return true
    }
}
