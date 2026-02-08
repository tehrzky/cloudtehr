package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class RapidShareExtractor : ExtractorApi() {
    override val mainUrl = "https://movhub.ws"
    override val name = "RapidShare"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("RapidShare DEBUG - Starting extraction for URL: $url")
        
        try {
            val rapidUrl = url.toHttpUrl()
            val token = rapidUrl.pathSegments.last()
            val subtitleUrl = rapidUrl.queryParameter("sub.list")
            val baseUrl = "${rapidUrl.scheme}://${rapidUrl.host}"
            val mediaUrl = "$baseUrl/media/$token"

            println("RapidShare DEBUG - Token: $token")
            println("RapidShare DEBUG - Base URL: $baseUrl")
            println("RapidShare DEBUG - Media URL: $mediaUrl")

            // Get encrypted response
            val encryptedResponse = app.get(mediaUrl).text
            println("RapidShare DEBUG - Encrypted response: $encryptedResponse")
            
            val encryptedData = parseJson<EncryptedRapidResponse>(encryptedResponse)
            println("RapidShare DEBUG - Encrypted data result: ${encryptedData.result}")

            // Decrypt the response
            val decryptionBody = JSONObject().apply {
                put("text", encryptedData.result)
                put("agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
            }.toString()

            println("RapidShare DEBUG - Decryption body: $decryptionBody")

            val decryptedResponse = app.post(
                "https://enc-dec.app/api/dec-rapid",
                requestBody = decryptionBody.toRequestBody("application/json".toMediaType())
            ).text

            println("RapidShare DEBUG - Decrypted response: $decryptedResponse")

            val rapidResult = parseJson<RapidDecryptResponse>(decryptedResponse).result

            println("RapidShare DEBUG - Found ${rapidResult.sources.size} sources")
            println("RapidShare DEBUG - Found ${rapidResult.tracks.size} tracks")

            // Handle subtitles
            val subtitles = if (subtitleUrl != null) {
                println("RapidShare DEBUG - Fetching subtitles from: $subtitleUrl")
                try {
                    val subResponse = app.get(subtitleUrl, headers = mapOf("Origin" to baseUrl)).text
                    parseJson<List<RapidShareTrack>>(subResponse)
                        .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                        .map { SubtitleFile(it.label!!, it.file) }
                } catch (e: Exception) {
                    println("RapidShare DEBUG - Error fetching subtitles: ${e.message}")
                    emptyList()
                }
            } else {
                rapidResult.tracks
                    .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                    .map { SubtitleFile(it.label!!, it.file) }
            }

            println("RapidShare DEBUG - Found ${subtitles.size} subtitles")
            subtitles.forEach(subtitleCallback)

            // Extract video sources
            rapidResult.sources.forEach { source ->
                val videoUrl = source.file
                println("RapidShare DEBUG - Processing source: $videoUrl")
                
                if (videoUrl.contains(".m3u8")) {
                    val m3u8Links = M3u8Helper.generateM3u8(
                        name,
                        videoUrl,
                        referer = "$baseUrl/",
                        headers = mapOf("Origin" to baseUrl)
                    )
                    println("RapidShare DEBUG - Generated ${m3u8Links.size} M3U8 links")
                    m3u8Links.forEach(callback)
                }
            }

        } catch (e: Exception) {
            println("RapidShare DEBUG - Error: ${e.message}")
            e.printStackTrace()
        }
    }

    @Serializable
    data class EncryptedRapidResponse(
        val result: String
    )

    @Serializable
    data class RapidDecryptResponse(
        val result: RapidResult
    )

    @Serializable
    data class RapidResult(
        val sources: List<RapidSource>,
        val tracks: List<RapidShareTrack>
    )

    @Serializable
    data class RapidSource(
        val file: String
    )

    @Serializable
    data class RapidShareTrack(
        val file: String,
        val label: String? = null,
        val kind: String? = null
    )
}