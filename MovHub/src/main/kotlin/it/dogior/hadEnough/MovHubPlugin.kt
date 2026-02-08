package it.dogior.hadEnough

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovHubPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the main API with default settings
        registerMainAPI(MovHub("en", true))
        
        // Register the extractor for video links
        registerExtractorAPI(RapidShareExtractor())
    }
}
