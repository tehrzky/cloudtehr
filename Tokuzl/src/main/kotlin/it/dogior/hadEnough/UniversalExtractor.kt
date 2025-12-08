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
        println("=== UNIVERSAL EXTRACTOR: Processing '$url' ===")
        
        try {
            // Just return a test stream for now
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Test Stream",
                    url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer ?: url
                }
            )
        } catch (e: Exception) {
            println("=== UNIVERSAL EXTRACTOR ERROR: ${e.message} ===")
        }
    }
}