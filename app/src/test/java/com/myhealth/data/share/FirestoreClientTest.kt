package com.myhealth.data.share

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.share.ShareKind
import com.myhealth.domain.share.SharedItem
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** P27.3: the REST shapes of Firebase auth and Firestore, against a mock engine. */
class FirestoreClientTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        FirestoreClient(
            projectId = "demo-family",
            apiKey = "fake-key",
            clock = { 1_000L },
            client = {
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        bodies += when (val body = request.body) {
                            is OutgoingContent.ByteArrayContent -> String(body.bytes())
                            else -> ""
                        }
                        handler(request)
                    },
                ) { expectSuccess = false }
            },
        )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf("Content-Type", "application/json"))

    private val session = FirebaseSession("uid-me", "id-token", "refresh", 0L)

    @Test
    fun fc01_sign_up_is_anonymous_and_returns_uid_and_tokens() = runTest {
        val c = client { json("""{"localId":"uid-me","idToken":"t","refreshToken":"r","expiresIn":"3600"}""") }
        val s = (c.signUpAnonymous() as Outcome.Ok).value
        assertThat(s).isEqualTo(FirebaseSession("uid-me", "t", "r", 1_000L + 3_600_000L))
        assertThat(requests.single().url.toString())
            .isEqualTo("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-key")
        assertThat(bodies.single()).contains("returnSecureToken")
    }

    @Test
    fun fc02_refresh_uses_the_secure_token_endpoint() = runTest {
        val c = client { json("""{"user_id":"uid-me","id_token":"t2","refresh_token":"r2","expires_in":"3600"}""") }
        val s = (c.refresh("r") as Outcome.Ok).value
        assertThat(s.idToken).isEqualTo("t2")
        assertThat(s.refreshToken).isEqualTo("r2")
        assertThat(requests.single().url.host).isEqualTo("securetoken.googleapis.com")
    }

    @Test
    fun fc03_put_item_writes_typed_fields_with_bearer_and_create_precondition() = runTest {
        val c = client { json("{}") }
        val item = SharedItem("s-1", ShareKind.MEAL, "uid-me", "Me", "Oats", 3L, 99L, 1, """{"name":"Oats"}""")
        assertThat(c.putItem(session, item, create = true)).isEqualTo(Outcome.Ok(Unit))
        val req = requests.single()
        assertThat(req.method).isEqualTo(HttpMethod.Patch)
        assertThat(req.url.encodedPath).isEqualTo("/v1/projects/demo-family/databases/(default)/documents/items/s-1")
        assertThat(req.url.parameters["currentDocument.exists"]).isEqualTo("false")
        assertThat(req.headers["Authorization"]).isEqualTo("Bearer id-token")
        val fields = Json.parseToJsonElement(bodies.single()).jsonObject.getValue("fields").jsonObject
        assertThat(fields.getValue("version").jsonObject.getValue("integerValue").jsonPrimitive.content).isEqualTo("3")
        assertThat(fields.getValue("payload").jsonObject.getValue("stringValue").jsonPrimitive.content).isEqualTo("""{"name":"Oats"}""")
        assertThat(fields.getValue("ownerUid").jsonObject.getValue("stringValue").jsonPrimitive.content).isEqualTo("uid-me")
    }

    @Test
    fun fc04_update_has_no_precondition() = runTest {
        val c = client { json("{}") }
        c.putItem(session, SharedItem("s-1", ShareKind.MEAL, "uid-me", "Me", "Oats", 4L, 99L, 1, "{}"), create = false)
        assertThat(requests.single().url.parameters["currentDocument.exists"]).isNull()
    }

    @Test
    fun fc05_list_items_follows_pages_and_skips_broken_documents() = runTest {
        val page1 = """{"documents":[{"name":"projects/p/databases/(default)/documents/items/a","fields":{
            "type":{"stringValue":"INGREDIENT"},"ownerUid":{"stringValue":"uid-anna"},"authorName":{"stringValue":"Anna"},
            "name":{"stringValue":"Oats"},"version":{"integerValue":"2"},"updatedAt":{"integerValue":"5"},
            "schema":{"integerValue":"1"},"payload":{"stringValue":"{}"}}},
            {"name":"projects/p/databases/(default)/documents/items/broken","fields":{"type":{"stringValue":"SPACESHIP"}}}],
            "nextPageToken":"next"}"""
        val page2 = """{"documents":[{"name":"x/items/b","fields":{"type":{"stringValue":"MEAL"},"ownerUid":{"stringValue":"uid-me"},
            "payload":{"stringValue":"{}"}}}]}"""
        val c = client { req -> json(if (req.url.parameters["pageToken"] == "next") page2 else page1) }
        val items = (c.listItems(session) as Outcome.Ok).value
        assertThat(items.map { it.shareId }).containsExactly("a", "b").inOrder()
        assertThat(items.first()).isEqualTo(SharedItem("a", ShareKind.INGREDIENT, "uid-anna", "Anna", "Oats", 2L, 5L, 1, "{}"))
        assertThat(requests).hasSize(2)
    }

    @Test
    fun fc06_an_empty_collection_lists_nothing() = runTest {
        assertThat((client { json("{}") }.listItems(session) as Outcome.Ok).value).isEmpty()
    }

    @Test
    fun fc07_permission_denied_is_a_403() = runTest {
        val c = client { json("""{"error":{"code":403,"status":"PERMISSION_DENIED"}}""", HttpStatusCode.Forbidden) }
        val err = c.putItem(session, SharedItem("s", ShareKind.MEAL, "uid-me", "Me", "x", 1, 1, 1, "{}"), true)
        assertThat(((err as Outcome.Err).error as AppError.Network).code).isEqualTo(403)
    }

    @Test
    fun fc08_members_carry_the_join_code() = runTest {
        val c = client { json("{}") }
        c.putMember(session, "Me", "joincode")
        assertThat(requests.single().url.encodedPath).endsWith("/documents/members/uid-me")
        assertThat(bodies.single()).contains("joincode")
    }

    @Test
    fun fc09_delete_item() = runTest {
        val c = client { json("{}") }
        assertThat(c.deleteItem(session, "s-1")).isEqualTo(Outcome.Ok(Unit))
        assertThat(requests.single().method).isEqualTo(HttpMethod.Delete)
    }

    @Test
    fun fc10_emulator_endpoints() {
        val e = FirebaseEndpoints.emulator("10.0.2.2")
        assertThat(e.auth).isEqualTo("http://10.0.2.2:9099/identitytoolkit.googleapis.com")
        assertThat(e.firestore).isEqualTo("http://10.0.2.2:8080")
    }
}
