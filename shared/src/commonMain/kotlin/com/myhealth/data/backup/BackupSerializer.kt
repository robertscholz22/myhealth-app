package com.myhealth.data.backup

import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.json.okio.encodeToBufferedSink
import okio.Sink
import okio.Source
import okio.buffer
import okio.use

/**
 * JSON codec for [BackupFile] (PLAN P8.4).
 *
 * - `prettyPrint = false`: a backup is machine-read, and a year of streams is large enough that
 *   indentation costs real megabytes.
 * - `ignoreUnknownKeys = true`: a backup taken by a *later* build that added a table must still be
 *   readable once its schema version is acceptable, and a hand-edited file with a stray key must
 *   not fail wholesale.
 * - `encodeDefaults = true`: an entity's defaulted columns are written out, so the file is a full
 *   record rather than a diff against whatever the code's defaults happen to be next year.
 * - Both directions stream: the document is never held in memory as a `String`.
 */
object BackupSerializer {

    val json: Json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun encode(file: BackupFile, out: Sink) {
        out.buffer().use { sink -> json.encodeToBufferedSink(file, sink) }
    }

    /** For tests and small payloads; the app always uses the streaming overloads. */
    fun encodeToString(file: BackupFile): String = json.encodeToString(file)

    /**
     * Reads a backup, rejecting one taken by a newer schema than this build can represent.
     * Anything unparseable comes back as [AppError.Parse] rather than an exception.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun decode(input: Source): Outcome<BackupFile> =
        decodeCatching { input.buffer().use { json.decodeFromBufferedSource<BackupFile>(it) } }

    fun decodeFromString(text: String): Outcome<BackupFile> =
        decodeCatching { json.decodeFromString<BackupFile>(text) }

    private inline fun decodeCatching(read: () -> BackupFile): Outcome<BackupFile> {
        val file = try {
            read()
        } catch (e: Exception) {
            return Outcome.Err(AppError.Parse("backup", e.message ?: "The backup file could not be read."))
        }
        if (file.schemaVersion > BackupFile.CURRENT_SCHEMA_VERSION) {
            return Outcome.Err(
                AppError.Validation(
                    "schemaVersion",
                    "This backup was made by a newer version of MyHealth " +
                        "(schema ${file.schemaVersion}, this build reads up to " +
                        "${BackupFile.CURRENT_SCHEMA_VERSION}). Update the app and try again.",
                ),
            )
        }
        return Outcome.Ok(file)
    }
}
