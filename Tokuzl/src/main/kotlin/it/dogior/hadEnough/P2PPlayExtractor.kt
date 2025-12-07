package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

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
            
            // Look for m3u8 URLs
            val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
            val foundUrls = mutableSetOf<String>()
            
            m3u8Regex.findAll(html).forEach { match ->
                val m3u8Url = match.groupValues[1]
                    .replace("\\/", "/")
                    .replace("\\", "")
                    .trim()
                
                if (m3u8Url.contains(".m3u8") && !foundUrls.contains(m3u8Url)) {
                    foundUrls.add(m3u8Url)
                }
            }
            
            // Extract m3u8 URLs
            foundUrls.forEach { m3u8Url ->
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
                    // Correct constructor call - using positional parameters
                    callback.invoke(
                        ExtractorLink(
                            name,                    // source
                            name,                    // name  
                            m3u8Url,                 // url
                            iframeUrl,               // referer
                            Qualities.Unknown.value, // quality
                            ExtractorLinkType.M3U8   // type
                        )
                    )
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
