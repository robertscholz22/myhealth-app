package com.myhealth.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * P20.2 / risk R3: the shared [HcExerciseType] and [HcSleepStageType] values must be the
 * connect-client constants of the same name. Every entry is compared through reflection, so a
 * constant added to the objects is covered automatically.
 */
class HcConstantsTest {

    private fun check(shared: Any, library: Class<*>, prefix: String) {
        val fields = shared::class.java.declaredFields.filter { it.type == Int::class.javaPrimitiveType && it.name != "INSTANCE" && !it.name.endsWith("\$stable") }
        assertWithMessage("no constants found in ${shared::class.simpleName}").that(fields).isNotEmpty()
        for (field in fields) {
            field.isAccessible = true
            val expected = library.getField(prefix + field.name).getInt(null)
            assertWithMessage("${shared::class.simpleName}.${field.name}").that(field.getInt(shared)).isEqualTo(expected)
        }
    }

    @Test
    fun hcc01_exercise_types_match_the_connect_client() =
        check(HcExerciseType, ExerciseSessionRecord::class.java, "EXERCISE_TYPE_")

    @Test
    fun hcc02_sleep_stage_types_match_the_connect_client() =
        check(HcSleepStageType, SleepSessionRecord::class.java, "STAGE_TYPE_")
}
