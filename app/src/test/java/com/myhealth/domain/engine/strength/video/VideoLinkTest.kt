package com.myhealth.domain.engine.strength.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P26.4: video links. Example ids only. */
class VideoLinkTest {

    private val id = "AbCdEfGhIj0"
    private val canonical = "https://www.youtube.com/watch?v=$id"

    @Test
    fun youtube_forms_normalise_to_one_link() {
        listOf(
            "https://youtu.be/$id",
            "https://www.youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id&feature=share",
            "https://youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube.com/watch?list=PL123&v=$id",
            "https://youtube.com/live/$id?si=xyz",
        ).forEach { assertEquals(it, canonical, VideoLink.normalise(it)) }
    }

    @Test
    fun start_time_is_kept() {
        assertEquals("$canonical&t=90", VideoLink.normalise("https://youtu.be/$id?t=90"))
        assertEquals("$canonical&t=1m30s", VideoLink.normalise("https://www.youtube.com/watch?v=$id&t=1m30s"))
    }

    @Test
    fun other_sites_are_kept_as_they_are() {
        val vimeo = "https://vimeo.com/123456789"
        assertEquals(vimeo, VideoLink.normalise(vimeo))
        assertFalse(VideoLink.isYouTube(vimeo))
        assertTrue(VideoLink.isWebLink(vimeo))
    }

    @Test
    fun first_url_out_of_text() {
        assertEquals("https://youtu.be/$id", VideoLink.firstUrl("Watch this: https://youtu.be/$id."))
        assertNull(VideoLink.firstUrl("no link here"))
    }

    @Test
    fun share_takes_subject_as_title() {
        val shared = VideoLink.fromShare("https://youtu.be/$id", "20 min full body")
        assertEquals(canonical, shared.url)
        assertEquals("20 min full body", shared.title)
    }

    @Test
    fun share_without_subject_uses_the_rest_of_the_text() {
        val shared = VideoLink.fromShare("Easy core workout https://youtu.be/$id", null)
        assertEquals(canonical, shared.url)
        assertEquals("Easy core workout", shared.title)
        assertNull(VideoLink.fromShare("https://youtu.be/$id", null).title)
    }

    @Test
    fun web_link_check() {
        assertTrue(VideoLink.isWebLink(canonical))
        assertFalse(VideoLink.isWebLink("youtube"))
        assertFalse(VideoLink.isWebLink(null))
        assertFalse(VideoLink.isWebLink("javascript:alert(1)"))
    }
}
