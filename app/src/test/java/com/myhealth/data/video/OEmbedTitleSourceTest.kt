package com.myhealth.data.video

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P26.4: the oEmbed answer's title, and no request for other sites. */
class OEmbedTitleSourceTest {

    @Test
    fun parses_the_title_and_ignores_the_rest() {
        assertEquals("Example workout", OEmbedTitleSource.parse("""{"title":" Example workout ","author_name":"x","width":200}"""))
        assertNull(OEmbedTitleSource.parse("""{"author_name":"x"}"""))
        assertNull(OEmbedTitleSource.parse("not json"))
    }

    @Test
    fun other_sites_are_not_asked() = runTest {
        val source = OEmbedTitleSource(client = { error("no request expected") })
        assertNull(source.titleOf("https://vimeo.com/1"))
    }
}
