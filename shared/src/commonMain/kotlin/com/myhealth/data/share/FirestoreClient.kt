package com.myhealth.data.share

import com.myhealth.data.off.OffClient
import com.myhealth.domain.share.ShareKind
import com.myhealth.domain.share.ShareMember
import com.myhealth.domain.share.SharedItem
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Where the three Firebase services live: Google in production, the local emulator in tests. */
data class FirebaseEndpoints(val auth: String, val token: String, val firestore: String) {
    companion object {
        val PRODUCTION = FirebaseEndpoints(
            auth = "https://identitytoolkit.googleapis.com",
            token = "https://securetoken.googleapis.com",
            firestore = "https://firestore.googleapis.com",
        )

        /** The Firebase Local Emulator Suite (Auth on 9099, Firestore on 8080). */
        fun emulator(host: String, authPort: Int = 9099, firestorePort: Int = 8080) = FirebaseEndpoints(
            auth = "http://$host:$authPort/identitytoolkit.googleapis.com",
            token = "http://$host:$authPort/securetoken.googleapis.com",
            firestore = "http://$host:$firestorePort",
        )
    }
}

/** An anonymous sign-in: [uid] is the author id, [idToken] the short-lived bearer token. */
data class FirebaseSession(val uid: String, val idToken: String, val refreshToken: String, val expiresAtMillis: Long)

/**
 * P27: the family library over plain REST — Firebase anonymous auth plus Firestore — with the
 * shared Ktor client, so Android and iOS run the same code and no Firebase SDK is needed.
 *
 * Firestore's typed JSON is only used for the top-level fields of a document; the content itself
 * is one string ([SharedItem.payload]). Errors come back as `AppError.Network(code)`; 403 is the
 * rules saying no (not a member, or not the owner).
 */
class FirestoreClient(
    private val projectId: String,
    private val apiKey: String,
    private val endpoints: FirebaseEndpoints = FirebaseEndpoints.PRODUCTION,
    private val clock: () -> Long,
    client: (() -> HttpClient)? = null,
) {
    private val http by lazy { client?.invoke() ?: OffClient.defaultHttpClient() }

    private val documents: String
        get() = "${endpoints.firestore}/v1/projects/$projectId/databases/(default)/documents"

    // --- auth -----------------------------------------------------------------------------------

    suspend fun signUpAnonymous(): Outcome<FirebaseSession> = call {
        val response = http.post("${endpoints.auth}/v1/accounts:signUp") {
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody("""{"returnSecureToken":true}""")
        }
        response.json { body ->
            FirebaseSession(
                uid = body.string("localId"),
                idToken = body.string("idToken"),
                refreshToken = body.string("refreshToken"),
                expiresAtMillis = expiry(body.optString("expiresIn")),
            )
        }
    }

    suspend fun refresh(refreshToken: String): Outcome<FirebaseSession> = call {
        val response = http.submitForm(
            url = "${endpoints.token}/v1/token?key=$apiKey",
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            },
        )
        response.json { body ->
            FirebaseSession(
                uid = body.string("user_id"),
                idToken = body.string("id_token"),
                refreshToken = body.string("refresh_token"),
                expiresAtMillis = expiry(body.optString("expires_in")),
            )
        }
    }

    // --- members --------------------------------------------------------------------------------

    suspend fun putMember(session: FirebaseSession, name: String, joinCode: String): Outcome<Unit> = call {
        val fields = buildJsonObject {
            putString("name", name)
            putString("joinCode", joinCode)
            putInteger("joinedAt", clock())
        }
        http.patch("$documents/members/${session.uid}") {
            auth(session)
            contentType(ContentType.Application.Json)
            setBody(document(fields).toString())
        }.unit()
    }

    suspend fun listMembers(session: FirebaseSession): Outcome<List<ShareMember>> = call {
        listAll(session, "members").mapOk { list ->
            list.map { (id, f) -> ShareMember(id, f.str("name").orEmpty(), f.long("joinedAt") ?: 0L) }
        }
    }

    // --- items ----------------------------------------------------------------------------------

    suspend fun listItems(session: FirebaseSession): Outcome<List<SharedItem>> = call {
        listAll(session, "items").mapOk { list -> list.mapNotNull { (id, f) -> item(id, f) } }
    }

    /** Writes [item]; [create] adds the precondition that the document must not exist yet. */
    suspend fun putItem(session: FirebaseSession, item: SharedItem, create: Boolean): Outcome<Unit> = call {
        val fields = buildJsonObject {
            putString("type", item.kind.name)
            putString("ownerUid", item.ownerUid)
            putString("authorName", item.authorName)
            putString("name", item.name)
            putInteger("version", item.version)
            putInteger("updatedAt", item.updatedAtMillis)
            putInteger("schema", item.schema.toLong())
            putString("payload", item.payload)
        }
        http.patch("$documents/items/${item.shareId}") {
            auth(session)
            if (create) parameter("currentDocument.exists", "false")
            contentType(ContentType.Application.Json)
            setBody(document(fields).toString())
        }.unit()
    }

    suspend fun deleteItem(session: FirebaseSession, shareId: String): Outcome<Unit> = call {
        http.delete("$documents/items/$shareId") { auth(session) }.unit()
    }

    // --- plumbing -------------------------------------------------------------------------------

    private suspend fun listAll(session: FirebaseSession, collection: String): Outcome<List<Pair<String, JsonObject>>> {
        val out = mutableListOf<Pair<String, JsonObject>>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val response = http.get("$documents/$collection") {
                auth(session)
                parameter("pageSize", PAGE_SIZE)
                pageToken?.let { parameter("pageToken", it) }
            }
            if (!response.status.isSuccess()) return Outcome.Err(AppError.Network(response.status.value, null))
            val body = JSON.parseToJsonElement(response.bodyAsText()).jsonObject
            body["documents"]?.jsonArray?.forEach { doc ->
                val obj = doc.jsonObject
                val id = obj.getValue("name").jsonPrimitive.content.substringAfterLast('/')
                out += id to (obj["fields"]?.jsonObject ?: JsonObject(emptyMap()))
            }
            pageToken = body["nextPageToken"]?.jsonPrimitive?.contentOrNull
            if (pageToken == null) return Outcome.Ok(out)
        }
        return Outcome.Ok(out)
    }

    private fun item(id: String, f: JsonObject): SharedItem? {
        val kind = f.str("type")?.let { t -> ShareKind.entries.firstOrNull { it.name == t } } ?: return null
        return SharedItem(
            shareId = id,
            kind = kind,
            ownerUid = f.str("ownerUid") ?: return null,
            authorName = f.str("authorName").orEmpty(),
            name = f.str("name").orEmpty(),
            version = f.long("version") ?: 0L,
            updatedAtMillis = f.long("updatedAt") ?: 0L,
            schema = (f.long("schema") ?: 1L).toInt(),
            payload = f.str("payload") ?: return null,
        )
    }

    private fun HttpRequestBuilder.auth(session: FirebaseSession) {
        header(HttpHeaders.Authorization, "Bearer ${session.idToken}")
    }

    private fun expiry(seconds: String?): Long = clock() + (seconds?.toLongOrNull() ?: DEFAULT_EXPIRY_S) * 1000L

    private suspend fun HttpResponse.unit(): Outcome<Unit> =
        if (status.isSuccess()) Outcome.Ok(Unit) else Outcome.Err(AppError.Network(status.value, Exception(bodyAsText().take(300))))

    private suspend fun <T> HttpResponse.json(map: (JsonObject) -> T): Outcome<T> {
        if (!status.isSuccess()) return Outcome.Err(AppError.Network(status.value, Exception(bodyAsText().take(300))))
        return Outcome.Ok(map(JSON.parseToJsonElement(bodyAsText()).jsonObject))
    }

    private suspend fun <T> call(block: suspend () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        Outcome.Err(AppError.Parse("firestore", e.message ?: "Unreadable response"))
    } catch (e: Exception) {
        Outcome.Err(AppError.Network(null, e))
    }

    private inline fun <A, B> Outcome<A>.mapOk(f: (A) -> B): Outcome<B> = when (this) {
        is Outcome.Ok -> Outcome.Ok(f(value))
        is Outcome.Err -> this
    }

    companion object {
        private const val PAGE_SIZE = 300
        private const val MAX_PAGES = 50
        private const val DEFAULT_EXPIRY_S = 3600L
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun document(fields: JsonObject) = buildJsonObject { put("fields", fields) }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putString(key: String, value: String) =
            putJsonObject(key) { put("stringValue", value) }

        /** Firestore sends and takes 64-bit integers as strings. */
        private fun kotlinx.serialization.json.JsonObjectBuilder.putInteger(key: String, value: Long) =
            putJsonObject(key) { put("integerValue", value.toString()) }

        private fun JsonObject.str(key: String): String? =
            this[key]?.jsonObject?.get("stringValue")?.jsonPrimitive?.contentOrNull

        private fun JsonObject.long(key: String): Long? =
            this[key]?.jsonObject?.let { it["integerValue"] ?: it["doubleValue"] }?.jsonPrimitive?.contentOrNull
                ?.toDoubleOrNull()?.toLong()

        private fun JsonObject.string(key: String): String =
            requireNotNull(this[key]?.jsonPrimitive?.contentOrNull) { "missing $key" }

        private fun JsonObject.optString(key: String): String? = (this[key] as? JsonElement)?.jsonPrimitive?.contentOrNull
    }
}
