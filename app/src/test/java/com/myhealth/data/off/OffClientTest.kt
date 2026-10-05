package com.myhealth.data.off

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.AppSettings
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * P20.2: the OkHttp → Ktor port keeps the P4.10 contract — one GET with the field list and the
 * required User-Agent, "unknown product" (status 0) and HTTP errors as `AppError.Network`, and
 * exactly one retry on an I/O failure.
 */
class OffClientTest {

    private val settings = mockk<SettingsRepository> {
        every { settings } returns flowOf(AppSettings(offUserAgentContact = "me@example.org"))
    }

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(vararg answers: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): OffClient {
        var call = 0
        val engine = MockEngine { request ->
            requests += request
            answers[minOf(call++, answers.lastIndex)](this, request)
        }
        val http = HttpClient(engine) { expectSuccess = false }
        return OffClient(settings, OffThrottle(), http, baseUrl = "https://off.test", io = Dispatchers.Unconfined)
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun off01_found_product_is_parsed_and_the_request_carries_fields_and_user_agent() = runTest {
        val result = client({
            respond(
                """{"code":"4000417025005","status":1,"product":{"product_name":" Nutella ","brands":"Ferrero, X",""" +
                    """"nutriments":{"energy-kcal_100g":539,"energy-kj_100g":2252}}}""",
                HttpStatusCode.OK,
                json,
            )
        }).fetch(" 4000417025005 ")

        val product = (result as Outcome.Ok).value
        assertThat(product.name).isEqualTo("Nutella")
        assertThat(product.brand).isEqualTo("Ferrero")
        assertThat(product.facts.energyKcal.value).isEqualTo(539.0)
        val request = requests.single()
        assertThat(request.url.toString()).isEqualTo(
            "https://off.test/api/v2/product/4000417025005.json?fields=${OffClient.FIELDS}",
        )
        assertThat(request.headers[HttpHeaders.UserAgent]).isEqualTo("MyHealth/0.1 (personal app; me@example.org)")
    }

    @Test
    fun off02_unknown_product_and_http_errors_map_to_network_errors() = runTest {
        val unknown = client({ respond("""{"status":0,"status_verbose":"product not found"}""", HttpStatusCode.OK, json) })
            .fetch("123")
        assertThat(unknown).isEqualTo(Outcome.Err(AppError.Network(OffClient.NOT_FOUND, null)))

        val server = client({ respond("oops", HttpStatusCode.InternalServerError) }).fetch("123")
        assertThat(server).isEqualTo(Outcome.Err(AppError.Network(500, null)))
    }

    @Test
    fun off03_one_io_failure_is_retried_two_are_an_error() = runTest {
        val retried = client(
            { throw IOException("flaky") },
            { respond("""{"status":1,"product":{"product_name":"A"}}""", HttpStatusCode.OK, json) },
        ).fetch("1")
        assertThat(retried).isInstanceOf(Outcome.Ok::class.java)
        assertThat(requests).hasSize(2)

        requests.clear()
        val failed = client({ throw IOException("down") }).fetch("1")
        val error = (failed as Outcome.Err).error as AppError.Network
        assertThat(error.code).isNull()
        assertThat(error.cause).isInstanceOf(IOException::class.java)
        assertThat(requests).hasSize(2)
    }

    @Test
    fun off04_blank_barcode_and_empty_bucket_never_reach_the_network() = runTest {
        assertThat(client({ error("no call expected") }).fetch("  ")).isInstanceOf(Outcome.Err::class.java)

        val empty = OffThrottle(permitsPerMinute = 0)
        val http = HttpClient(MockEngine { error("no call expected") })
        val throttled = OffClient(settings, empty, http, io = Dispatchers.Unconfined).fetch("1")
        assertThat(throttled).isEqualTo(Outcome.Err(AppError.Network(OffClient.THROTTLED, null)))
    }
}
