package com.myhealth.domain.share

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** P27: what a family member needs to join a library. */
data class ShareConnection(val projectId: String, val apiKey: String, val joinCode: String)

/**
 * P27: the connection code sent to family members as text:
 * `myhealth-share:1:` + base64url of `{"p":…,"k":…,"j":…}`.
 */
@OptIn(ExperimentalEncodingApi::class)
object ShareCode {

    const val PREFIX: String = "myhealth-share:1:"

    private const val JOIN_CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    @Serializable
    private data class Wire(val p: String, val k: String, val j: String)

    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    fun encode(connection: ShareConnection): String {
        val wire = Json.encodeToString(Wire.serializer(), Wire(connection.projectId, connection.apiKey, connection.joinCode))
        return PREFIX + base64.encode(wire.encodeToByteArray())
    }

    /** Finds the code anywhere in [text] (a pasted message), or null. */
    fun decode(text: String): ShareConnection? {
        val start = text.indexOf(PREFIX)
        if (start < 0) return null
        val body = text.substring(start + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (body.isEmpty()) return null
        val wire = runCatching {
            Json.decodeFromString(Wire.serializer(), base64.decode(body).decodeToString())
        }.getOrNull() ?: return null
        if (!validProjectId(wire.p) || wire.k.isBlank() || wire.j.length < MIN_JOIN_CODE) return null
        return ShareConnection(wire.p, wire.k.trim(), wire.j)
    }

    /** Firebase project ids: 6–30 characters, lower-case letters, digits and hyphens. */
    fun validProjectId(id: String): Boolean = Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$").matches(id)

    /** A new join code: 24 characters without look-alikes (about 139 bits). */
    fun newJoinCode(random: Random = Random.Default): String =
        (1..JOIN_CODE_LENGTH).map { JOIN_CHARS[random.nextInt(JOIN_CHARS.length)] }.joinToString("")

    const val JOIN_CODE_LENGTH: Int = 24
    private const val MIN_JOIN_CODE = 16
}
