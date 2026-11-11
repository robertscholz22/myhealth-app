package com.myhealth.data.backup

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import org.junit.Test

/** The JSON codec of PLAN P8.4: full round-trip, schema gate, forwards compatibility. */
class BackupSerializerTest {

    @Test
    fun a_full_backup_round_trips_to_identical_rows() {
        val original = BackupFixtures.file()

        val decoded = BackupSerializer.decodeFromString(BackupSerializer.encodeToString(original))

        assertThat(decoded).isInstanceOf(Outcome.Ok::class.java)
        val restored = (decoded as Outcome.Ok).value
        assertThat(restored).isEqualTo(original)
        assertThat(restored.activitySession.single().trimp).isWithin(1e-9).of(226.2)
        assertThat(restored.mealLogItem.single().unit).isEqualTo(original.mealLogItem.single().unit)
        assertThat(restored.rowsPerTable()).isEqualTo(original.rowsPerTable())
        assertThat(restored.rideBest.single().kind).isEqualTo(original.rideBest.single().kind)
        assertThat(restored.totalRows).isEqualTo(15)
    }

    @Test
    fun the_row_counts_name_every_non_empty_table() {
        val counts = BackupFixtures.file().rowsPerTable()

        assertThat(counts.keys).containsExactly(
            "profile", "body_measurement", "activity_session", "activity_stream",
            "ingredient", "meal_log", "meal_log_item", "daily_load", "running_best",
            "ride_best", "cycle_entry", "strength_workout", "strength_workout_exercise",
            "strength_set_log", "exercise_progress",
        )
        assertThat(counts["activity_session"]).isEqualTo(1)
        assertThat(counts).doesNotContainKey("sleep_session")
    }

    @Test
    fun a_backup_from_a_newer_schema_is_rejected() {
        val future = BackupSerializer.encodeToString(
            BackupFixtures.file().copy(schemaVersion = BackupFile.CURRENT_SCHEMA_VERSION + 1),
        )

        val decoded = BackupSerializer.decodeFromString(future)

        assertThat(decoded).isInstanceOf(Outcome.Err::class.java)
        val error = (decoded as Outcome.Err).error
        assertThat(error).isInstanceOf(AppError.Validation::class.java)
        assertThat((error as AppError.Validation).field).isEqualTo("schemaVersion")
        assertThat(error.message).contains("newer version")
    }

    @Test
    fun a_backup_from_an_older_schema_is_accepted() {
        val older = BackupSerializer.encodeToString(BackupFixtures.file().copy(schemaVersion = 1))

        val decoded = BackupSerializer.decodeFromString(older)

        assertThat(decoded).isInstanceOf(Outcome.Ok::class.java)
        assertThat((decoded as Outcome.Ok).value.schemaVersion).isEqualTo(1)
    }

    @Test
    fun unknown_keys_and_unknown_tables_are_ignored() {
        val withExtras = """
            {"schemaVersion":2,"exportedAtMillis":7,"appVersion":"9.9","futureTable":[{"a":1}],
             "profile":[{"id":1,"displayName":"Robert","sex":"MALE","birthDay":5000,
             "heightCm":180.0,"createdAtMillis":1,"updatedAtMillis":1,"futureColumn":"x"}]}
        """.trimIndent()

        val decoded = BackupSerializer.decodeFromString(withExtras)

        assertThat(decoded).isInstanceOf(Outcome.Ok::class.java)
        val file = (decoded as Outcome.Ok).value
        assertThat(file.appVersion).isEqualTo("9.9")
        assertThat(file.profile.single().displayName).isEqualTo("Robert")
        // Columns the file leaves out fall back to the entity's own defaults — including the
        // P12 ones, so a backup written by 1.0.x still restores into a v5 database.
        assertThat(file.profile.single().sleepTargetHours).isEqualTo(8.0)
        assertThat(file.profile.single().ftpWattsManual).isNull()
        assertThat(file.profile.single().indoorTrainerAvailable).isFalse()
        assertThat(file.rideBest).isEmpty()
        assertThat(file.activitySession).isEmpty()
    }

    @Test
    fun p24_a_schema_8_meal_template_restores_as_one_serving_and_a_schema_9_one_keeps_its_servings() {
        val v8 = """
            {"schemaVersion":8,"exportedAtMillis":7,"appVersion":"0.9.4",
             "mealTemplate":[{"id":1,"name":"Porridge","defaultSlot":"BREAKFAST","note":null,
             "isFavorite":false,"useCount":2,"lastUsedAtMillis":null,"archived":false,
             "createdAtMillis":1,"updatedAtMillis":1}]}
        """.trimIndent()

        val old = (BackupSerializer.decodeFromString(v8) as Outcome.Ok).value
        assertThat(old.mealTemplate.single().servings).isEqualTo(1.0)

        val current = old.copy(
            schemaVersion = BackupFile.CURRENT_SCHEMA_VERSION,
            mealTemplate = listOf(old.mealTemplate.single().copy(servings = 4.0)),
        )
        val again = BackupSerializer.decodeFromString(BackupSerializer.encodeToString(current))
        assertThat((again as Outcome.Ok).value.mealTemplate.single().servings).isEqualTo(4.0)
    }

    @Test
    fun a_file_that_is_not_json_fails_as_a_parse_error() {
        val decoded = BackupSerializer.decodeFromString("this is not a backup")

        assertThat(decoded).isInstanceOf(Outcome.Err::class.java)
        assertThat((decoded as Outcome.Err).error).isInstanceOf(AppError.Parse::class.java)
    }

    @Test
    fun bug29_json_that_is_not_a_backup_is_refused() {
        // Every field of the document has a default and unknown keys are ignored, so a foreign
        // JSON object used to decode as an empty backup — and Replace then wiped the device.
        listOf("{}", """{"name":"something","items":[1,2,3]}""", """{"schemaVersion":9}""").forEach { foreign ->
            val decoded = BackupSerializer.decodeFromString(foreign)

            assertThat(decoded).isInstanceOf(Outcome.Err::class.java)
            assertThat((decoded as Outcome.Err).error).isInstanceOf(AppError.Parse::class.java)
        }
    }

    @Test
    fun bug29_an_export_of_an_empty_device_still_decodes() {
        // It is stamped with its time and app version, which is what tells it from foreign JSON.
        val empty = BackupFile(exportedAtMillis = 1_700_000_000_000L, appVersion = "0.9.6")

        val decoded = BackupSerializer.decodeFromString(BackupSerializer.encodeToString(empty))

        assertThat(decoded).isInstanceOf(Outcome.Ok::class.java)
    }
}
