package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovHubPlugin : Plugin() {
    private val sharedPref = activity?.getSharedPreferences("MovHub", Context.MODE_PRIVATE)
    
    override fun load(context: Context) {
        val lang = sharedPref?.getString("lang", "en") ?: "en"
        val showLogo = sharedPref?.getBoolean("show_logo", true) ?: true
        
        // Register the main API (provider)
        registerMainAPI(MovHub(lang, showLogo))
        
        // Register the extractor for video links
        registerExtractorAPI(RapidShareExtractor())
        
        // Optional: Register settings if needed
        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = MovHubSettingsFragment(this, sharedPref)
            frag.show(activity.supportFragmentManager, "MovHubSettings")
        }
    }
}
