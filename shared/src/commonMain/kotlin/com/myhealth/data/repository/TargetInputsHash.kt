package com.myhealth.data.repository

import com.myhealth.domain.util.NumberFormat
import okio.ByteString.Companion.encodeUtf8

/**
 * The `inputsHash` of `nutrition_target_snapshot` (PLAN §2.2.5, P4.12): a SHA-256 over exactly the
 * inputs that can change a day's target — profile version, weight, goal weight and pace, NEAT
 * level, day type, the planned and completed session ids, and the TDEE rung plus its value.
 *
 * Two properties matter and are asserted by `TargetHashTest`:
 * - **stable**: the same inputs always produce the same digest, in this and any future process
 *   (no `hashCode()`, no iteration order, no locale-dependent formatting);
 * - **order-independent**: the field map is canonicalised by sorting on the key, so the caller
 *   cannot change the hash by reordering the fields it passes.
 *
 * okio's SHA-256 (P20.2, was `java.security.MessageDigest`; same digest) and the shared
 * [NumberFormat] keep it identical on both platforms.
 */
object TargetInputsHash {

    /** SHA-256 of the canonical `key=value` rendering of [fields], lower-case hex. */
    fun of(fields: Map<String, String?>): String = sha256(canonicalize(fields))

    /**
     * `key=value` pairs sorted by key and joined with `|` — the string that is actually hashed.
     * Exposed for the tests (and for debugging a spurious recompute), not for storage.
     */
    fun canonicalize(fields: Map<String, String?>): String = fields.entries
        .sortedBy { it.key }
        .joinToString("|") { "${it.key}=${it.value ?: ""}" }

    fun sha256(value: String): String {
        return value.encodeUtf8().sha256().hex()
    }

    /**
     * Renders a `Double` for the hash at a fixed precision so that a re-read of the same stored
     * value can never produce a different digest (and so that 80.0 and 80.000000001 are the same
     * weight as far as a recompute is concerned).
     */
    fun num(value: Double?): String =
        value?.let { NumberFormat.fixed(it, 3) } ?: ""

    /** Ids are sorted so that a different query order never looks like a plan change. */
    fun ids(ids: List<Long>): String = ids.sorted().joinToString(",")
}
