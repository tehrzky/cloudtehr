    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Debug: Print all iframes found
        val iframes = document.select("iframe")
        println("Tokuzl: Found ${iframes.size} iframes")
        
        if (iframes.isEmpty()) {
            // No iframe found, try to find script with video source
            val scripts = document.select("script")
            scripts.forEach { script ->
                val content = script.data()
                if (content.contains("m3u8") || content.contains("p2pplay") || content.contains("video")) {
                    println("Tokuzl: Found potential video script")
                    
                    // Extract any m3u8 URLs
                    val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                    m3u8Regex.findAll(content).forEach { match ->
                        val m3u8Url = match.groupValues[1].replace("\\/", "/")
                        println("Tokuzl: Found m3u8: $m3u8Url")
                        
                        try {
                            M3u8Helper.generateM3u8(
                                name,
                                m3u8Url,
                                data
                            ).forEach(callback)
                        } catch (e: Exception) {
                            callback.invoke(
                                ExtractorLink(
                                    name,
                                    name,
                                    m3u8Url,
                                    data,
                                    Qualities.Unknown.value
                                ).apply {
                                    this.isM3u8 = true
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // Process each iframe
            iframes.forEach { iframeElement ->
                val iframeSrc = iframeElement.attr("src")
                println("Tokuzl: Processing iframe: $iframeSrc")
                
                if (iframeSrc.isNotEmpty()) {
                    val iframeUrl = when {
                        iframeSrc.startsWith("//") -> "https:$iframeSrc"
                        iframeSrc.startsWith("/") -> "$mainUrl$iframeSrc"
                        else -> iframeSrc
                    }
                    
                    println("Tokuzl: Full iframe URL: $iframeUrl")
                    
                    // Use P2PPlay extractor for p2pplay domains
                    if (iframeUrl.contains("p2pplay", ignoreCase = true)) {
                        println("Tokuzl: Using P2PPlay extractor")
                        P2PPlayExtractor().getUrl(
                            url = iframeUrl,
                            referer = data,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    } else {
                        // Generic iframe handler
                        try {
                            val iframeDoc = app.get(iframeUrl, referer = data)
                            val iframeHtml = iframeDoc.text
                            
                            val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                            m3u8Regex.findAll(iframeHtml).forEach { match ->
                                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                                println("Tokuzl: Found m3u8 in iframe: $m3u8Url")
                                
                                M3u8Helper.generateM3u8(
                                    name,
                                    m3u8Url,
                                    iframeUrl
                                ).forEach(callback)
                            }
                        } catch (e: Exception) {
                            println("Tokuzl: Error processing iframe: ${e.message}")
                        }
                    }
                }
            }
        }
        
        return true
    }
