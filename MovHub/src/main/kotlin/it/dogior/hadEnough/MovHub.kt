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
        "$mainUrl/browse/trending" to "Trending",
        "$mainUrl/browse/latest" to "Latest",
        "$mainUrl/browse/top10" to "Top 10"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        val document = app.get(url, headers = headers).document

        val items = document.select("div.movie-cards div.item").mapNotNull { item ->
            val poster = item.selectFirst("a.poster") ?: return@mapNotNull null
            val title = item.selectFirst("a.title")?.text() ?: return@mapNotNull null
            val itemUrl = fixUrl(poster.attr("href"))
            val posterUrl = item.selectFirst("img")?.attr("data-src") ?: ""

            if (itemUrl.contains("/tv/")) {
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

            if (itemUrl.contains("/tv/")) {
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
        println("MovHub DEBUG - loadLinks called with data: $data")

        try {
            val encryptedId = encrypt(data)
            val serversUrl = "$mainUrl/ajax/links/list?eid=$data&_=$encryptedId"

            val ajaxHeaders = headers + mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            )

            val serversResponse = app.get(serversUrl, headers = ajaxHeaders).text
            println("MovHub DEBUG - Servers response: $serversResponse")

            val serversData = parseJson<ResultResponse>(serversResponse)
            val serversDoc = Jsoup.parse(serversData.result)

            val servers = serversDoc.select("li.link-item a[data-lid]")
            println("MovHub DEBUG - Found ${servers.size} servers")

            servers.forEach { serverElement ->
                val serverId = serverElement.attr("data-lid")
                val serverName = serverElement.text()
                println("MovHub DEBUG - Processing server: $serverName ($serverId)")

                if (serverId.isEmpty()) return@forEach

                try {
                    val encryptedServerId = encrypt(serverId)
                    val viewUrl = "$mainUrl/ajax/links/view?id=$serverId&_=$encryptedServerId"

                    val viewResponse = app.get(viewUrl, headers = ajaxHeaders).text
                    println("MovHub DEBUG - View response: $viewResponse")

                    val iframeUrl = parseJson<ResultResponse>(viewResponse).result
                    println("MovHub DEBUG - Iframe URL: $iframeUrl")

                    // Directly call RapidShareExtractor since it's registered
                    val extractor = RapidShareExtractor()
                    extractor.getUrl(iframeUrl, mainUrl, subtitleCallback, callback)

                } catch (e: Exception) {
                    println("MovHub DEBUG - Server error: ${e.message}")
                }
            }

            return true
        } catch (e: Exception) {
            println("MovHub DEBUG - loadLinks error: ${e.message}")
            return false
        }
    }

    private suspend fun encrypt(text: String): String {
        val response = app.get("https://enc-dec.app/api/enc-movies-flix?text=$text").text
        return parseJson<ResultResponse>(response).result
    }

    @Serializable
    data class ResultResponse(
        val result: String
    )
}
