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
    override val mainUrl = "https://rapid.yuzuha.xyz"
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
            val subtitleUrl = rapidUrl.queryParameter("sub.info")
            val baseUrl = "${rapidUrl.scheme}://${rapidUrl.host}"
            val mediaUrl = "$baseUrl/media/$token"

            // Get encrypted response
            val encryptedResponse = app.get(mediaUrl).text
            val encryptedData = parseJson<EncryptedRapidResponse>(encryptedResponse)

            // Decrypt the response
            val decryptionBody = JSONObject().apply {
                put("text", encryptedData.result)
                put("agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
            }.toString()

            val decryptedResponse = app.post(
                "https://enc-dec.app/api/dec-rapid",
                requestBody = decryptionBody.toRequestBody("application/json".toMediaType())
            ).text

            val rapidDataString = parseJson<SimpleResponse>(decryptedResponse).result
            println("RapidShare DEBUG - M3U8 URL: $rapidDataString")
            
            // Handle subtitles if available
            if (subtitleUrl != null) {
                try {
                    val subResponse = app.get(subtitleUrl, headers = mapOf("Origin" to baseUrl)).text
                    parseJson<List<RapidShareTrack>>(subResponse)
                        .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                        .forEach { subtitleCallback(SubtitleFile(it.label!!, it.file)) }
                    println("RapidShare DEBUG - Subtitles added")
                } catch (e: Exception) {
                    println("RapidShare DEBUG - Subtitle error: ${e.message}")
                }
            }
            
            // Create direct M3U8 link without using M3u8Helper
            if (rapidDataString.startsWith("http")) {
                println("RapidShare DEBUG - Creating direct M3U8 link")
                
                val link = newExtractorLink(
                    source = name,
                    name = name,
                    url = rapidDataString,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = baseUrl
                }
                
                println("RapidShare DEBUG - Link created: ${link.url}")
                callback(link)
                println("RapidShare DEBUG - Link sent to callback")
                
            } else {
                // Fallback: try parsing as JSON
                println("RapidShare DEBUG - Trying JSON parsing")
                try {
                    val rapidResult = parseJson<RapidResult>(rapidDataString)
                    
                    rapidResult.tracks
                        .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                        .forEach { subtitleCallback(SubtitleFile(it.label!!, it.file)) }

                    rapidResult.sources
                        .filter { it.file.contains(".m3u8") }
                        .forEach { source ->
                            val link = newExtractorLink(
                                source = name,
                                name = name,
                                url = source.file,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = baseUrl
                            }
                            callback(link)
                        }
                } catch (e: Exception) {
                    println("RapidShare DEBUG - JSON parse failed: ${e.message}")
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