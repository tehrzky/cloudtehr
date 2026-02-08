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
    override val mainUrl = "https://movhub.to"
    override val name = "RapidShare"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val rapidUrl = url.toHttpUrl()
            val token = rapidUrl.pathSegments.last()
            val subtitleUrl = rapidUrl.queryParameter("sub.list")
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

            val rapidResult = parseJson<RapidDecryptResponse>(decryptedResponse).result

            // Handle subtitles
            val subtitles = if (subtitleUrl != null) {
                try {
                    val subResponse = app.get(subtitleUrl, headers = mapOf("Origin" to baseUrl)).text
                    parseJson<List<RapidShareTrack>>(subResponse)
                        .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                        .map { SubtitleFile(it.label!!, it.file) }
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                rapidResult.tracks
                    .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                    .map { SubtitleFile(it.label!!, it.file) }
            }

            subtitles.forEach(subtitleCallback)

            // Extract video sources
            rapidResult.sources.forEach { source ->
                val videoUrl = source.file
                if (videoUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        videoUrl,
                        referer = "$baseUrl/",
                        headers = mapOf("Origin" to baseUrl)
                    ).forEach(callback)
                }
            }

        } catch (e: Exception) {
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
