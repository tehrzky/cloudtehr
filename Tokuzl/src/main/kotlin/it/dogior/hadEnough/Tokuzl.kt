override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
    println("Tokuzl: Loading links from: $data")
    
    // Check 1: Look for direct m3u8 in scripts first
    val scripts = document.select("script")
    scripts.forEach { script ->
        val content = script.data()
        if (content.contains("m3u8")) {
            println("Tokuzl: Found script with m3u8")
            val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8(?:\?[^"']*)?)["']""")
            m3u8Regex.findAll(content).forEach { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                println("Tokuzl: Found direct m3u8: $m3u8Url")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                    }
                )
                return true
            }
        }
    }
    
    // Check 2: Look for iframes
    val iframes = document.select("iframe")
    println("Tokuzl: Found ${iframes.size} iframes")
    
    iframes.forEachIndexed { index, iframeElement ->
        val iframeSrc = iframeElement.attr("src")
        if (iframeSrc.isNotEmpty()) {
            val iframeUrl = when {
                iframeSrc.startsWith("//") -> "https:$iframeSrc"
                iframeSrc.startsWith("/") -> "$mainUrl$iframeSrc"
                else -> iframeSrc
            }
            println("Tokuzl: Iframe $index URL: $iframeUrl")
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "iframe",
                    url = iframeUrl
                ) {
                    this.referer = data
                }
            )
            return true
        }
    }
    
    println("Tokuzl: No video sources found")
    return false
}
