package com.myhealth.data.share

import com.myhealth.data.off.OffClient
import com.myhealth.domain.share.ShareCode
import com.myhealth.domain.share.ShareConnection
import com.myhealth.domain.share.ShareKind
import com.myhealth.domain.share.ShareRef
import com.myhealth.domain.share.ShareRules
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
import org.junit.Test
import java.io.File
import com.myhealth.domain.share.ShareFixtures as F

/**
 * P27 manual walkthrough helper, not a test: with `tools/firestore-emu.sh serve` running and
 * SHARE_WALKTHROUGH_OUT set, loads the rules, joins as "Anna", shares a recipe (two ingredients)
 * and a workout (one own exercise), and writes the connection code to that file for the app.
 */
class ShareWalkthroughSeed {
    @Test
    fun seed_anna() = runBlocking {
        val out = System.getenv("SHARE_WALKTHROUGH_OUT")
        assumeTrue(out != null)
        val endpoints = FirebaseEndpoints.emulator("127.0.0.1", 9099, 8080)
        val connection = ShareConnection("demo-family", "fake-api-key", ShareCode.newJoinCode())
        val body = buildJsonObject {
            putJsonObject("rules") {
                put("files", buildJsonArray { add(buildJsonObject { put("content", ShareRules.text(connection.joinCode)) }) })
            }
        }
        OffClient.defaultHttpClient().put("${endpoints.firestore}/emulator/v1/projects/demo-family:securityRules") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val anna = Device(endpoints)
        check(anna.service.connect(connection, "Anna") is Outcome.Ok)
        anna.exercises.upsert(F.exercise(id = "USER_ANNAPLANK01", name = "Side plank reach")).let { check(it is Outcome.Ok) }
        anna.ingredients.upsert(F.ingredient(id = 1L, name = "Skyr", barcode = "4000000000024"))
        anna.ingredients.upsert(F.ingredient(id = 2L, name = "Blueberries"))
        anna.meals.upsertTemplate(F.template().copy(name = "Berry skyr bowl", note = "Top with seeds"))
        anna.workouts.upsertWorkout(F.workout(listOf("PUSH_UP", "USER_ANNAPLANK01")).copy(name = "Core 20", videoUrl = null, videoTitle = null))
        check(anna.service.share(ShareRef(ShareKind.MEAL, "5")) is Outcome.Ok)
        check(anna.service.share(ShareRef(ShareKind.WORKOUT, "7")) is Outcome.Ok)
        File(out!!).writeText(ShareCode.encode(connection))
        println("seeded: ${anna.dao.links.value.map { it.kind + ":" + it.shareId }}")
    }
}
