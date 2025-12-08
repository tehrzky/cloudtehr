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
        
        val home = document.select("article").mapNotNull { article ->
            val link = article.selectFirst("h3 a, h2 a, .entry-title a") ?: return@mapNotNull null
            val title = link.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            
            val href = fixUrl(link.attr("href"))
            val posterUrl = article.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            
            // Check if it's likely a movie
            val isMovie = article.text().contains("Movie", ignoreCase = true) ||
                         title.contains("Movie", ignoreCase = true)
            
            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
        }
        
        return newHomePageResponse(
            request.name, 
            home, 
            hasNext = document.selectFirst("a:contains(Next), .next") != null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${encodeUri(query)}").document
        return document.select("article").mapNotNull { article ->
            val link = article.selectFirst("h3 a, h2 a, .entry-title a") ?: return@mapNotNull null
            val title = link.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            
            val href = fixUrl(link.attr("href"))
            val posterUrl = article.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            
            // Check if it's likely a movie
            val isMovie = article.text().contains("Movie", ignoreCase = true) ||
                         title.contains("Movie", ignoreCase = true)
            
            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("img[src*=wp-content]")?.attr("src")?.let { fixUrl(it) }
        val plot = document.select("div.entry-content, .post-content").text().trim()
        
        // Extract year
        val yearText = document.select("p:contains(Year), span:contains(Year)").text()
        val year = yearText.substringAfter("Year").trim().take(4).toIntOrNull()
        
        // Find all episode links
        val episodes = mutableListOf<Episode>()
        
        // Method 1: Look for episode containers
        document.select("div.episode-list, ul.episodes, .episode-item, .episode-box").forEach { container ->
            container.select("a").forEach { link ->
                val href = link.attr("href")
                if (href.isNotEmpty() && href.contains(".html")) {
                    val linkText = link.text().trim()
                    val epNum = extractEpisodeNumber(linkText, href)
                    
                    episodes.add(
                        newEpisode(fixUrl(href)) {
                            this.name = if (linkText.isNotEmpty()) linkText else "Episode $epNum"
                            this.episode = epNum
                            this.season = 1
                        }
                    )
                }
            }
        }
        
        // Method 2: Look for download/watch buttons
        document.select("a:contains(Watch), a:contains(Download), a:contains(Episode)").forEach { link ->
            val href = link.attr("href")
            if (href.isNotEmpty() && href.contains(".html")) {
                val linkText = link.text().trim()
                val epNum = extractEpisodeNumber(linkText, href)
                
                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = if (linkText.isNotEmpty()) linkText else "Episode $epNum"
                        this.episode = epNum
                        this.season = 1
                    }
                )
            }
        }
        
        // Method 3: Look for any links with episode numbers in the content
        document.select("div.entry-content a[href*=.html], .post-content a[href*=.html]").forEach { link ->
            val href = link.attr("href")
            val linkText = link.text().trim()
            
            if (linkText.contains("\\d+".toRegex()) || href.contains("episode", ignoreCase = true)) {
                val epNum = extractEpisodeNumber(linkText, href)
                
                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = if (linkText.isNotEmpty()) linkText else "Episode $epNum"
                        this.episode = epNum
                        this.season = 1
                    }
                )
            }
        }
        
        // Remove duplicates and sort
        val uniqueEpisodes = episodes
            .distinctBy { it.episode }
            .sortedBy { it.episode }
        
        // If no episodes found, check if it's a movie
        if (uniqueEpisodes.isEmpty()) {
            // Check for video iframes which would indicate it's a movie
            val hasVideo = document.select("iframe").isNotEmpty()
            
            if (hasVideo) {
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                }
            }
            
            // Fallback: Create a single episode
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                newEpisode(url) {
                    this.name = "Watch"
                    this.episode = 1
                    this.season = 1
                }
            )) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }
    
    private fun extractEpisodeNumber(text: String, url: String): Int {
        // Try to extract from text
        val textPatterns = listOf(
            """Episode\s*(\d+)""".toRegex(RegexOption.IGNORE_CASE),
            """EP\s*(\d+)""".toRegex(RegexOption.IGNORE_CASE),
            """E(\d+)""".toRegex(),
            """#(\d+)""".toRegex(),
            """(\d+)(?:st|nd|rd|th)""".toRegex(),
            """\b(\d{1,3})\b""".toRegex()
        )
        
        for (pattern in textPatterns) {
            pattern.find(text)?.let {
                return it.groupValues[1].toIntOrNull() ?: 1
            }
        }
        
        // Try to extract from URL
        val urlPatterns = listOf(
            """episode[-_]?(\d+)""".toRegex(RegexOption.IGNORE_CASE),
            """-ep-?(\d+)""".toRegex(RegexOption.IGNORE_CASE),
            """-(\d+)\.html""".toRegex(),
            """/ep(\d+)""".toRegex(),
            """\?ep=(\d+)""".toRegex(),
            """/(\d+)/?$""".toRegex()
        )
        
        for (pattern in urlPatterns) {
            pattern.find(url)?.let {
                return it.groupValues[1].toIntOrNull() ?: 1
            }
        }
        
        return 1
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Look for iframes first
        val iframes = document.select("iframe")
        if (iframes.isNotEmpty()) {
            iframes.forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    val iframeUrl = when {
                        src.startsWith("//") -> "https:$src"
                        src.startsWith("/") -> "$mainUrl$src"
                        else -> src
                    }
                    
                    // Load iframe content to find video
                    val iframeDoc = app.get(iframeUrl, referer = data).document
                    
                    // Look for video sources
                    iframeDoc.select("source[src], video source").forEach { source ->
                        val videoUrl = source.attr("src")
                        if (videoUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                name,
                                videoUrl,
                                iframeUrl
                            ).forEach(callback)
                            return true
                        }
                    }
                    
                    // Look for m3u8 in scripts
                    val scripts = iframeDoc.select("script")
                    scripts.forEach { script ->
                        val scriptText = script.data()
                        val m3u8Regex = """["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex()
                        m3u8Regex.findAll(scriptText).forEach { match ->
                            val m3u8Url = match.groupValues[1]
                            M3u8Helper.generateM3u8(
                                name,
                                m3u8Url,
                                iframeUrl
                            ).forEach(callback)
                            return true
                        }
                    }
                }
            }
        }
        
        // Look for direct video links in the page
        document.select("source[src], video source, a[href$=.m3u8]").forEach { element ->
            val videoUrl = element.attr("src") ?: element.attr("href")
            if (videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name,
                    videoUrl,
                    data
                ).forEach(callback)
                return true
            }
        }
        
        // Look for m3u8 in scripts
        document.select("script").forEach { script ->
            val scriptText = script.data()
            val m3u8Regex = """["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex()
            m3u8Regex.findAll(scriptText).forEach { match ->
                val m3u8Url = match.groupValues[1]
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    data
                ).forEach(callback)
                return true
            }
        }
        
        return false
    }
}
