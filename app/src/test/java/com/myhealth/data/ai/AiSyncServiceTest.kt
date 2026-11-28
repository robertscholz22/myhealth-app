package com.myhealth.data.ai

import com.myhealth.data.db.dao.AiDao
import com.myhealth.data.db.entity.AiChangeEntity
import com.myhealth.data.db.entity.AiConfigEntity
import com.myhealth.data.db.entity.AiLinkEntity
import com.myhealth.data.repository.InMemoryStrengthRepository
import com.myhealth.data.share.FakeUserExercises
import com.myhealth.domain.ai.AiConnection
import com.myhealth.domain.ai.AiDataGroup
import com.myhealth.domain.ai.AiOp
import com.myhealth.domain.ai.AiSessionFields
import com.myhealth.domain.ai.AiWorkoutRow
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SportType
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.AiChangeResult
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.CycleRepository
import com.myhealth.domain.repository.GoalRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.LoadRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.util.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P28.4: pull → plan → apply → log → ack → snapshot, and Undo, against a MockEngine relay. */
class AiSyncServiceTest {

    private val today = LocalDate(2026, 12, 1).toEpochDays().toLong()
    private val clock = Clock.fixed(Instant.parse("2026-12-01T10:00:00Z"), ZoneOffset.UTC)

    private val relay = FakeRelay()
    private val dao = FakeAiDao()
    private val exercises = FakeUserExercises()
    private val workouts = InMemoryStrengthRepository()
    private val plan = FakePlan()
    private val profile = FakeProfile()

    private val service = AiSyncService(
        dao,
        AiContent(
            exercises = exercises, workouts = workouts, plan = plan, profile = profile,
            goals = notUsedExcept<GoalRepository>("observeAll" to { flowOf(emptyList<Any>()) }),
            calendar = notUsedExcept<CalendarRepository>("observeOccurrences" to { flowOf(emptyList<Any>()) }),
            activities = notUsedExcept<ActivityRepository>("observeRange" to { flowOf(emptyList<Any>()) }, "getRange" to { emptyList<Any>() }),
            load = notUsedExcept<LoadRepository>("getRange" to { emptyList<Any>() }, "getLatest" to { null }),
            body = notUsedExcept<BodyRepository>("latestWeight" to { null }),
            health = notUsedExcept<HealthRepository>("observeRange" to { flowOf(emptyList<Any>()) }, "observeSleepRange" to { flowOf(emptyList<Any>()) }),
            cycle = notUsedExcept<CycleRepository>("isTrackingEnabled" to { flowOf(false) }),
        ),
        clock,
        RelayClient { HttpClient(MockEngine { req -> relay.route(req).let { (b, st, h) -> respond(b, st, h) } }) },
    )

    private val connection = AiConnection("https://relay.test/", "reg-key-0123456789abcdef")

    private fun session(id: Long, day: Long, locked: Boolean = false, type: SessionType = SessionType.EASY_RUN) = PlannedSession(
        id = id, planId = null, day = day, startMinuteOfDay = null, sportType = SportType.RUN_OUTDOOR, sessionType = type,
        intensity = Intensity.LOW, targetDurationMin = 45, targetDistanceMeters = null, targetPaceSecPerKm = null,
        estimatedTrimp = null, description = "Owner's run", rationale = null, status = PlannedStatus.PLANNED, locked = locked,
        linkedActivityId = null, sourceSuggestionId = null, createdAtMillis = 1L, updatedAtMillis = 1L,
    )

    private fun connected() = runBlocking {
        plan.rows.value = mapOf(1L to session(1, today + 1), 2L to session(2, today + 3, locked = true), 3L to session(3, today + 5))
        assertTrue(service.connect(connection, AiDataGroup.ALL) is Outcome.Ok)
    }

    private fun op(op: AiOp) = relay.queue(AiOp.JSON.encodeToJsonElement(AiOp.serializer(), op).jsonObject)

    private fun weekOps() {
        op(AiOp.CreateExercise("o1", "ai-ex0000001", "Banded face pull", listOf("SHOULDERS_REAR"), equipment = "BAND", pattern = "HORIZONTAL_PULL"))
        op(AiOp.CreateWorkout("o2", "ai-wo0000001", "Home pull A", "UPPER", listOf(AiWorkoutRow("ai-ex0000001", 3, reps = 15), AiWorkoutRow("PLANK", 2, seconds = 45))))
        op(AiOp.ScheduleSession("o3", "ai-se0000001", AiSessionFields(day = "2026-12-02", workout = "ai-wo0000001", rationale = "Pull balance")))
        op(AiOp.UpdateSession("o4", "s1", AiSessionFields(durationMin = 30)))
        op(AiOp.DeleteSession("o5", "s3"))
        op(AiOp.SetEquipment("o6", listOf("DUMBBELL", "BAND")))
    }

    @Test
    fun as01_connect_registers_and_pushes_a_snapshot() = runBlocking {
        connected()
        val config = dao.config.value!!
        assertEquals("https://relay.test", config.relayUrl)
        assertEquals("space1", config.spaceId)
        assertEquals("Bearer reg-key-0123456789abcdef", relay.registerAuth)
        val snap = relay.snapshots.single()
        assertEquals("2026-12-01", snap["today"]!!.jsonPrimitive.content)
        assertEquals(listOf("BASICS", "LOAD", "BODY", "CYCLE"), snap["groups"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("s1", "s2", "s3"), snap["sessions"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(clock.millis(), config.lastSyncAtMillis)
        assertNull(config.lastError)
    }

    @Test
    fun as02_a_week_is_applied_logged_acked_and_linked() = runBlocking {
        connected()
        weekOps()
        val result = (service.sync() as Outcome.Ok).value
        assertEquals(6, result.applied)
        assertEquals(0, result.rejected)

        val ex = exercises.rows.value.values.single()
        assertEquals("Banded face pull", ex.exercise.name)
        val wo = workouts.stored.value.values.single()
        assertEquals(listOf(ex.exercise.id, "PLANK"), wo.exercises.map { it.exerciseId })
        val created = plan.rows.value.values.single { it.id > 3 }
        assertEquals(wo.id, created.workoutId)
        assertEquals(SessionType.STRENGTH_UPPER, created.sessionType)
        assertEquals(30, plan.rows.value[1]!!.targetDurationMin)
        assertFalse(3L in plan.rows.value)
        assertEquals(setOf(Equipment.BODYWEIGHT, Equipment.DUMBBELL, Equipment.BAND), profile.equipment())

        assertEquals(List(6) { "applied" }, relay.acks.map { it.second })
        assertEquals(setOf("EXERCISE:${ex.exercise.id}", "WORKOUT:${wo.id}", "SESSION:${created.id}"), service.createdByAi.first())
        val links = relay.snapshots.last()["links"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(mapOf("ai-ex0000001" to ex.exercise.id, "ai-wo0000001" to "w${wo.id}", "ai-se0000001" to "s${created.id}"), links)
        val log = service.changes.first()
        assertEquals(6, log.size)
        assertTrue(log.all { it.result == AiChangeResult.APPLIED && it.batchId == result.batchId })
        assertTrue(log.any { it.summary == "Planned strength upper on 2026-12-02" })
    }

    @Test
    fun as03_out_of_scope_changes_are_rejected_with_the_reason() = runBlocking {
        connected()
        op(AiOp.UpdateSession("o1", "s2", AiSessionFields(durationMin = 60)))
        relay.queue(buildJsonObject { put("type", "teleport_session"); put("opId", "o2") })
        val result = (service.sync() as Outcome.Ok).value
        assertEquals(2, result.rejected)
        assertEquals(45, plan.rows.value[2]!!.targetDurationMin)
        assertEquals(listOf("rejected", "rejected"), relay.acks.map { it.second })
        assertTrue(relay.acks[0].third!!.contains("locked"))
        assertTrue(relay.acks[1].third!!.contains("newer MyHealth"))
        assertEquals(listOf(AiChangeResult.REJECTED, AiChangeResult.REJECTED), service.changes.first().map { it.result })
    }

    @Test
    fun as04_a_lost_ack_does_not_apply_twice() = runBlocking {
        connected()
        op(AiOp.ScheduleSession("o1", "ai-se0000001", AiSessionFields(day = "2026-12-04", sport = "WALK")))
        relay.dropAcks = true
        assertTrue(service.sync() is Outcome.Ok)
        relay.dropAcks = false
        assertEquals(1, relay.pending.size)
        assertTrue(service.sync() is Outcome.Ok)
        assertEquals(4, plan.rows.value.size)
        assertEquals(listOf("o1" to "applied"), relay.acks.map { it.first to it.second })
        assertEquals(1, service.changes.first().size)
    }

    @Test
    fun as05_undo_reverts_the_whole_batch() = runBlocking {
        connected()
        val before = plan.rows.value
        weekOps()
        val batch = (service.sync() as Outcome.Ok).value.batchId!!
        val snapshots = relay.snapshots.size

        assertEquals(Outcome.Ok(6), service.undo(batch))
        assertEquals(before, plan.rows.value)
        assertTrue(workouts.stored.value.isEmpty())
        assertTrue(exercises.rows.value.isEmpty())
        assertEquals(Equipment.entries.toSet(), profile.equipment())
        assertTrue(service.createdByAi.first().isEmpty())
        assertTrue(service.changes.first().all { it.result == AiChangeResult.UNDONE })
        assertEquals(snapshots + 1, relay.snapshots.size)
        assertEquals(Outcome.Ok(0), service.undo(batch))
    }

    @Test
    fun as06_undo_leaves_a_session_that_was_done_meanwhile() = runBlocking {
        connected()
        op(AiOp.ScheduleSession("o1", "ai-se0000001", AiSessionFields(day = "2026-12-02", sport = "WALK")))
        op(AiOp.UpdateSession("o2", "s1", AiSessionFields(durationMin = 20)))
        val batch = (service.sync() as Outcome.Ok).value.batchId!!
        val createdId = plan.rows.value.keys.max()
        plan.rows.value = plan.rows.value.mapValues { (_, s) -> s.copy(status = PlannedStatus.COMPLETED) }
        assertEquals(Outcome.Ok(0), service.undo(batch))
        assertTrue(createdId in plan.rows.value)
        assertEquals(20, plan.rows.value[1]!!.targetDurationMin)
        assertTrue(service.changes.first().all { it.result == AiChangeResult.APPLIED })
    }

    @Test
    fun as07_groups_off_leave_load_body_and_cycle_out() = runBlocking {
        connected()
        assertTrue(relay.snapshots.last().containsKey("body"))
        assertTrue(service.setGroups(emptySet()) is Outcome.Ok)
        val snap = relay.snapshots.last()
        assertEquals(listOf("BASICS"), snap["groups"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(snap.containsKey("body"))
        assertFalse(snap.containsKey("load"))
        assertEquals(setOf(AiDataGroup.BASICS), service.setup.first()!!.groups)
    }

    @Test
    fun as08_a_wiped_space_reports_how_to_fix_it() = runBlocking {
        connected()
        relay.gone = true
        assertTrue(service.sync() is Outcome.Err)
        assertEquals("The relay no longer knows this phone. Disconnect, then connect again.", dao.config.value!!.lastError)
    }

    @Test
    fun as09_disconnect_deletes_the_space_and_the_ai_rows_but_keeps_the_content() = runBlocking {
        connected()
        weekOps()
        service.sync()
        assertTrue(service.disconnect() is Outcome.Ok)
        assertTrue(relay.deleted)
        assertNull(service.setup.first())
        assertTrue(service.changes.first().isEmpty())
        assertTrue(service.createdByAi.first().isEmpty())
        assertEquals(1, workouts.stored.value.size)
    }

    @Test
    fun as10_pairing_code_and_relay_errors() = runBlocking {
        assertTrue(service.pairingCode() is Outcome.Err)
        connected()
        val code = (service.pairingCode() as Outcome.Ok).value
        assertEquals("ABCD2345", code.code)
        assertEquals(clock.millis() + 600_000, code.expiresAtMillis)
        relay.snapshotError = "Snapshot is larger than 1 MB."
        assertTrue(service.sync() is Outcome.Err)
        assertEquals("Snapshot is larger than 1 MB.", dao.config.value!!.lastError)
    }

    // --- fakes ------------------------------------------------------------------------------------

    /** The relay's app API (`relay/src/index.ts`), in memory. */
    private class FakeRelay {
        val pending = mutableListOf<JsonObject>()
        val acks = mutableListOf<Triple<String, String, String?>>()
        val snapshots = mutableListOf<JsonObject>()
        var registerAuth: String? = null
        var dropAcks = false
        var gone = false
        var deleted = false
        var snapshotError: String? = null

        fun queue(op: JsonObject) {
            pending += buildJsonObject { put("opId", op["opId"]!!); put("op", op) }
        }

        private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
            Triple(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

        private fun body(req: HttpRequestData) = (req.body as? TextContent)?.text ?: ""

        fun route(req: HttpRequestData): Triple<String, HttpStatusCode, io.ktor.http.Headers> {
            val path = req.url.encodedPath
            val token = req.headers[HttpHeaders.Authorization]
            if (path == "/v1/space" && req.method == HttpMethod.Post) {
                registerAuth = token
                return json("""{"spaceId":"space1","spaceToken":"space1.secret"}""")
            }
            if (gone || token != "Bearer space1.secret") return json("""{"error":"Unknown or disconnected space."}""", HttpStatusCode.Unauthorized)
            return when {
                path == "/v1/ops" -> json(buildJsonObject { put("ops", JsonArray(pending.toList())) }.toString())
                path == "/v1/ack" -> {
                    if (!dropAcks) {
                        val results = Json.parseToJsonElement(body(req)).jsonObject["results"]!!.jsonArray
                        for (r in results) {
                            val o = r.jsonObject
                            val id = o["opId"]!!.jsonPrimitive.content
                            acks += Triple(id, o["status"]!!.jsonPrimitive.content, o["reason"]?.jsonPrimitive?.content)
                            pending.removeAll { it["opId"]!!.jsonPrimitive.content == id }
                        }
                    }
                    json("""{"acked":1}""")
                }
                path == "/v1/snapshot" -> snapshotError?.let { json("""{"error":"$it"}""", HttpStatusCode.BadRequest) }
                    ?: run { snapshots += Json.parseToJsonElement(body(req)).jsonObject; json("""{"pending":${pending.size}}""") }
                path == "/v1/pairing" -> json("""{"code":"ABCD2345","expiresInSec":600}""")
                path == "/v1/status" -> json("""{"registered":true,"pending":${pending.size},"connectors":["Claude"]}""")
                path == "/v1/space" && req.method == HttpMethod.Delete -> { deleted = true; json("""{"deleted":true}""") }
                else -> json("""{"error":"Not found"}""", HttpStatusCode.NotFound)
            }
        }
    }

    private class FakeAiDao : AiDao {
        val config = MutableStateFlow<AiConfigEntity?>(null)
        val links = MutableStateFlow<List<AiLinkEntity>>(emptyList())
        val changes = MutableStateFlow<List<AiChangeEntity>>(emptyList())
        private var nextId = 1L

        override fun observeConfig(): Flow<AiConfigEntity?> = config
        override suspend fun getConfig() = config.value
        override suspend fun upsertConfig(row: AiConfigEntity) { config.value = row }
        override suspend fun deleteConfig() { config.value = null }
        override suspend fun getLinks() = links.value
        override fun observeLinks(): Flow<List<AiLinkEntity>> = links
        override suspend fun insertLink(row: AiLinkEntity) { links.value = links.value.filter { it.aiId != row.aiId } + row }
        override suspend fun deleteLink(kind: String, localKey: String) {
            links.value = links.value.filterNot { it.kind == kind && it.localKey == localKey }
        }
        override fun observeChanges(limit: Int): Flow<List<AiChangeEntity>> = changes.map { it.sortedByDescending { c -> c.id }.take(limit) }
        override suspend fun getBatch(batchId: String) = changes.value.filter { it.batchId == batchId }
        override suspend fun getChange(opId: String) = changes.value.firstOrNull { it.opId == opId }
        override suspend fun insertChange(row: AiChangeEntity): Long {
            if (changes.value.any { it.opId == row.opId }) return -1
            val r = row.copy(id = nextId++)
            changes.value = changes.value + r
            return r.id
        }
        override suspend fun setResult(id: Long, result: String) {
            changes.value = changes.value.map { if (it.id == id) it.copy(result = result) else it }
        }
        override suspend fun deleteChanges() { changes.value = emptyList() }
        override suspend fun deleteLinks() { links.value = emptyList() }
    }

    private class FakePlan : PlanRepository by notUsed() {
        val rows = MutableStateFlow<Map<Long, PlannedSession>>(emptyMap())
        private var nextId = 100L
        override suspend fun getSession(id: Long) = rows.value[id]
        override suspend fun getSessions(fromDay: Long, toDay: Long) =
            rows.value.values.filter { it.day in fromDay..toDay }.sortedWith(compareBy({ it.day }, { it.id }))
        override suspend fun upsertSession(session: PlannedSession): Outcome<Long> {
            val id = if (session.id == 0L) nextId++ else session.id
            rows.value = rows.value + (id to session.copy(id = id))
            return Outcome.Ok(id)
        }
        override suspend fun deleteSession(id: Long): Outcome<Unit> { rows.value = rows.value - id; return Outcome.Ok(Unit) }
    }

    private class FakeProfile : ProfileRepository by notUsed() {
        var profile = Profile(
            displayName = "Test", sex = Sex.MALE, birthDay = LocalDate(1990, 5, 1).toEpochDays().toLong(), heightCm = 180.0,
            restingHrManual = 50, createdAtMillis = 1L, updatedAtMillis = 1L,
        )
        override suspend fun getProfile() = profile
        override suspend fun upsert(profile: Profile): Outcome<Unit> { this.profile = profile; return Outcome.Ok(Unit) }
        fun equipment() = com.myhealth.domain.engine.strength.EquipmentSetCodec.decode(profile.availableEquipmentJson) ?: Equipment.entries.toSet()
    }
}

/** An interface whose every method throws, except those named in [answers] (suspend or not). */
private inline fun <reified T : Any> notUsedExcept(vararg answers: Pair<String, () -> Any?>): T {
    val map = answers.toMap()
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        map[method.name]?.invoke() ?: if (method.name in map) null else throw NotImplementedError("${T::class.simpleName}.${method.name}")
    } as T
}

private inline fun <reified T : Any> notUsed(): T = notUsedExcept()
