package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class RapidShareExtractor : ExtractorApi() {
    override val mainUrl = "movhub.to"
    override val name = "RapidShare"
    override val requiresReferer = false

    private val jsonMimeType = "application/json".toMediaType()
    private val rapidHeaders = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Connection" to "keep-alive",
        "DNT" to "1",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    )

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
            val encryptedResponse = app.get(
                mediaUrl,
                headers = rapidHeaders.plus(
                    "Referer" to "$baseUrl/",
                    "Origin" to baseUrl
                )
            )

            if (!encryptedResponse.isSuccessful) {
                throw Exception("Failed to fetch encrypted response: ${encryptedResponse.code}")
            }

            val encryptedResult = encryptedResponse.parsed<EncryptedRapidResponse>().result

            // Decrypt the response
            val decryptionBody = """
                {
                    "text": "$encryptedResult",
                    "agent": "${rapidHeaders["User-Agent"] ?: ""}"
                }
            """.trimIndent().toRequestBody(jsonMimeType)

            val decryptResponse = app.post(
                "https://enc-dec.app/api/dec-rapid",
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "Origin" to "https://enc-dec.app",
                    "Referer" to "https://enc-dec.app/",
                    "User-Agent" to rapidHeaders["User-Agent"] ?: ""
                ),
                requestBody = decryptionBody
            )

            if (!decryptResponse.isSuccessful) {
                throw Exception("Failed to decrypt: ${decryptResponse.code}")
            }

            val rapidResult = decryptResponse.parsed<RapidDecryptResponse>().result

            // Process subtitles
            val subtitleList = if (subtitleUrl != null) {
                getSubtitles(subtitleUrl, baseUrl)
            } else {
                rapidResult.tracks
                    .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                    .map { SubtitleFile(it.file, it.label!!) }
            }

            // Send subtitles to callback
            subtitleList.forEach(subtitleCallback)

            // Process video sources
            val videoSources = rapidResult.sources.sortedByDescending { it.size }
            
            videoSources.forEach { source ->
                val videoUrl = source.file
                when {
                    videoUrl.contains(".m3u8") -> {
                        // For M3U8 streams
                        M3u8Helper.generateM3u8(
                            name,
                            videoUrl,
                            referer = "$baseUrl/"
                        ).forEach(callback)
                    }
                    videoUrl.isNotBlank() -> {
                        // For direct video links (if any)
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "${name} - ${source.size}p",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO,
                                quality = source.size,
                                referer = "$baseUrl/"
                            )
                        )
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    private suspend fun getSubtitles(url: String, baseUrl: String): List<SubtitleFile> {
        return try {
            val subHeaders = mapOf(
                "Accept" to "*/*",
                "Origin" to baseUrl,
                "Referer" to "$baseUrl/"
            )

            app.get(url, headers = subHeaders)
                .parsed<List<RapidShareTrack>>()
                .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                .map { SubtitleFile(it.file, it.label!!) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

// Data classes for JSON parsing
private data class EncryptedRapidResponse(
    val result: String
)

private data class RapidDecryptResponse(
    val result: RapidResult
)

private data class RapidResult(
    val sources: List<RapidSource>,
    val tracks: List<RapidShareTrack>
)

private data class RapidSource(
    val file: String,
    val size: Int,
    val type: String?
)

private data class RapidShareTrack(
    val file: String,
    val kind: String,
    val label: String?
)
