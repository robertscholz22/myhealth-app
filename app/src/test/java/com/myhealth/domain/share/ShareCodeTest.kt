package com.myhealth.domain.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShareCodeTest {

    private val connection = ShareConnection("demo-family-1", "fake-api-key-123", "abcdefghijkmnpqrstuvwxyz")

    @Test
    fun sc01_round_trip() {
        val code = ShareCode.encode(connection)
        assertTrue(code.startsWith(ShareCode.PREFIX))
        assertEquals(connection, ShareCode.decode(code))
    }

    @Test
    fun sc02_found_inside_a_pasted_message() {
        val text = "Join our family library: ${ShareCode.encode(connection)} \nsee you"
        assertEquals(connection, ShareCode.decode(text))
    }

    @Test
    fun sc03_garbage_and_bad_values_are_refused() {
        assertNull(ShareCode.decode("hello"))
        assertNull(ShareCode.decode(ShareCode.PREFIX))
        assertNull(ShareCode.decode(ShareCode.PREFIX + "!!!"))
        assertNull(ShareCode.decode(ShareCode.encode(connection.copy(projectId = "Bad Project"))))
        assertNull(ShareCode.decode(ShareCode.encode(connection.copy(joinCode = "short"))))
        assertNull(ShareCode.decode(ShareCode.encode(connection.copy(apiKey = " "))))
    }

    @Test
    fun sc04_project_ids() {
        assertTrue(ShareCode.validProjectId("my-family-123"))
        assertFalse(ShareCode.validProjectId("abc"))
        assertFalse(ShareCode.validProjectId("Family"))
        assertFalse(ShareCode.validProjectId("ends-with-"))
    }

    @Test
    fun sc05_join_codes_are_long_and_alphanumeric() {
        val code = ShareCode.newJoinCode(Random(1))
        assertEquals(ShareCode.JOIN_CODE_LENGTH, code.length)
        assertTrue(code.all { it.isLetterOrDigit() })
        assertFalse(code == ShareCode.newJoinCode(Random(2)))
    }

    @Test
    fun sc06_rules_embed_the_join_code_and_owner_checks() {
        val rules = ShareRules.text("abcdefghijkmnpqrstuvwxyz")
        assertTrue(rules.contains("request.resource.data.joinCode == 'abcdefghijkmnpqrstuvwxyz'"))
        assertTrue(rules.contains("resource.data.ownerUid == request.auth.uid"))
        assertTrue(rules.contains("\$(request.auth.uid)"))
        assertTrue(rules.startsWith("rules_version = '2';"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sc07_rules_refuse_a_join_code_that_could_break_out_of_the_string() {
        ShareRules.text("abc' || true || '")
    }
}
