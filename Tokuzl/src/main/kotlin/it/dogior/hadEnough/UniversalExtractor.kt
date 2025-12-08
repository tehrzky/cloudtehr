package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink

class UniversalExtractor : ExtractorApi() {
    override val name = "UniversalExtractor"
    override val mainUrl = "https://tokuzl.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("UniversalExtractor: Starting for URL: $url")
        println("UniversalExtractor: Referer: $referer")
        
        // Remove hash fragment from URL if present
        val cleanUrl = if (url.contains("#")) {
            url.substringBefore("#")
        } else {
            url
        }
        
        println("UniversalExtractor: Clean URL (no hash): $cleanUrl")
        
        try {
            // Fetch the page with proper headers
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Referer" to (referer ?: ""),
                "DNT" to "1",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1"
            )
            
            val response = app.get(cleanUrl, referer = referer ?: "", headers = headers)
            val html = response.text
            println("UniversalExtractor: Got HTML, length: ${html.length}")
            
            // Debug: Show first 500 chars of HTML
            if (html.length > 500) {
                println("UniversalExtractor: HTML preview (first 500 chars): ${html.substring(0, 500)}")
            }
            
            // Look for m3u8 URLs in the HTML
            val m3u8Patterns = listOf(
                // Direct m3u8 URL
                Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""", RegexOption.IGNORE_CASE),
                // In quotes
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                // In data attributes
                Regex("""data-(?:src|file)\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                // In script variables
                Regex("""(?:src|file|url)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                // Base URL + path
                Regex("""["'](/[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            )
            
            val foundUrls = mutableSetOf<String>()
            
            m3u8Patterns.forEachIndexed { i, pattern ->
                val matches = pattern.findAll(html).toList()
                if (matches.isNotEmpty()) {
                    println("UniversalExtractor: Pattern $i found ${matches.size} matches")
                    matches.forEach { match ->
                        var videoUrl = if (match.groupValues.size > 1) {
                            match.groupValues[1]
                        } else {
                            match.value
                        }
                        
                        // Clean the URL
                        videoUrl = videoUrl
                            .replace("\\/", "/")
                            .replace("\\", "")
                            .replace("\"", "")
                            .replace("'", "")
                            .replace(" ", "")
                            .trim()
                        
                        // Convert relative URLs to absolute
                        if (videoUrl.startsWith("/") && videoUrl.contains(".m3u8")) {
                            val baseDomain = cleanUrl.substringAfter("://").substringBefore("/")
                            videoUrl = "https://$baseDomain$videoUrl"
                            println("UniversalExtractor: Converted relative URL to absolute: $videoUrl")
                        }
                        
                        if (videoUrl.contains(".m3u8", ignoreCase = true) && 
                            videoUrl.startsWith("http") && 
                            !foundUrls.contains(videoUrl)) {
                            println("UniversalExtractor: Found valid m3u8 URL: $videoUrl")
                            foundUrls.add(videoUrl)
                        }
                    }
                }
            }
            
            println("UniversalExtractor: Total unique m3u8 URLs found: ${foundUrls.size}")
            
            if (foundUrls.isEmpty()) {
                println("UniversalExtractor: No m3u8 URLs found, trying alternative patterns...")
                
                // Try more general patterns
                val generalPatterns = listOf(
                    Regex("""["'](https?://[^"']+/[^"']+)["']"""),
                    Regex("""(https?://[^\s"']+/[^\s"']+)""")
                )
                
                generalPatterns.forEach { pattern ->
                    val matches = pattern.findAll(html).toList()
                    println("UniversalExtractor: General pattern found ${matches.size} URLs")
                    matches.take(5).forEach { match ->
                        val generalUrl = match.value
                            .replace("\\/", "/")
                            .replace("\"", "")
                            .replace("'", "")
                            .trim()
                        println("UniversalExtractor: General URL: $generalUrl")
                    }
                }
            }
            
            // Process found m3u8 URLs
            foundUrls.forEach { m3u8Url ->
                println("UniversalExtractor: Processing m3u8 URL: $m3u8Url")
                try {
                    // Try to generate M3U8 links
                    val m3u8Headers = mapOf(
                        "Origin" to getOrigin(cleanUrl),
                        "Referer" to cleanUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                    
                    val links = M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        cleanUrl,
                        headers = m3u8Headers
                    )
                    println("UniversalExtractor: M3u8Helper generated ${links.size} links")
                    links.forEach(callback)
                } catch (e: Exception) {
                    println("UniversalExtractor: M3u8Helper failed: ${e.message}")
                    // Fallback: direct link
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = cleanUrl
                            this.headers = mapOf(
                                "Origin" to getOrigin(cleanUrl),
                                "Referer" to cleanUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                }
            }
            
            if (foundUrls.isEmpty()) {
                println("UniversalExtractor: No video URLs found at all")
            }
            
        } catch (e: Exception) {
            println("UniversalExtractor: Error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun getOrigin(url: String): String {
        return try {
            val protocol = if (url.startsWith("https")) "https://" else "http://"
            val domain = url.substringAfter("://").substringBefore("/")
            "$protocol$domain"
        } catch (e: Exception) {
            "https://tokuzl.net"
        }
    }
}