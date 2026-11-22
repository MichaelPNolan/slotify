package com.slotify.app.youtube

/** Android: yt-dlp has no practical runtime here — feature is desktop-first. */
actual fun createYoutubeSource(): YoutubeSource = UnsupportedYoutubeSource

object UnsupportedYoutubeSource : YoutubeSource {
    override val isSupported: Boolean = false

    override suspend fun search(query: String, max: Int): List<YoutubeSearchResult> =
        throw UnsupportedOperationException("YouTube fetch is desktop-only for now")

    override suspend fun download(
        result: YoutubeSearchResult,
        targetDir: String,
        onProgress: (String) -> Unit,
    ): String = throw UnsupportedOperationException("YouTube fetch is desktop-only for now")
}
