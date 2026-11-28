package com.myhealth.data.ai

import com.myhealth.data.off.OffClient
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * P28: the relay's app API (`relay/src/index.ts`). Every call but [register] carries the space
 * token; a 401 means the space is gone (disconnected elsewhere or wiped).
 */
class RelayClient(client: (() -> HttpClient)? = null) {
    private val http by lazy { client?.invoke() ?: OffClient.defaultHttpClient() }

    @Serializable
    data class Registered(val spaceId: String, val spaceToken: String)

    @Serializable
    data class Pulled(val opId: String, val op: JsonElement)

    @Serializable
    data class AckResult(val opId: String, val status: String, val reason: String? = null)

    @Serializable
    data class Status(
        val registered: Boolean = false,
        val snapshotAt: Long? = null,
        val pending: Int = 0,
        val connectors: List<String> = emptyList(),
    )

    @Serializable
    data class Pairing(val code: String, val expiresInSec: Int)

    @Serializable private data class OpsBody(val ops: List<Pulled>)

    @Serializable private data class AckBody(val results: List<AckResult>)

    suspend fun register(url: String, registrationKey: String): Outcome<Registered> = call {
        http.post("$url/v1/space") { bearer(registrationKey) }.decode<Registered>()
    }

    suspend fun putSnapshot(url: String, token: String, json: String): Outcome<Unit> = call {
        http.put("$url/v1/snapshot") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(json)
        }.decode<Unit?>().map { }
    }

    suspend fun pullOps(url: String, token: String): Outcome<List<Pulled>> = call {
        http.get("$url/v1/ops") { bearer(token) }.decode<OpsBody>().map { it.ops }
    }

    suspend fun ack(url: String, token: String, results: List<AckResult>): Outcome<Unit> = call {
        if (results.isEmpty()) return@call Outcome.Ok(Unit)
        http.post("$url/v1/ack") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(JSON.encodeToString(AckBody.serializer(), AckBody(results)))
        }.decode<Unit?>().map { }
    }

    suspend fun status(url: String, token: String): Outcome<Status> = call {
        http.get("$url/v1/status") { bearer(token) }.decode<Status>()
    }

    suspend fun pairing(url: String, token: String): Outcome<Pairing> = call {
        http.post("$url/v1/pairing") { bearer(token) }.decode<Pairing>()
    }

    suspend fun deleteSpace(url: String, token: String): Outcome<Unit> = call {
        http.delete("$url/v1/space") { bearer(token) }.decode<Unit?>().map { }
    }

    private fun HttpRequestBuilder.bearer(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

    private suspend inline fun <reified T> HttpResponse.decode(): Outcome<T> {
        val text = bodyAsText()
        if (!status.isSuccess()) {
            return Outcome.Err(AppError.Network(status.value, RelayException(errorText(text) ?: "HTTP ${status.value}")))
        }
        @Suppress("UNCHECKED_CAST")
        if (null is T) return Outcome.Ok(null as T)
        return Outcome.Ok(JSON.decodeFromString<T>(text))
    }

    private fun errorText(body: String): String? =
        runCatching { JSON.decodeFromString<Map<String, JsonElement>>(body)["error"]?.toString()?.trim('"') }.getOrNull()

    private suspend fun <T> call(block: suspend () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        Outcome.Err(AppError.Parse("relay", e.message ?: "Unreadable response"))
    } catch (e: Exception) {
        Outcome.Err(AppError.Network(null, e))
    }

    private inline fun <A, B> Outcome<A>.map(f: (A) -> B): Outcome<B> = when (this) {
        is Outcome.Ok -> Outcome.Ok(f(value))
        is Outcome.Err -> this
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

/** The relay's error text, e.g. "Snapshot is larger than 1 MB." */
class RelayException(message: String) : Exception(message)
