package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

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
            val response = app.get(iframeUrl, referer = referer)
            val document = response.document
            val html = response.text
            
            // Method 1: Find m3u8 in script tags
            val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            val matches = m3u8Regex.findAll(html)
            
            matches.forEach { match ->
                val m3u8Url = match.groupValues[1]
                    .replace("\\/", "/")
                    .replace("\\", "")
                
                try {
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        iframeUrl,
                        headers = mapOf("Origin" to mainUrl)
                    ).forEach(callback)
                } catch (e: Exception) {
                    // Continue to next match if this one fails
                }
            }
            
            // Method 2: Look for video source tags
            val videoSrc = document.selectFirst("video source")?.attr("src")
                ?: document.selectFirst("video")?.attr("src")
            
            if (videoSrc != null && videoSrc.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name,
                    videoSrc,
                    iframeUrl,
                    headers = mapOf("Origin" to mainUrl)
                ).forEach(callback)
            }
            
            // Method 3: Look for specific p2pplay patterns
            val p2pPlayRegex = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""")
            p2pPlayRegex.find(html)?.let { match ->
                val m3u8Url = match.groupValues[1]
                    .replace("\\/", "/")
                
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    iframeUrl,
                    headers = mapOf("Origin" to mainUrl)
                ).forEach(callback)
            }
            
            // Method 4: Check for data attributes
            document.select("[data-src*=.m3u8]").forEach { element ->
                val dataSrc = element.attr("data-src")
                if (dataSrc.isNotEmpty()) {
                    M3u8Helper.generateM3u8(
                        name,
                        dataSrc,
                        iframeUrl,
                        headers = mapOf("Origin" to mainUrl)
                    ).forEach(callback)
                }
            }
            
        } catch (e: Exception) {
            // Log error or handle gracefully
        }
    }
}
