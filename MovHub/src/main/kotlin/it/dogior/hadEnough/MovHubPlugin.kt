package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MovHubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovHub())
        registerExtractorAPI(RapidShareExtractor())
    }
}
