package com.tokuzl

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        val document = app.get("${request.data}/page/$page").document
        val home = document.select("div.film_list-wrap > div.flw-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.film-name a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.film_list-wrap > div.flw-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.film-poster img")?.attr("src")
        val plot = document.select("div.description p").text()
        val year = document.selectFirst("span:contains(Year)")?.text()?.substringAfter("Year")?.trim()?.toIntOrNull()
        
        // Get all episodes
        val episodes = document.select("div.episode-list a, ul li a[href*=?ep=]").mapNotNull { ep ->
            val epNum = ep.attr("href").substringAfter("?ep=").toIntOrNull() ?: return@mapNotNull null
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
                    callback.invoke(
                        ExtractorLink(
                            name,
                            name,
                            m3u8Url,
                            iframe,
                            Qualities.Unknown.value,
                            true
                        )
                    )
                }
            }
            
            // Try extracting from video tag
            val videoSrc = iframeDoc.selectFirst("video source")?.attr("src")
                ?: iframeDoc.selectFirst("video")?.attr("src")
            
            if (videoSrc != null && videoSrc.contains(".m3u8")) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        fixUrl(videoSrc),
                        iframe,
                        Qualities.Unknown.value,
                        true
                    )
                )
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
        
        return true
    }
}
