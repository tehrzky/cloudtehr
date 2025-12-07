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
            
            // More aggressive regex patterns
            val patterns = listOf(
                // Standard m3u8 URLs
                Regex("""(https?://[^"\s'<>]+\.m3u8[^"\s'<>]*)"""),
                // Escaped URLs
                Regex("""(https?:\\/\\/[^"\s'<>]+\\.m3u8[^"\s'<>]*)"""),
                // In quotes
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                // file: property
                Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                // src attribute
                Regex("""src\s*=\s*["']([^"']+\.m3u8[^"']*)["']"""),
                // P2P specific pattern
                Regex("""(?:source|src|file)["'\s:=]+["']?(https?://[^"'\s]+p2pplay[^"'\s]+\.m3u8[^"'\s]*)""")
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
            
            // Try to extract from found URLs
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
                    // If M3u8Helper fails, try direct link
                    callback.invoke(
                        ExtractorLink(
                            name,
                            name,
                            m3u8Url,
                            iframeUrl,
                            Qualities.Unknown.value,
                            true
                        )
                    )
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
