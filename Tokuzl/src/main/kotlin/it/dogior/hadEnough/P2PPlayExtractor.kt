package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
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
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to (referer ?: ""),
                "Accept" to "*/*"
            )
            
            // Fetch iframe content with headers
            val response = app.get(iframeUrl, headers = headers)
            val html = response.text
            
            // Look for m3u8 URLs
            val patterns = listOf(
                Regex("""(https?://[^"\s'<>]+\.m3u8[^"\s'<>]*)"""),
                Regex("""(https?:\\/\\/[^"\s'<>]+\\.m3u8[^"\s'<>]*)"""),
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""src\s*=\s*["']([^"']+\.m3u8[^"']*)["']""")
            )
            
            val foundUrls = mutableSetOf<String>()
            
            patterns.forEach { pattern ->
                pattern.findAll(html).forEach { match ->
                    val matchedUrl = if (match.groupValues.size > 1) {
                        match.groupValues[1]
                    } else {
                        match.value
                    }
                    
                    val cleanUrl = matchedUrl
                        .replace("\\/", "/")
                        .replace("\\", "")
                        .replace("\"", "")
                        .replace("'", "")
                        .trim()
                    
                    if (cleanUrl.contains(".m3u8") && !foundUrls.contains(cleanUrl)) {
                        foundUrls.add(cleanUrl)
                    }
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
                    // If M3u8Helper fails, create direct link with isM3u8 = true
                    callback.invoke(
                        ExtractorLink(
                            name,
                            name,
                            m3u8Url,
                            iframeUrl,
                            Qualities.Unknown.value,
                            true  // Set isM3u8 here
                        )
                    )
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
