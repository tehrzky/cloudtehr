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
        println("P2PPlayExtractor: Starting extraction for: $url")
        
        val iframeUrl = if (url.startsWith("//")) "https:$url" else url
        println("P2PPlayExtractor: Full URL: $iframeUrl")
        
        try {
            // Try multiple approaches
            
            // Approach 1: Direct m3u8 URL pattern
            if (iframeUrl.contains(".m3u8")) {
                println("P2PPlayExtractor: URL is already m3u8, using directly")
                try {
                    M3u8Helper.generateM3u8(
                        name,
                        iframeUrl,
                        referer ?: "",
                        headers = mapOf(
                            "Referer" to referer ?: "",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    ).forEach(callback)
                    return
                } catch (e: Exception) {
                    println("P2PPlayExtractor: Direct m3u8 failed: ${e.message}")
                }
            }
            
            // Approach 2: Fetch iframe and search for m3u8
            println("P2PPlayExtractor: Fetching iframe content...")
            val response = app.get(iframeUrl, referer = referer ?: "", headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Accept-Encoding" to "gzip, deflate, br",
                "DNT" to "1",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            ))
            
            val html = response.text
            println("P2PPlayExtractor: HTML length: ${html.length}")
            
            // Save HTML for debugging
            // println("P2PPlayExtractor: HTML sample: ${html.take(500)}")
            
            // Try multiple regex patterns for m3u8
            val patterns = listOf(
                // Direct m3u8 URL
                Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""", RegexOption.IGNORE_CASE),
                // In quotes
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                // In data attributes
                Regex("""data-(?:src|file)\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                // In script variables
                Regex("""(?:src|file|url)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                // P2P specific pattern
                Regex("""(https?://[^"\s]+p2pplay[^"\s]+\.m3u8[^"\s]*)""", RegexOption.IGNORE_CASE)
            )
            
            val foundUrls = mutableSetOf<String>()
            
            patterns.forEachIndexed { i, pattern ->
                val matches = pattern.findAll(html).toList()
                if (matches.isNotEmpty()) {
                    println("P2PPlayExtractor: Pattern $i found ${matches.size} matches")
                    matches.forEach { match ->
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
                            .replace(" ", "")
                            .trim()
                        
                        if (cleanUrl.contains(".m3u8", ignoreCase = true) && 
                            !foundUrls.contains(cleanUrl) &&
                            cleanUrl.startsWith("http")) {
                            println("P2PPlayExtractor: Found m3u8: $cleanUrl")
                            foundUrls.add(cleanUrl)
                        }
                    }
                }
            }
            
            // Also look for base64 encoded URLs
            val base64Pattern = Regex("""["']([A-Za-z0-9+/=]+\.m3u8)["']""", RegexOption.IGNORE_CASE)
            base64Pattern.findAll(html).forEach { match ->
                val encoded = match.groupValues[1]
                println("P2PPlayExtractor: Found possible base64 encoded: $encoded")
                try {
                    // Try to decode base64
                    // val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                    // println("P2PPlayExtractor: Decoded: $decoded")
                } catch (e: Exception) {
                    // Not base64
                }
            }
            
            println("P2PPlayExtractor: Total unique m3u8 URLs: ${foundUrls.size}")
            
            if (foundUrls.isEmpty()) {
                println("P2PPlayExtractor: No m3u8 URLs found, trying alternative approaches...")
                
                // Look for video source tags
                val sourceTags = Regex("""<source[^>]+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html).toList()
                println("P2PPlayExtractor: Found ${sourceTags.size} source tags")
                sourceTags.forEach { match ->
                    val src = match.groupValues[1]
                    println("P2PPlayExtractor: Source tag src: $src")
                    if (src.isNotEmpty() && src.startsWith("http")) {
                        foundUrls.add(src)
                    }
                }
                
                // Look for iframes within iframe
                val innerIframes = Regex("""<iframe[^>]+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html).toList()
                println("P2PPlayExtractor: Found ${innerIframes.size} inner iframes")
                innerIframes.forEach { match ->
                    val innerSrc = match.groupValues[1]
                    println("P2PPlayExtractor: Inner iframe: $innerSrc")
                }
            }
            
            // Process found URLs
            foundUrls.forEach { m3u8Url ->
                println("P2PPlayExtractor: Processing: $m3u8Url")
                try {
                    val links = M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        iframeUrl,
                        headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to iframeUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Accept" to "*/*",
                            "Accept-Language" to "en-US,en;q=0.9",
                            "Connection" to "keep-alive"
                        )
                    )
                    println("P2PPlayExtractor: Generated ${links.size} M3U8 links")
                    links.forEach(callback)
                } catch (e: Exception) {
                    println("P2PPlayExtractor: M3u8Helper failed: ${e.message}")
                    // Fallback: direct link
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = iframeUrl
                            this.headers = mapOf(
                                "Origin" to mainUrl,
                                "Referer" to iframeUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                }
            }
            
            if (foundUrls.isEmpty()) {
                println("P2PPlayExtractor: No video URLs found at all")
                // Return the iframe URL itself as a last resort
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "iframe",
                        url = iframeUrl,
                        type = ExtractorLinkType.GENERIC
                    ) {
                        this.referer = referer ?: ""
                    }
                )
            }
            
        } catch (e: Exception) {
            println("P2PPlayExtractor: Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
