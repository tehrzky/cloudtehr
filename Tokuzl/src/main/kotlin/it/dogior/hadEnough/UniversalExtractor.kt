package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
            
            // Strategy 1: Look for iframes
            val iframes = document.select("iframe")
            if (iframes.isNotEmpty()) {
                iframes.forEach { iframe ->
                    val src = iframe.attr("src").trim()
                    if (src.isNotEmpty()) {
                        val iframeUrl = when {
                            src.startsWith("http") -> src
                            src.startsWith("//") -> "https:$src"
                            src.startsWith("/") -> "$mainUrl$src"
                            else -> src
                        }
                        
                        println("UniversalExtractor: Found iframe: $iframeUrl")
                        extractVideoFromPage(iframeUrl, url, callback)
                        return
                    }
                }
            }
            
            // Strategy 2: Extract from current page
            extractVideoFromPage(url, url, callback)
            
        } catch (e: Exception) {
            println("UniversalExtractor: Error: ${e.message}")
        }
    }
    
    private suspend fun extractVideoFromPage(
        pageUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(pageUrl, referer = referer, timeout = 30)
            val html = response.text
            
            // Look for m3u8 URLs
            val m3u8Pattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""", RegexOption.IGNORE_CASE)
            m3u8Pattern.findAll(html).forEach { match ->
                val m3u8Url = match.value.trim()
                if (m3u8Url.isNotEmpty()) {
                    println("UniversalExtractor: Found m3u8: $m3u8Url")
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
                }
            }
            
            // Look for MP4 URLs
            val mp4Pattern = Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""", RegexOption.IGNORE_CASE)
            mp4Pattern.findAll(html).forEach { match ->
                val mp4Url = match.value.trim()
                if (mp4Url.isNotEmpty()) {
                    println("UniversalExtractor: Found mp4: $mp4Url")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Video",
                            url = mp4Url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = pageUrl
                        }
                    )
                    return
                }
            }
            
            println("UniversalExtractor: No video URLs found")
            
        } catch (e: Exception) {
            println("UniversalExtractor: Error extracting video: ${e.message}")
        }
    }
}