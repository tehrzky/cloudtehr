package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink

class P2PPlayExtractor : ExtractorApi() {
    override val name = "P2PPlay"
    override val mainUrl = "https://p2pplay.pro"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val iframeUrl = if (url.startsWith("//")) "https:$url" else url
        
        try {
            // Fetch iframe content
            val response = app.get(iframeUrl, referer = referer ?: "")
            val html = response.text
            
            // Look for m3u8 URLs - simplified regex
            val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
            
            m3u8Regex.findAll(html).forEach { match ->
                val m3u8Url = match.value
                    .replace("\\/", "/")
                    .replace("\\", "")
                    .replace("\"", "")
                    .replace("'", "")
                    .trim()
                
                if (m3u8Url.contains(".m3u8")) {
                    try {
                        M3u8Helper.generateM3u8(
                            name,
                            m3u8Url,
                            iframeUrl,
                            headers = mapOf(
                                "Origin" to mainUrl,
                                "Referer" to iframeUrl
                            )
                        ).forEach(callback)
                    } catch (e: Exception) {
                        // Use newExtractorLink for m3u8 URLs
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = iframeUrl
                            }
                        )
                    }
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
