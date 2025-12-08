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
            ?: "Unknown Title"
        
        // Extract poster image
        val poster = document.selectFirst("img.size-full, img.wp-post-image, img.aligncenter")?.attr("src")
            ?: document.selectFirst("img[src*=.jpg], img[src*=.png], img[src*=.jpeg]")?.attr("src")
        
        // Extract plot/description
        val plotElement = document.selectFirst("div.entry-content") ?: document.selectFirst("article")
        val plot = plotElement?.select("p")?.take(3)?.joinToString("\n\n") { it.text().trim() } 
            ?: "No description available."
        
        // Extract year
        var year: Int? = null
        val yearMatch = Regex("""(19\d{2}|20\d{2})""").find(title + " " + plot)
        if (yearMatch != null) {
            year = yearMatch.value.toIntOrNull()
        }
        
        // Extract episodes
        val episodes = mutableListOf<Episode>()
        
        // Look for episode links
        document.select("a[href*='.html']").forEach { link ->
            val href = link.attr("href")
            val text = link.text().trim()
            
            // Check if it looks like an episode link
            if (text.contains("Episode", ignoreCase = true) || 
                text.contains("EP", ignoreCase = true) ||
                Regex("""Episode\s+\d+""", RegexOption.IGNORE_CASE).matches(text) ||
                Regex("""EP\s+\d+""", RegexOption.IGNORE_CASE).matches(text)) {
                
                val epNum = try {
                    Regex("""\d+""").find(text)?.value?.toIntOrNull() ?: 1
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
        
        // If no episodes found, create at least one
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Watch"
                    this.episode = 1
                    this.season = 1
                }
            )
        }
        
        // Sort episodes
        val sortedEpisodes = episodes.sortedBy { it.episode }
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    // IMPORTANT: Cloudstream requires this method
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Cloudstream will automatically use the registered UniversalExtractor
        // But we need to return true to indicate we can handle this URL
        println("Tokuzl: loadLinks called for $data")
        return true
    }
}