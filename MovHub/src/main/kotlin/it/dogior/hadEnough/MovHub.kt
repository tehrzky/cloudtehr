package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

class MovHub : MainAPI() {
    override var mainUrl = "https://movhub.ws"
    override var name = "MovHub"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/browser?sort=trending" to "Trending",
        "$mainUrl/browser?sort=latest" to "Latest",  // FIXED: Added sort parameter
        "$mainUrl/browser?sort=popular" to "Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        val document = app.get(url, headers = headers).document
        
        val items = document.select("div.movie-cards div.item").mapNotNull { item ->
            val poster = item.selectFirst("a.poster") ?: return@mapNotNull null
            val title = item.selectFirst("a.title")?.text() ?: return@mapNotNull null
            val itemUrl = fixUrl(poster.attr("href"))
            val posterUrl = item.selectFirst("img")?.attr("data-src") ?: ""
            
            val isSeries = itemUrl.contains("/tv/")
            
            if (isSeries) {
                newTvSeriesSearchResponse(title, itemUrl) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, itemUrl) {
                    this.posterUrl = posterUrl
                }
            }
        }
        
        val hasNextPage = document.selectFirst("li.page-item a[rel=next]") != null
        
        return newHomePageResponse(request.name, items, hasNext = hasNextPage)
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/browser?keyword=$query", headers = headers).document
        
        return document.select("div.movie-cards div.item").mapNotNull { item ->
            val poster = item.selectFirst("a.poster") ?: return@mapNotNull null
            val title = item.selectFirst("a.title")?.text() ?: return@mapNotNull null
            val itemUrl = fixUrl(poster.attr("href"))
            val posterUrl = item.selectFirst("img")?.attr("data-src") ?: ""
            
            val isSeries = itemUrl.contains("/tv/")
            
            if (isSeries) {
                newTvSeriesSearchResponse(title, itemUrl) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, itemUrl) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        
        val title = document.selectFirst("h1.title")?.text() ?: "Unknown"
        val posterUrl = document.selectFirst("div.poster img")?.attr("src") ?: ""
        val plot = document.selectFirst(".description")?.text() ?: ""
        
        val isMovie = document.selectFirst("ol.breadcrumb li a[href*='/movie']") != null
        
        val genre = document.select("ul.mics li:has(a[href*=/genre/]) a").eachText()
        val year = document.selectFirst("ul.mics li:contains(Released:)")
            ?.text()?.substringAfter(":")?.trim()?.take(4)?.toIntOrNull()
        
        val contentId = document.selectFirst("#movie-rating[data-id]")?.attr("data-id")
            ?: throw ErrorLoadingException("Content ID not found")
        
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, contentId) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genre
                this.year = year
            }
        } else {
            val episodes = getEpisodes(contentId, url)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genre
                this.year = year
            }
        }
    }

    private suspend fun getEpisodes(contentId: String, animeUrl: String): List<Episode> {
        val encryptedId = encrypt(contentId)
        val ajaxUrl = "$mainUrl/ajax/episodes/list?id=$contentId&_=$encryptedId"
        
        val ajaxHeaders = headers + mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to animeUrl
        )
        
        val response = app.get(ajaxUrl, headers = ajaxHeaders).text
        val resultData = parseJson<ResultResponse>(response)
        val resultDoc = Jsoup.parse(resultData.result)
        
        val episodes = mutableListOf<Episode>()
        
        resultDoc.select("ul.episodes[data-season]").forEach { seasonElement ->
            val seasonNum = seasonElement.attr("data-season").toIntOrNull() ?: 1
            
            seasonElement.select("li a").forEach { element ->
                val episodeId = element.attr("eid")
                val hasEpisodeNum = element.selectFirst("span.num") != null
                
                if (hasEpisodeNum) {
                    val epNum = element.attr("num").toIntOrNull() ?: 0
                    val epTitle = element.selectFirst("span:not(.num)")?.text()?.trim() ?: ""
                    
                    episodes.add(
                        newEpisode(episodeId) {
                            this.name = "S$seasonNum E$epNum: $epTitle"
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                } else {
                    val movieTitle = element.selectFirst("span")?.text()?.trim() ?: "Movie"
                    episodes.add(
                        newEpisode(episodeId) {
                            this.name = movieTitle
                            this.season = 1
                            this.episode = 1
                        }
                    )
                }
            }
        }
        
        return episodes.reversed()
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val episodeId = data
    
    println("MovHub DEBUG - Loading links for episode ID: $episodeId")
    
    try {
        val encryptedId = encrypt(episodeId)
        println("MovHub DEBUG - Encrypted ID: $encryptedId")
        
        val serversUrl = "$mainUrl/ajax/links/list?eid=$episodeId&_=$encryptedId"
        println("MovHub DEBUG - Servers URL: $serversUrl")
        
        val ajaxHeaders = headers + mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest"
        )
        
        val serversResponse = app.get(serversUrl, headers = ajaxHeaders).text
        println("MovHub DEBUG - Servers response: $serversResponse")
        
        val serversData = parseJson<ResultResponse>(serversResponse)
        val serversDoc = Jsoup.parse(serversData.result)
        
        // FIXED: Select the <a> tags directly since they have the data-lid
        val servers = serversDoc.select("li.link-item a[data-lid]")
        println("MovHub DEBUG - Found ${servers.size} servers")
        
        servers.forEachIndexed { index, serverElement ->
            val serverName = serverElement.text() ?: "Server ${index + 1}"
            // FIXED: Get data-lid from the <a> element directly
            val serverId = serverElement.attr("data-lid")
            
            println("MovHub DEBUG - Processing server: $serverName (ID: $serverId)")
            
            if (serverId.isEmpty()) {
                println("MovHub DEBUG - Skipping server with empty ID")
                return@forEachIndexed
            }
            
            try {
                val encryptedServerId = encrypt(serverId)
                println("MovHub DEBUG - Encrypted server ID: $encryptedServerId")
                
                val viewUrl = "$mainUrl/ajax/links/view?id=$serverId&_=$encryptedServerId"
                println("MovHub DEBUG - View URL: $viewUrl")
                
                val viewResponse = app.get(viewUrl, headers = ajaxHeaders).text
                println("MovHub DEBUG - View response: $viewResponse")
                
                val viewData = parseJson<ResultResponse>(viewResponse)
                println("MovHub DEBUG - View data result: ${viewData.result}")
                
                val iframeUrl = decrypt(viewData.result)
                println("MovHub DEBUG - Decrypted iframe URL: $iframeUrl")
                
                // Use the RapidShare extractor
                val extractor = RapidShareExtractor()
                extractor.getUrl(iframeUrl, mainUrl, subtitleCallback, callback)
                
            } catch (e: Exception) {
                println("MovHub DEBUG - Error processing server $serverName: ${e.message}")
                e.printStackTrace()
            }
        }
        
        return true
    } catch (e: Exception) {
        println("MovHub DEBUG - Error in loadLinks: ${e.message}")
        e.printStackTrace()
        return false
    }
}

    private suspend fun encrypt(text: String): String {
        val response = app.get("https://enc-dec.app/api/enc-movies-flix?text=$text").text
        return parseJson<ResultResponse>(response).result
    }

    private suspend fun decrypt(text: String): String {
        val response = app.get("https://enc-dec.app/api/dec-movies-flix?text=$text").text
        return parseJson<DecryptedIframeResponse>(response).result.url
    }

    @Serializable
    data class ResultResponse(
        val result: String
    )

    @Serializable
    data class DecryptedIframeResponse(
        val result: DecryptedResult
    )

    @Serializable
    data class DecryptedResult(
        val url: String
    )
}
