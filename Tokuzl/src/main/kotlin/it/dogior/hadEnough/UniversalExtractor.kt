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
        println("UniversalExtractor: Referer: $referer")
        
        try {
            // Try to get the page content
            val response = app.get(url, referer = referer ?: "", timeout = 60)
            val html = response.text
            
            // Look for m3u8 URLs first
            val m3u8Patterns = listOf(
                Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""", RegexOption.IGNORE_CASE),
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""file["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            )
            
            var foundM3u8 = false
            
            for (pattern in m3u8Patterns) {
                pattern.findAll(html).forEach { match ->
                    var m3u8Url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                    m3u8Url = m3u8Url.trim()
                    
                    if (m3u8Url.isNotEmpty() && m3u8Url.contains(".m3u8")) {
                        println("UniversalExtractor: Found m3u8: $m3u8Url")
                        
                        try {
                            M3u8Helper.generateM3u8(
                                name,
                                m3u8Url,
                                url,
                                headers = mapOf(
                                    "Referer" to referer ?: url,
                                    "User-Agent" to "Mozilla/5.0"
                                ),
                                subtitleCallback = subtitleCallback
                            ).forEach(callback)
                            foundM3u8 = true
                        } catch (e: Exception) {
                            println("UniversalExtractor: M3u8Helper failed, using direct link: ${e.message}")
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "Stream",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = referer ?: url
                                }
                            )
                            foundM3u8 = true
                        }
                    }
                }
                
                if (foundM3u8) return
            }
            
            // If no m3u8 found, look for other video formats
            val videoPatterns = listOf(
                Regex("""<source[^>]+src=["']([^"']+\.(?:mp4|mkv|webm|avi))["']""", RegexOption.IGNORE_CASE),
                Regex("""data-src=["']([^"']+\.(?:mp4|mkv|webm|avi))["']""", RegexOption.IGNORE_CASE),
                Regex("""(https?://[^\s"']+\.(?:mp4|mkv|webm|avi)[^\s"']*)""", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in videoPatterns) {
                pattern.findAll(html).forEach { match ->
                    var videoUrl = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                    videoUrl = videoUrl.trim()
                    
                    if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                        println("UniversalExtractor: Found video: $videoUrl")
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Video",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer ?: url
                            }
                        )
                        return
                    }
                }
            }
            
            println("UniversalExtractor: No video URLs found in HTML")
            
        } catch (e: Exception) {
            println("UniversalExtractor: Error: ${e.message}")
            
            // If page fetch fails, try to use the URL directly if it looks like a video
            if (url.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Direct Stream",
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: mainUrl
                    }
                )
            }
        }
    }
}