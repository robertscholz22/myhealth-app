package com.myhealth.domain.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * P28: the relay's connection code, `myhealth-ai:1:` + base64url of `{u: relay URL, k: registration
 * key}` — printed by the relay's deploy, pasted into More › AI planning. Like the share code it is
 * found anywhere in a pasted message.
 */
object AiConnectionCode {

    const val PREFIX: String = "myhealth-ai:1:"
    private const val MIN_KEY = 16

    @Serializable
    private data class Wire(val u: String, val k: String)

    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    fun encode(connection: AiConnection): String {
        val wire = Json.encodeToString(Wire.serializer(), Wire(connection.relayUrl, connection.registrationKey))
        return PREFIX + base64.encode(wire.encodeToByteArray())
    }

    fun decode(text: String): AiConnection? {
        val start = text.indexOf(PREFIX)
        if (start < 0) return null
        val body = text.substring(start + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (body.isEmpty()) return null
        val wire = runCatching { Json.decodeFromString(Wire.serializer(), base64.decode(body).decodeToString()) }.getOrNull()
            ?: return null
        val url = wire.u.trim().trimEnd('/')
        if (!validRelayUrl(url) || wire.k.length < MIN_KEY) return null
        return AiConnection(url, wire.k)
    }

    /**
     * HTTPS only, except plain HTTP to the development machine (`wrangler dev`): localhost,
     * 127.0.0.1, and 10.0.2.2 as the Android emulator sees it.
     */
    fun validRelayUrl(url: String): Boolean {
        val m = Regex("^(https?)://([A-Za-z0-9.-]+)(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$").matchEntire(url) ?: return false
        val (scheme, host) = m.destructured
        return scheme == "https" || host in DEV_HOSTS
    }

    private val DEV_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
}
