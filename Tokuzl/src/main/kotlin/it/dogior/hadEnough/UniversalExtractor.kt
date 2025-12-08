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
        println("UniversalExtractor: Processing URL: $url")
        
        try {
            val document = app.get(url, referer = referer ?: "", timeout = 30).document
            
            // Look for iframes first (most common)
            val iframes = document.select("iframe")
            println("UniversalExtractor: Found ${iframes.size} iframes")
            
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotEmpty() && (src.contains("video") || src.contains("player"))) {
                    val iframeUrl = fixUrl(src, url)
                    println("UniversalExtractor: Found iframe: $iframeUrl")
                    
                    // Extract from iframe
                    extractFromPage(iframeUrl, url, subtitleCallback, callback)
                    return
                }
            }
            
            // If no iframes found, extract from current page
            extractFromPage(url, url, subtitleCallback, callback)
            
        } catch (e: Exception) {
            println("UniversalExtractor: Error: ${e.message}")
        }
    }
    
    private suspend fun extractFromPage(
        pageUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(pageUrl, referer = referer, timeout = 30)
            val html = response.text
            
            // Look for m3u8 URLs
            val m3u8Patterns = listOf(
                Regex("""src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""data-src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""file["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in m3u8Patterns) {
                pattern.findAll(html).forEach { match ->
                    val m3u8Url = if (match.groupValues.size > 1) {
                        match.groupValues[1]
                    } else {
                        match.value
                    }.trim()
                    
                    if (m3u8Url.isNotEmpty() && m3u8Url.contains(".m3u8", ignoreCase = true)) {
                        println("UniversalExtractor: Found m3u8: $m3u8Url")
                        
                        try {
                            // Return m3u8 link - Cloudstream will handle it
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "Stream",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = pageUrl
                                }
                            )
                            return
                        } catch (e: Exception) {
                            println("UniversalExtractor: Error creating link: ${e.message}")
                        }
                    }
                }
            }
            
            // Look for MP4 or other direct video URLs
            val videoPatterns = listOf(
                Regex("""src=["']([^"']+\.(?:mp4|mkv|webm|avi))["']""", RegexOption.IGNORE_CASE),
                Regex("""data-src=["']([^"']+\.(?:mp4|mkv|webm|avi))["']""", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in videoPatterns) {
                pattern.findAll(html).forEach { match ->
                    val videoUrl = if (match.groupValues.size > 1) {
                        match.groupValues[1]
                    } else {
                        match.value
                    }.trim()
                    
                    if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                        println("UniversalExtractor: Found video: $videoUrl")
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Video",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = pageUrl
                            }
                        )
                        return
                    }
                }
            }
            
            println("UniversalExtractor: No video URLs found on page")
            
        } catch (e: Exception) {
            println("UniversalExtractor: Error extracting from page: ${e.message}")
        }
    }
    
    private fun fixUrl(url: String, base: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val baseDomain = base.substringAfter("://").substringBefore("/")
                "https://$baseDomain$url"
            }
            else -> {
                val baseUrl = base.substringBeforeLast("/")
                "$baseUrl/$url"
            }
        }
    }
}