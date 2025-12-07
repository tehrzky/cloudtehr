override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
    
    // First check for direct m3u8 in scripts
    val scripts = document.select("script")
    for (script in scripts) {
        val content = script.data()
        if (content.contains("m3u8")) {
            val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8(?:\?[^"']*)?)["']""")
            m3u8Regex.findAll(content).forEach { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                
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
    
    // Look for iframes
    val iframes = document.select("iframe")
    
    for (iframe in iframes) {
        val iframeSrc = iframe.attr("src")
        if (iframeSrc.isNotEmpty()) {
            val iframeUrl = when {
                iframeSrc.startsWith("//") -> "https:$iframeSrc"
                iframeSrc.startsWith("/") -> "$mainUrl$iframeSrc"
                else -> iframeSrc
            }
            
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
    
    return false
}