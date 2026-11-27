package com.myhealth.data.share

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.off.OffClient
import com.myhealth.domain.share.ShareCode
import com.myhealth.domain.share.ShareKind
import com.myhealth.domain.share.ShareRules
import com.myhealth.domain.share.SharedItem
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * P27.3: the generated rules, enforced by the real Firestore and Auth emulators. Skipped unless
 * `tools/firestore-emu.sh test` runs it (that sets `FIRESTORE_EMULATOR_HOST`).
 *
 * What it proves: without the join code nothing is readable, and no member can write an item in
 * someone else's name or change or delete someone else's item.
 */
class FirestoreRulesEmulatorTest {

    private val host = System.getenv("FIRESTORE_EMULATOR_HOST")
    private val project = "demo-family"
    private val joinCode = ShareCode.newJoinCode()
    private lateinit var c: FirestoreClient

    @Before
    fun setUp() {
        assumeTrue("Firestore emulator not running", host != null)
        val hostname = host!!.substringBefore(':')
        val port = host.substringAfter(':').toInt()
        val authPort = System.getenv("FIREBASE_AUTH_EMULATOR_HOST")?.substringAfter(':')?.toInt() ?: 9099
        c = FirestoreClient(project, "fake-api-key", FirebaseEndpoints.emulator(hostname, authPort, port), { System.currentTimeMillis() })
        runBlocking { loadRules(hostname, port) }
    }

    private suspend fun loadRules(hostname: String, port: Int) {
        val body = buildJsonObject {
            putJsonObject("rules") {
                put("files", buildJsonArray { add(buildJsonObject { put("content", ShareRules.text(joinCode)) }) })
            }
        }
        val response = OffClient.defaultHttpClient().put("http://$hostname:$port/emulator/v1/projects/$project:securityRules") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        check(response.status.isSuccess()) { "rules not loaded: ${response.status}" }
    }

    private fun <T> Outcome<T>.ok(): T = (this as? Outcome.Ok)?.value ?: error("expected Ok, got $this")

    private fun Outcome<*>.code(): Int? = ((this as? Outcome.Err)?.error as? AppError.Network)?.code

    private fun item(id: String, owner: String, version: Long = 1) =
        SharedItem(id, ShareKind.INGREDIENT, owner, "x", "Oats", version, 1L, 1, """{"name":"Oats","basis":"PER_100G","kcal":370}""")

    private fun newId() = "it-" + ShareCode.newJoinCode().take(12)

    @Test
    fun er01_a_stranger_without_the_join_code_reads_and_writes_nothing() = runBlocking {
        val stranger = c.signUpAnonymous().ok()
        assertThat(c.listItems(stranger).code()).isEqualTo(403)
        assertThat(c.putMember(stranger, "Eve", "wrong-code-wrong-code").code()).isEqualTo(403)
        assertThat(c.putItem(stranger, item(newId(), stranger.uid), create = true).code()).isEqualTo(403)
        assertThat(c.listMembers(stranger).code()).isEqualTo(403)
    }

    @Test
    fun er02_members_read_each_other_but_only_the_owner_writes() = runBlocking {
        val me = c.signUpAnonymous().ok()
        val anna = c.signUpAnonymous().ok()
        c.putMember(me, "Me", joinCode).ok()
        c.putMember(anna, "Anna", joinCode).ok()

        val mine = newId()
        c.putItem(me, item(mine, me.uid), create = true).ok()
        assertThat(c.listItems(anna).ok().map { it.shareId }).contains(mine)
        assertThat(c.listMembers(anna).ok().map { it.name }).containsAtLeast("Me", "Anna")

        // Anna can neither overwrite, take over, nor delete my item…
        assertThat(c.putItem(anna, item(mine, anna.uid, 2), create = false).code()).isEqualTo(403)
        assertThat(c.putItem(anna, item(mine, me.uid, 2), create = false).code()).isEqualTo(403)
        assertThat(c.deleteItem(anna, mine).code()).isEqualTo(403)
        // …nor create a new item in my name.
        assertThat(c.putItem(anna, item(newId(), me.uid), create = true).code()).isEqualTo(403)

        // I can update and delete my own.
        c.putItem(me, item(mine, me.uid, 2), create = false).ok()
        assertThat(c.listItems(me).ok().single { it.shareId == mine }.version).isEqualTo(2L)
        // Handing it over to Anna is refused too: the owner may not change.
        assertThat(c.putItem(me, item(mine, anna.uid, 3), create = false).code()).isEqualTo(403)
        c.deleteItem(me, mine).ok()
        assertThat(c.listItems(anna).ok().map { it.shareId }).doesNotContain(mine)
    }

    @Test
    fun er03_create_refuses_an_existing_id() = runBlocking {
        val me = c.signUpAnonymous().ok()
        c.putMember(me, "Me", joinCode).ok()
        val id = newId()
        c.putItem(me, item(id, me.uid), create = true).ok()
        assertThat(c.putItem(me, item(id, me.uid), create = true)).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun er04_refresh_keeps_the_same_author() = runBlocking {
        val me = c.signUpAnonymous().ok()
        val again = c.refresh(me.refreshToken).ok()
        assertThat(again.uid).isEqualTo(me.uid)
        c.putMember(again, "Me", joinCode).ok()
        assertThat(c.listItems(again)).isInstanceOf(Outcome.Ok::class.java)
    }
}
