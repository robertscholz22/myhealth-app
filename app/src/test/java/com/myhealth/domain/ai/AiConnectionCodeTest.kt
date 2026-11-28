package com.myhealth.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConnectionCodeTest {

    private val connection = AiConnection("https://relay.example.workers.dev", "reg-key-0123456789abcdef")

    @Test
    fun ac01_round_trip() {
        val code = AiConnectionCode.encode(connection)
        assertTrue(code.startsWith(AiConnectionCode.PREFIX))
        assertEquals(connection, AiConnectionCode.decode(code))
    }

    @Test
    fun ac02_found_inside_a_pasted_message_and_trailing_slash_dropped() {
        val code = AiConnectionCode.encode(connection.copy(relayUrl = "https://relay.example.workers.dev/"))
        assertEquals(connection, AiConnectionCode.decode("Here is the code:\n$code.\nThanks"))
    }

    @Test
    fun ac03_garbage_short_keys_and_share_codes_are_refused() {
        assertNull(AiConnectionCode.decode("hello"))
        assertNull(AiConnectionCode.decode(AiConnectionCode.PREFIX))
        assertNull(AiConnectionCode.decode(AiConnectionCode.PREFIX + "!!!"))
        assertNull(AiConnectionCode.decode(AiConnectionCode.encode(connection.copy(registrationKey = "short"))))
        assertNull(AiConnectionCode.decode("myhealth-share:1:eyJwIjoieCJ9"))
    }

    @Test
    fun ac04_http_only_to_the_development_machine() {
        assertTrue(AiConnectionCode.validRelayUrl("http://10.0.2.2:8787"))
        assertTrue(AiConnectionCode.validRelayUrl("http://localhost:8787"))
        assertTrue(AiConnectionCode.validRelayUrl("https://relay.example.com/base"))
        assertFalse(AiConnectionCode.validRelayUrl("http://relay.example.com"))
        assertFalse(AiConnectionCode.validRelayUrl("ftp://relay.example.com"))
        assertFalse(AiConnectionCode.validRelayUrl("https://relay.example.com?x=1"))
        assertNull(AiConnectionCode.decode(AiConnectionCode.encode(connection.copy(relayUrl = "http://evil.example.com"))))
    }

    @Test
    fun ac05_data_groups_always_include_basics() {
        assertEquals(setOf(AiDataGroup.BASICS), AiDataGroup.parse(emptyList()))
        assertEquals(setOf(AiDataGroup.BASICS, AiDataGroup.CYCLE), AiDataGroup.parse(listOf("CYCLE", "NOPE")))
    }

    /** The relay's `scripts/connection-code.mjs` output decodes here (fake URL and key). */
    @Test
    fun ac07_a_code_printed_by_the_relay_script_decodes() {
        assertEquals(connection, AiConnectionCode.decode("myhealth-ai:1:eyJ1IjoiaHR0cHM6Ly9yZWxheS5leGFtcGxlLndvcmtlcnMuZGV2IiwiayI6InJlZy1rZXktMDEyMzQ1Njc4OWFiY2RlZiJ9"))
    }

    @Test
    fun ac06_snapshot_ids_round_trip() {
        assertEquals(12L, AiIds.parseWorkout(AiIds.workout(12)))
        assertEquals(34L, AiIds.parseSession(AiIds.session(34)))
        assertNull(AiIds.parseWorkout("s12"))
        assertNull(AiIds.parseSession("ai-1"))
        assertTrue(AiIds.isAi("ai-1"))
    }
}
