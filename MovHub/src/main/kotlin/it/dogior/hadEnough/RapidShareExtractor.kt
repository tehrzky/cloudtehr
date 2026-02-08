package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import okhttp3.HttpUrl.Companion.toHttpUrl

class RapidShareExtractor : ExtractorApi() {
    override val mainUrl = "movhub.to"
    override val name = "RapidShare"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val rapidUrl = url.toHttpUrl()
            val token = rapidUrl.pathSegments.last()
            val baseUrl = "${rapidUrl.scheme}://${rapidUrl.host}"
            val mediaUrl = "$baseUrl/media/$token"

            // Get the response
            val response = app.get(mediaUrl)
            val responseText = response.text
            
            // Look for M3U8 URLs in the response
            val m3u8Pattern = """https?://[^"\s]+\.m3u8[^"\s]*""".toRegex()
            val m3u8Urls = m3u8Pattern.findAll(responseText).map { it.value }.toList()
            
            // Look for subtitles
            val subtitlePattern = """https?://[^"\s]+\.(vtt|srt)[^"\s]*""".toRegex()
            val subtitleUrls = subtitlePattern.findAll(responseText).map { it.value }.toList()
            
            // Send subtitles - FIXED: Use simpler approach
            subtitleUrls.forEach { subtitleUrl ->
                subtitleCallback(SubtitleFile(subtitleUrl, "English"))
            }
            
            // Process M3U8 URLs
            m3u8Urls.forEach { m3u8Url ->
                M3u8Helper.generateM3u8(
                    name, // source
                    m3u8Url, // streamUrl
                    referer = "$baseUrl/"
                ).forEach(callback)
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
