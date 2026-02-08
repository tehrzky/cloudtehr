package it.dogior.hadEnough

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovHubPlugin : Plugin() {
    override val name = "MovHub"
    override val author = "YourName"
    override val description = "Watch movies and TV shows from MovHub"
    override val version = "1.0.0"
    
    override fun load(context: Context) {
        // Register the main API with default settings
        registerMainAPI(MovHub("en", true))
        
        // Register the extractor for video links
        registerExtractorAPI(RapidShareExtractor())
    }
}
