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
            val response = app.get(url, referer = referer ?: "", timeout = 60)
            val html = response.text
            
            // Simple pattern matching for m3u8
            val m3u8Pattern = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""", RegexOption.IGNORE_CASE)
            
            m3u8Pattern.findAll(html).forEach { match ->
                val m3u8Url = match.value.trim()
                if (m3u8Url.isNotEmpty()) {
                    println("UniversalExtractor: Found m3u8: $m3u8Url")
                    
                    // Return direct m3u8 link
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
                    return
                }
            }
            
            println("UniversalExtractor: No m3u8 URLs found")
            
        } catch (e: Exception) {
            println("UniversalExtractor: Error: ${e.message}")
        }
    }
}