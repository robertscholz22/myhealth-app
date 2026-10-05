package com.myhealth.data.off

import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.systemClock
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/**
 * Open Food Facts product lookup (PLAN P4.10).
 *
 * Contract, verbatim from §5/P4.10: one `GET` against the v2 product endpoint with an explicit
 * `fields` list, the **required** `User-Agent: MyHealth/0.1 (personal app; <contact>)` (the
 * contact is a setting, so it is editable without a rebuild), 10 s timeouts, exactly one retry on
 * an `IOException`, and a client-side token bucket of 15 requests per minute — OFF's documented
 * limit for product reads. A `status != 1` body (OFF answers "unknown product" with HTTP 200) maps
 * to `Outcome.Err(AppError.Network(404, null))`, which the UI shows as "Product not found".
 *
 * P20.2: Ktor client (OkHttp engine on Android as before, Darwin on iOS); same request, headers,
 * timeouts and retry rule.
 */
class OffClient(
    private val settings: SettingsRepository,
    private val throttle: OffThrottle = OffThrottle(),
    private val client: HttpClient = defaultHttpClient(),
    private val baseUrl: String = BASE_URL,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Looks [barcode] up; see the class KDoc for the error mapping. */
    suspend fun fetch(barcode: String): Outcome<OffProduct> = withContext(io) {
        val code = barcode.trim()
        if (code.isBlank()) {
            return@withContext Outcome.Err(AppError.Validation("barcode", "Enter a barcode first."))
        }
        if (!throttle.tryAcquire()) {
            return@withContext Outcome.Err(AppError.Network(THROTTLED, null))
        }
        when (val body = execute("$baseUrl/api/v2/product/$code.json?fields=$FIELDS", userAgent(contact()))) {
            is Outcome.Err -> body
            is Outcome.Ok -> parse(body.value, code)
        }
    }

    private suspend fun contact(): String =
        settings.settings.first().offUserAgentContact.trim().ifBlank { NO_CONTACT }

    /** One call, retried once on an `IOException` (a flaky mobile connection, not a 4xx/5xx). */
    private suspend fun execute(url: String, userAgent: String): Outcome<String> {
        var last: IOException? = null
        repeat(ATTEMPTS) {
            try {
                val response = client.get(url) { header(HttpHeaders.UserAgent, userAgent) }
                if (!response.status.isSuccess()) {
                    return Outcome.Err(AppError.Network(response.status.value, null))
                }
                return Outcome.Ok(response.bodyAsText())
            } catch (e: IOException) {
                last = e
            }
        }
        return Outcome.Err(AppError.Network(null, last))
    }

    private fun parse(body: String, barcode: String): Outcome<OffProduct> {
        val decoded = try {
            OFF_JSON.decodeFromString<OffResponse>(body)
        } catch (e: SerializationException) {
            return Outcome.Err(AppError.Parse("off-product", e.message ?: "Unreadable response"))
        }
        val product = OffMapper.toProduct(decoded, barcode)
            ?: return Outcome.Err(AppError.Network(NOT_FOUND, null))
        return Outcome.Ok(product)
    }

    companion object {
        const val BASE_URL = "https://world.openfoodfacts.org"
        const val FIELDS = "product_name,brands,quantity,serving_size,image_url,nutriments"

        /** `AppError.Network` codes this client produces on top of real HTTP status codes. */
        const val NOT_FOUND = 404
        const val THROTTLED = 429

        private const val NO_CONTACT = "no-contact"
        private const val ATTEMPTS = 2
        private const val TIMEOUT_MILLIS = 10_000L

        /** The exact header OFF requires of every API client. */
        fun userAgent(contact: String): String = "MyHealth/0.1 (personal app; $contact)"

        /** 10 s connect / socket / whole-request timeouts; non-2xx answers are returned, not thrown. */
        fun defaultHttpClient(): HttpClient = HttpClient(offHttpEngine()) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = TIMEOUT_MILLIS
                socketTimeoutMillis = TIMEOUT_MILLIS
                requestTimeoutMillis = TIMEOUT_MILLIS
            }
        }
    }
}

/**
 * Token bucket limiting OFF product reads to [permitsPerMinute] (P4.10). Refills continuously, so
 * 15 requests in a burst are allowed and the 16th has to wait 4 s for the next token. The [clock]
 * is injected so `OffThrottleTest` can advance time instead of sleeping.
 */
class OffThrottle(
    private val clock: PlatformClock = systemClock(),
    private val permitsPerMinute: Int = 15,
) {

    private var tokens: Double = permitsPerMinute.toDouble()
    private var refilledAtMillis: Long = clock.millis()

    private val lock = SynchronizedObject()

    /** `true` when a request may go out now, consuming one token. */
    fun tryAcquire(): Boolean = synchronized(lock) {
        refill()
        if (tokens < 1.0) return@synchronized false
        tokens -= 1.0
        true
    }

    /** Tokens currently available (for diagnostics and tests). */
    fun available(): Double = synchronized(lock) {
        refill()
        tokens
    }

    private fun refill() {
        val now = clock.millis()
        val elapsed = now - refilledAtMillis
        if (elapsed <= 0L) return
        val gained = elapsed * permitsPerMinute / 60_000.0
        tokens = (tokens + gained).coerceAtMost(permitsPerMinute.toDouble())
        refilledAtMillis = now
    }
}

/** The platform's HTTP engine: OkHttp on Android, `NSURLSession` (Darwin) on iOS. */
expect fun offHttpEngine(): HttpClientEngineFactory<*>
