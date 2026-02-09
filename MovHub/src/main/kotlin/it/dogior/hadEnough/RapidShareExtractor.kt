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
        println("RapidShare DEBUG - Referer: $referer")
        
        try {
            val rapidUrl = url.toHttpUrl()
            val token = rapidUrl.pathSegments.last()
            val subtitleUrl = rapidUrl.queryParameter("sub.info")
            val baseUrl = "${rapidUrl.scheme}://${rapidUrl.host}"
            val mediaUrl = "$baseUrl/media/$token"

            println("RapidShare DEBUG - Media URL: $mediaUrl")

            // Get encrypted response
            val encryptedResponse = app.get(mediaUrl).text
            val encryptedData = parseJson<EncryptedRapidResponse>(encryptedResponse)

            println("RapidShare DEBUG - Encrypted result: ${encryptedData.result}")

            // Decrypt the response
            val decryptionBody = JSONObject().apply {
                put("text", encryptedData.result)
                put("agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
            }.toString()

            val decryptedResponse = app.post(
                "https://enc-dec.app/api/dec-rapid",
                requestBody = decryptionBody.toRequestBody("application/json".toMediaType())
            ).text

            println("RapidShare DEBUG - Decrypted response: $decryptedResponse")

            val rapidDataString = parseJson<SimpleResponse>(decryptedResponse).result
            println("RapidShare DEBUG - Final data: $rapidDataString")
            
            // Handle subtitles if available
            if (subtitleUrl != null) {
                println("RapidShare DEBUG - Fetching subtitles from: $subtitleUrl")
                try {
                    val subResponse = app.get(subtitleUrl, headers = mapOf("Origin" to baseUrl)).text
                    val subtitles = parseJson<List<RapidShareTrack>>(subResponse)
                        .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                    
                    println("RapidShare DEBUG - Found ${subtitles.size} subtitles")
                    subtitles.forEach { 
                        subtitleCallback(SubtitleFile(it.label!!, it.file))
                    }
                } catch (e: Exception) {
                    println("RapidShare DEBUG - Error fetching subtitles: ${e.message}")
                }
            }
            
            // Check if it's a direct URL or JSON object
            if (rapidDataString.startsWith("http")) {
                // Direct M3U8 URL
                println("RapidShare DEBUG - Creating ExtractorLink for: $rapidDataString")
                
                val link = newExtractorLink(
                    source = name,
                    name = name,
                    url = rapidDataString,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = baseUrl
                }
                
                println("RapidShare DEBUG - Link created with referer: $baseUrl")
                callback.invoke(link)
                
            } else {
                // JSON object with sources
                println("RapidShare DEBUG - Parsing JSON sources")
                val rapidResult = parseJson<RapidResult>(rapidDataString)

                println("RapidShare DEBUG - Found ${rapidResult.sources.size} sources")
                println("RapidShare DEBUG - Found ${rapidResult.tracks.size} tracks")

                // Handle subtitles from JSON
                rapidResult.tracks
                    .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                    .forEach { 
                        println("RapidShare DEBUG - Adding subtitle: ${it.label}")
                        subtitleCallback(SubtitleFile(it.label!!, it.file))
                    }

                // Extract video sources
                rapidResult.sources.forEachIndexed { index, source ->
                    println("RapidShare DEBUG - Source $index: ${source.file}")
                    if (source.file.contains(".m3u8")) {
                        val link = newExtractorLink(
                            source = name,
                            name = "$name - Source ${index + 1}",
                            url = source.file,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = baseUrl
                        }
                        callback.invoke(link)
                    }
                }
            }
            
            println("RapidShare DEBUG - Extraction completed")

        } catch (e: Exception) {
            println("RapidShare DEBUG - Error: ${e.message}")
            e.printStackTrace()
        }
    }

    @Serializable
    data class SimpleResponse(
        val result: String
    )

    @Serializable
    data class EncryptedRapidResponse(
        val result: String
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