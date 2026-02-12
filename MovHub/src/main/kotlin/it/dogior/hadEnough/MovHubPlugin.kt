package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.extractorApis

@CloudstreamPlugin
class MovHubPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the main provider
        registerMainAPI(MovHub())
        
        // Register the extractor
        registerExtractorAPI(RapidShareExtractor())
    }
}
