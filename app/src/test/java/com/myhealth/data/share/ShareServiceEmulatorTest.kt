package com.myhealth.data.share

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.off.OffClient
import com.myhealth.domain.share.LocalShareState
import com.myhealth.domain.share.RemoteStatus
import com.myhealth.domain.share.ShareCode
import com.myhealth.domain.share.ShareConnection
import com.myhealth.domain.share.ShareKind
import com.myhealth.domain.share.SharePlanner
import com.myhealth.domain.share.ShareRef
import com.myhealth.domain.share.ShareRules
import com.myhealth.domain.share.ShareFixtures as F
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * P27: two devices ("me" and "Anna") sharing through the real Firestore + Auth emulators.
 * Run by `tools/firestore-emu.sh test`; skipped otherwise.
 */
class ShareServiceEmulatorTest {

    private val host = System.getenv("FIRESTORE_EMULATOR_HOST")
    private lateinit var endpoints: FirebaseEndpoints
    private lateinit var connection: ShareConnection

    @Before
    fun setUp() = runBlocking {
        assumeTrue("Firestore emulator not running", host != null)
        endpoints = FirebaseEndpoints.emulator(host!!.substringBefore(':'), 9099, host.substringAfter(':').toInt())
        connection = ShareConnection("demo-family", "fake-api-key", ShareCode.newJoinCode())
        val body = buildJsonObject {
            putJsonObject("rules") {
                put("files", buildJsonArray { add(buildJsonObject { put("content", ShareRules.text(connection.joinCode)) }) })
            }
        }
        OffClient.defaultHttpClient().put("${endpoints.firestore}/emulator/v1/projects/demo-family:securityRules") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        Unit
    }

    private fun <T> Outcome<T>.ok(): T = (this as? Outcome.Ok)?.value ?: error("expected Ok, got $this")

    private suspend fun device(name: String) = Device(endpoints).also { it.service.connect(connection, name).ok() }

    /** Me: an own exercise in a workout, and a recipe with two ingredients (one with a barcode). */
    private suspend fun Device.seedMine() {
        exercises.upsert(F.exercise()).ok()
        ingredients.upsert(F.ingredient(id = 1L, name = "Oats", barcode = "4000000000017")).ok()
        ingredients.upsert(F.ingredient(id = 2L, name = "Milk")).ok()
        meals.upsertTemplate(F.template(listOf(1L, 2L))).ok()
        workouts.upsertWorkout(F.workout()).ok()
    }

    private val meal = ShareRef(ShareKind.MEAL, "5")
    private val workout = ShareRef(ShareKind.WORKOUT, "7")

    @Test
    fun se01_share_import_update_fork_and_stop_between_two_people() = runBlocking {
        val me = device("Me")
        me.seedMine()
        assertThat(me.service.share(meal).ok()).isEqualTo(3) // oats, milk, recipe
        assertThat(me.service.share(workout).ok()).isEqualTo(2) // band walk, workout
        assertThat(me.service.stateOf(meal)).isEqualTo(LocalShareState.Shared(changed = false))

        val anna = device("Anna")
        // Anna already has oats with the same barcode: hers, and it must stay hers.
        anna.ingredients.upsert(F.ingredient(id = 0L, name = "Annas oats", barcode = "4000000000017")).ok()
        val snapshot = anna.service.refresh().ok()
        val mealId = me.dao.getLink("MEAL", "5")!!.shareId!!
        val workoutId = me.dao.getLink("WORKOUT", "7")!!.shareId!!
        val links = anna.dao.links.value.mapNotNull { it.toDomain() }.associateBy { it.shareId!! }
        val annaUid = anna.dao.config.value!!.uid
        assertThat(SharePlanner.status(snapshot.items.single { it.shareId == mealId }, links, annaUid)).isEqualTo(RemoteStatus.NOT_IMPORTED)
        assertThat(snapshot.members.map { it.name }).containsAtLeast("Me", "Anna")

        val result = anna.service.import(listOf(mealId, workoutId)).ok()
        assertThat(result.imported).isEqualTo(5) // milk, recipe, band walk, workout (+ oats reused, counted)
        val annasOats = anna.ingredients.rows.values.single { it.barcode == "4000000000017" }
        assertThat(annasOats.name).isEqualTo("Annas oats")
        assertThat(anna.dao.links.value.none { it.kind == "INGREDIENT" && it.localKey == annasOats.id.toString() }).isTrue()
        val annasMeal = anna.meals.templates.values.single()
        assertThat(annasMeal.items.map { it.ingredientId }).contains(annasOats.id)
        assertThat(anna.service.stateOf(ShareRef(ShareKind.MEAL, annasMeal.id.toString()))).isEqualTo(LocalShareState.Theirs("Me", false))
        val annasWorkout = anna.workouts.rows.values.single()
        assertThat(annasWorkout.exercises.map { it.exerciseId }).containsExactly("PUSH_UP", "USER_BANDWALK0001").inOrder()
        assertThat(anna.exercises.rows.value.keys).containsExactly("USER_BANDWALK0001")

        // Anna marks the recipe a favourite (personal), then I change it: an update keeps her favourite.
        anna.meals.upsertTemplate(annasMeal.copy(isFavorite = true)).ok()
        me.meals.upsertTemplate(me.meals.templates.getValue(5L).copy(note = "Soak 8 hours")).ok()
        assertThat(me.service.changedMine()).containsExactly(meal)
        assertThat(me.service.pushChanges().ok()).isEqualTo(1)
        assertThat(me.service.changedMine()).isEmpty()
        val updated = anna.service.updateAll().ok()
        assertThat(updated.updated).isEqualTo(1)
        val after = anna.meals.templates.getValue(annasMeal.id)
        assertThat(after.note).isEqualTo("Soak 8 hours")
        assertThat(after.isFavorite).isTrue()

        // Anna can't share my workout, but can make her own copy; my next update leaves the copy alone.
        val annasWorkoutRef = ShareRef(ShareKind.WORKOUT, annasWorkout.id.toString())
        assertThat((anna.service.share(annasWorkoutRef) as Outcome.Err).error).isInstanceOf(AppError.Validation::class.java)
        val copy = anna.service.fork(annasWorkoutRef).ok()
        assertThat(anna.service.stateOf(copy)).isEqualTo(LocalShareState.Fork("Glute video", "Me", null))
        anna.workouts.upsertWorkout(anna.workouts.rows.getValue(copy.localKey.toLong()).copy(name = "My glute video")).ok()
        me.workouts.upsertWorkout(me.workouts.rows.getValue(7L).copy(durationMin = 30)).ok()
        me.service.pushChanges().ok()
        anna.service.updateAll().ok()
        assertThat(anna.workouts.rows.getValue(annasWorkout.id).durationMin).isEqualTo(30)
        assertThat(anna.workouts.rows.getValue(copy.localKey.toLong()).name).isEqualTo("My glute video")
        assertThat(anna.workouts.rows.getValue(copy.localKey.toLong()).durationMin).isEqualTo(25)

        // Anna shares her copy: it is hers, credited "based on".
        assertThat(anna.service.share(copy).ok()).isEqualTo(1)
        assertThat(anna.service.stateOf(copy)).isEqualTo(LocalShareState.Fork("Glute video", "Me", LocalShareState.Shared(false)))

        // I stop sharing the workout: Anna's copy of the original stays, marked no longer shared.
        me.service.stopSharing(workout).ok()
        anna.service.refresh().ok()
        assertThat(anna.service.stateOf(annasWorkoutRef)).isEqualTo(LocalShareState.Theirs("Me", true))
        assertThat(anna.workouts.rows).containsKey(annasWorkout.id)
    }

    @Test
    fun se02_a_pull_never_touches_my_rows() = runBlocking {
        val me = device("Me")
        me.seedMine()
        me.service.share(meal).ok()
        // Restored from my own backup on a second device-like state: the link says mine.
        val before = me.meals.templates.getValue(5L)
        val result = me.service.import(listOf(me.dao.getLink("MEAL", "5")!!.shareId!!)).ok()
        assertThat(result.imported + result.updated).isEqualTo(0)
        assertThat(me.meals.templates.getValue(5L)).isEqualTo(before)
        assertThat(me.meals.templates).hasSize(1)
    }

    @Test
    fun se03_wrong_join_code_stores_nothing() = runBlocking {
        val eve = Device(endpoints)
        val r = eve.service.connect(connection.copy(joinCode = ShareCode.newJoinCode()), "Eve")
        assertThat(((r as Outcome.Err).error as AppError.Network).code).isEqualTo(403)
        assertThat(eve.dao.config.value).isNull()
    }

    @Test
    fun se04_leaving_keeps_content_and_drops_my_links() = runBlocking {
        val me = device("Me")
        me.seedMine()
        me.service.share(meal).ok()
        me.service.leave().ok()
        assertThat(me.dao.config.value).isNull()
        assertThat(me.dao.links.value).isEmpty()
        assertThat(me.meals.templates).hasSize(1)
        assertThat(me.service.stateOf(meal)).isEqualTo(LocalShareState.Private)
    }

    @Test
    fun se05_reconnecting_to_the_same_library_keeps_the_author() = runBlocking {
        val me = device("Me")
        val uid = me.dao.config.value!!.uid
        me.service.connect(connection, "Me again").ok()
        assertThat(me.dao.config.value!!.uid).isEqualTo(uid)
        assertThat(me.dao.config.value!!.displayName).isEqualTo("Me again")
    }
}
