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
                    val subtitles = parseJson<List<RapidShareTrack>>(subResponse)
                        .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                        .map { SubtitleFile(it.label!!, it.file) }
                    
                    subtitles.forEach(subtitleCallback)
                } catch (e: Exception) {
                    println("RapidShare DEBUG - Error fetching subtitles: ${e.message}")
                }
            }
            
            // Check if it's a direct URL or JSON object
            if (rapidDataString.startsWith("http")) {
                // Direct M3U8 URL
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = rapidDataString,
                        type = ExtractorLinkType.M3U8
                    ) {
                        // Use the original referer passed to getUrl, or baseUrl as fallback
                        this.referer = referer ?: "$baseUrl/"
                    }
                )
                
            } else {
                // JSON object with sources
                val rapidResult = parseJson<RapidResult>(rapidDataString)

                // Handle subtitles from JSON
                rapidResult.tracks
                    .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                    .forEach { subtitleCallback(SubtitleFile(it.label!!, it.file)) }

                // Extract video sources
                rapidResult.sources.forEach { source ->
                    if (source.file.contains(".m3u8")) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = source.file,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = referer ?: "$baseUrl/"
                            }
                        )
                    }
                }
            }

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