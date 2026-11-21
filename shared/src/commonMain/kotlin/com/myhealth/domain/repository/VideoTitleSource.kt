package com.myhealth.domain.repository

/**
 * Looks up a video's title once, when a link is entered (P26.4). The only network call of the
 * video feature: it sends just the video link. `null` when offline, for other sites, or on any
 * error — the title field then stays as typed.
 */
fun interface VideoTitleSource {
    suspend fun titleOf(url: String): String?
}
