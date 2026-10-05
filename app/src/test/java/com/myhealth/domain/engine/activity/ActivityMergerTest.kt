package com.myhealth.domain.engine.activity

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.SportType
import org.junit.Test

/**
 * The field-precedence table, `userEditedFieldsCsv` protection and `mergedSourcesCsv`
 * accumulation of PLAN §2.4 (task P2.4, cases `dedup08`–`dedup10`).
 */
class ActivityMergerTest {

    private val now = ActivityFixtures.NOW + 60_000L

    private val hc = ActivityFixtures.session(
        source = ActivitySource.HEALTH_CONNECT,
        startIso = "2026-09-12T06:00:00Z",
        durationSec = 3600,
        sportType = SportType.RUN_OUTDOOR,
        distanceMeters = 9_940.0,
        title = "Running",
        activeEnergyKcal = 720.0,
        totalEnergyKcal = 810.0,
        avgHr = 148,
        streams = ActivityFixtures.streams(140, 150),
    )

    private val fit = ActivityFixtures.session(
        source = ActivitySource.FIT_IMPORT,
        startIso = "2026-09-12T05:59:40Z",
        durationSec = 3612,
        sportType = SportType.RUN_TRAIL,
        distanceMeters = 10_000.0,
        title = "Morning trail",
        activeEnergyKcal = 690.0,
        totalEnergyKcal = 770.0,
        avgHr = 151,
        maxHr = 178,
        elevationGainM = 240.0,
        streams = ActivityFixtures.streams(141, 149, 152),
        laps = listOf(ActivityFixtures.lap(0, 5_000.0), ActivityFixtures.lap(1, 5_000.0)),
    )

    /**
     * P12: power lives in `MergeFieldGroup.MOTION`, so Health Connect's per-sample average and
     * maximum beat the CSV's — but each field is picked on its own, so the normalized power only
     * the CSV carries is not dropped along with them.
     */
    @Test
    fun bike02_merge_hc_power_beats_csv_and_csv_np_survives() {
        val hcRide = ActivityFixtures.session(
            source = ActivitySource.HEALTH_CONNECT,
            startIso = "2026-02-05T16:25:06Z",
            durationSec = 3_600,
            sportType = SportType.CYCLING_INDOOR,
            distanceMeters = null,
            avgPowerW = 247,
            maxPowerW = 300,
            normalizedPowerW = null,
        )
        val csvRide = ActivityFixtures.session(
            source = ActivitySource.CSV_IMPORT,
            startIso = "2026-02-05T16:25:06Z",
            durationSec = 3_612,
            sportType = SportType.CYCLING_INDOOR,
            distanceMeters = 34_620.0,
            avgPowerW = 166,
            maxPowerW = 310,
            normalizedPowerW = 167,
        )

        val merged = ActivityMerger.merge(listOf(hcRide, csvRide), existing = null, nowMillis = now)

        assertThat(merged.avgPowerW).isEqualTo(247)
        assertThat(merged.maxPowerW).isEqualTo(300)
        assertThat(merged.normalizedPowerW).isEqualTo(167)
        assertThat(merged.mergedSources)
            .containsExactly(ActivitySource.HEALTH_CONNECT, ActivitySource.CSV_IMPORT)

        // The order of the candidates must not change the outcome (§2.4).
        val flipped = ActivityMerger.merge(listOf(csvRide, hcRide), existing = null, nowMillis = now)
        assertThat(flipped.avgPowerW).isEqualTo(247)
        assertThat(flipped.normalizedPowerW).isEqualTo(167)
    }

    @Test
    fun dedup08_merge_prefers_fit_streams_and_hc_calories() {
        val merged = ActivityMerger.merge(listOf(fit), existing = mergedHcOnly(), nowMillis = now)

        // streams and laps: FIT wins
        assertThat(merged.streams).isEqualTo(fit.streams)
        assertThat(merged.hasStreams).isTrue()
        assertThat(merged.laps).hasSize(2)
        assertThat(merged.avgHr).isEqualTo(151)
        assertThat(merged.maxHr).isEqualTo(178)
        // distance / duration / elevation: FIT wins
        assertThat(merged.distanceMeters).isEqualTo(10_000.0)
        assertThat(merged.durationSec).isEqualTo(3612)
        assertThat(merged.elevationGainM).isEqualTo(240.0)
        assertThat(merged.startAtMillis).isEqualTo(fit.startAtMillis)
        // calories: Health Connect wins (device-calibrated, §2.4)
        assertThat(merged.activeEnergyKcal).isEqualTo(720.0)
        assertThat(merged.totalEnergyKcal).isEqualTo(810.0)
        // title and sport type: FIT wins over the coarse HC type
        assertThat(merged.title).isEqualTo("Morning trail")
        assertThat(merged.sportType).isEqualTo(SportType.RUN_TRAIL)
        // bookkeeping
        assertThat(merged.mergedSources)
            .containsExactly(ActivitySource.HEALTH_CONNECT, ActivitySource.FIT_IMPORT)
        assertThat(merged.primarySource).isEqualTo(ActivitySource.FIT_IMPORT)
        assertThat(merged.id).isEqualTo(41L)
        assertThat(merged.createdAtMillis).isEqualTo(ActivityFixtures.NOW)
        assertThat(merged.updatedAtMillis).isEqualTo(now)
        assertThat(merged.dedupeBucket)
            .isEqualTo(DedupeKey.of(SportType.RUN_TRAIL.group, fit.startAtMillis))
    }

    @Test
    fun dedup08b_merge_is_order_independent() {
        val hcThenFit = ActivityMerger.merge(listOf(fit), mergedHcOnly(), now)
        val fitFirst = ActivityMerger.merge(listOf(fit), existing = null, nowMillis = now)
            .copy(id = 41L, createdAtMillis = ActivityFixtures.NOW)
        val fitThenHc = ActivityMerger.merge(listOf(hc), fitFirst, now)

        assertThat(fitThenHc).isEqualTo(hcThenFit)
    }

    @Test
    fun dedup09_merge_never_overwrites_user_edited_fields() {
        val existing = mergedHcOnly().copy(
            title = "Intervals with the club",
            note = "felt great",
            rpe = 8,
            distanceMeters = 9_000.0,
            userEditedFields = listOf(
                ActivityFields.TITLE,
                ActivityFields.NOTE,
                ActivityFields.RPE,
                ActivityFields.DISTANCE_METERS,
            ),
        )

        val merged = ActivityMerger.merge(listOf(fit), existing, now)

        assertThat(merged.title).isEqualTo("Intervals with the club")
        assertThat(merged.note).isEqualTo("felt great")
        assertThat(merged.rpe).isEqualTo(8)
        assertThat(merged.distanceMeters).isEqualTo(9_000.0)
        assertThat(merged.userEditedFields).hasSize(4)
        // Unprotected fields still merge normally.
        assertThat(merged.durationSec).isEqualTo(3612)
        assertThat(merged.elevationGainM).isEqualTo(240.0)
    }

    @Test
    fun dedup10_merge_is_idempotent() {
        val once = ActivityMerger.merge(listOf(hc), existing = null, nowMillis = now)
        val twice = ActivityMerger.merge(listOf(hc), existing = once, nowMillis = now)
        val thrice = ActivityMerger.merge(listOf(hc), existing = twice, nowMillis = now)

        assertThat(twice).isEqualTo(once)
        assertThat(thrice).isEqualTo(once)

        val bothOnce = ActivityMerger.merge(listOf(fit), mergedHcOnly(), now)
        val bothTwice = ActivityMerger.merge(listOf(fit), bothOnce, now)

        assertThat(bothTwice).isEqualTo(bothOnce)
    }

    @Test
    fun merge_of_a_single_source_keeps_its_own_values() {
        val merged = ActivityMerger.merge(listOf(hc), existing = null, nowMillis = now)

        assertThat(merged.primarySource).isEqualTo(ActivitySource.HEALTH_CONNECT)
        assertThat(merged.mergedSources).containsExactly(ActivitySource.HEALTH_CONNECT)
        assertThat(merged.distanceMeters).isEqualTo(9_940.0)
        assertThat(merged.title).isEqualTo("Running")
        assertThat(merged.id).isEqualTo(0L)
    }

    @Test
    fun manual_entry_wins_note_and_rpe_over_every_device_source() {
        val manual = ActivityFixtures.session(
            source = ActivitySource.MANUAL,
            startIso = "2026-09-12T06:00:00Z",
            note = "tempo blocks",
            rpe = 9,
            distanceMeters = 8_000.0,
        )

        val merged = ActivityMerger.merge(listOf(manual), mergedHcOnly().copy(note = "auto"), now)

        assertThat(merged.note).isEqualTo("tempo blocks")
        assertThat(merged.rpe).isEqualTo(9)
        // MANUAL is unlisted in the motion group, so the device distance survives.
        assertThat(merged.distanceMeters).isEqualTo(9_940.0)
        assertThat(merged.primarySource).isEqualTo(ActivitySource.HEALTH_CONNECT)
    }

    @Test
    fun re_ingesting_one_source_does_not_pull_back_a_field_the_other_source_won() {
        val both = ActivityMerger.merge(listOf(fit), mergedHcOnly(), now)

        // The FIT file is imported again; Health Connect's calories must survive.
        val reIngested = ActivityMerger.merge(listOf(fit), both, now)

        assertThat(reIngested.activeEnergyKcal).isEqualTo(720.0)
        assertThat(reIngested.distanceMeters).isEqualTo(10_000.0)
    }

    /** The canonical row as it looks after only the Health Connect record has been ingested. */
    private fun mergedHcOnly() =
        ActivityMerger.merge(listOf(hc), existing = null, nowMillis = ActivityFixtures.NOW)
            .copy(id = 41L, createdAtMillis = ActivityFixtures.NOW)
}
