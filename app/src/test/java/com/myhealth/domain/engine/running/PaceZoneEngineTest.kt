package com.myhealth.domain.engine.running

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.load.HrBounds
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.HrZoneScheme
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import org.junit.Test
import java.time.LocalDate

/**
 * Zone ↔ pace correlation of PLAN §3.10.2 (`pz01`…`pz12`).
 *
 * `hrMax = 190`, `hrRest = 50` → Karvonen boundaries `[134, 148, 162, 176]`, so HR 120 is Z1,
 * 140 is Z2, 150 is Z3 and 165 is Z4 in every fixture below.
 *
 * Every synthetic stream is 1 Hz and built as **warm-up + body + cool-down**: the 10-minute warm-up
 * is what sample rule 1 throws away, and the 30 s cool-down (1.0 m/s, dropped by rule 3) only
 * exists so the 20 s HR-lag lookup of the last body samples finds a reading of the right zone
 * instead of running off the end of the stream.
 */
class PaceZoneEngineTest {

    private val bounds = HrBounds(hrMax = 190, hrRest = 50)
    private val model = HrZoneModel(
        scheme = HrZoneScheme.HRR_KARVONEN,
        zones = HrZoneModel.zonesOf(listOf(134, 148, 162, 176), bounds),
        bounds = bounds,
    )
    private val today: Long = LocalDate.of(2026, 9, 13).toEpochDay()

    /** One constant stretch of a synthetic stream. */
    private data class Seg(val seconds: Int, val hr: Int, val speedMps: Double)

    private val warmUp = Seg(seconds = 600, hr = 120, speedMps = 2.5)

    private fun coolDown(hr: Int) = Seg(seconds = 30, hr = hr, speedMps = 1.0)

    private fun streamsOf(segments: List<Seg>): ActivityStreams {
        val offsets = ArrayList<Int>()
        val hr = ArrayList<Int?>()
        val speed = ArrayList<Double>()
        var t = 0
        for (segment in segments) {
            repeat(segment.seconds) {
                offsets += t++
                hr += segment.hr
                speed += segment.speedMps
            }
        }
        return ActivityStreams(
            sampleOffsetsSec = offsets.toIntArray(),
            hr = hr,
            speedMps = speed.toDoubleArray(),
            sampleCount = offsets.size,
            medianIntervalSec = 1.0,
        )
    }

    /** A run whose body is [body]; the warm-up and the matching cool-down are added here. */
    private fun streamRun(
        id: Long,
        body: List<Seg>,
        sportType: SportType = SportType.RUN_OUTDOOR,
        day: Long = today,
    ) = PaceRun(
        activityId = id,
        day = day,
        sportType = sportType,
        durationSec = 600 + body.sumOf { it.seconds } + 30,
        streams = streamsOf(listOf(warmUp) + body + coolDown(body.last().hr)),
    )

    /** A stream-less run: sample rule 7's single `(avgHr, duration / distance)` point. */
    private fun summaryRun(id: Long, avgHr: Int, distanceMeters: Double, durationSec: Int) = PaceRun(
        activityId = id,
        day = today,
        sportType = SportType.RUN_OUTDOOR,
        durationSec = durationSec,
        distanceMeters = distanceMeters,
        avgHr = avgHr,
    )

    private fun input(
        runs: List<PaceRun>,
        vdot: Double? = null,
        includeTreadmillInPrs: Boolean = false,
    ) = PaceZoneInput(today, runs, model, vdot, includeTreadmillInPrs)

    private fun List<PaceZoneBand>.z(zone: Int): PaceZoneBand = first { it.zone == zone }

    private fun speedForPace(secPerKm: Double): Double = 1000.0 / secPerKm

    // ---- pz01…pz12 ---------------------------------------------------------------------------

    /**
     * The fixture carries **speed only** — no `distanceMeters` channel — because Health Connect
     * never supplies cumulative distance (P14.2 risk note).
     *
     * §3.10.2 asks for centres 333 / 250 *and* `MODELLED` for the empty zones, which cannot both
     * hold: one activity caps the confidence at `LOW`, and `LOW` blends the measured median half
     * and half with the Daniels anchor — so the measured centres only survive untouched when there
     * is no VDOT to blend with, in which case the empty zones are `NONE`. Both halves are asserted
     * here, each under the VDOT that makes it true.
     */
    @Test
    fun pz01_single_run_median_per_zone() {
        val run = streamRun(1L, listOf(Seg(900, 140, 3.00), Seg(900, 165, 4.00)))

        val measured = PaceZoneEngine.compute(input(listOf(run)))
        assertThat(measured.z(2).centreSecPerKm).isEqualTo(333)
        assertThat(measured.z(2).inZoneSec).isEqualTo(880.0)
        assertThat(measured.z(4).centreSecPerKm).isEqualTo(250)
        assertThat(measured.z(4).activities).isEqualTo(1)
        assertThat(listOf(1, 3, 5).map { measured.z(it).confidence })
            .containsExactly(PaceConfidence.NONE, PaceConfidence.NONE, PaceConfidence.NONE)

        val anchored = PaceZoneEngine.compute(input(listOf(run), vdot = 50.0))
        assertThat(listOf(1, 3, 5).map { anchored.z(it).confidence })
            .containsExactly(PaceConfidence.MODELLED, PaceConfidence.MODELLED, PaceConfidence.MODELLED)
        assertThat(listOf(1, 3, 5).map { anchored.z(it).centreSecPerKm }).containsExactly(361, 268, 234)
        assertThat(anchored.z(2).centreSecPerKm).isEqualTo(334)
        assertThat(anchored.z(4).centreSecPerKm).isEqualTo(253)
    }

    @Test
    fun pz02_hr_lag_shift() {
        // Speed steps to 4.00 m/s at t = 1500; heart rate only follows at t = 1520.
        val run = streamRun(
            1L,
            listOf(Seg(900, 140, 3.00), Seg(20, 140, 4.00), Seg(880, 165, 4.00)),
        )

        val bands = PaceZoneEngine.compute(input(listOf(run)))

        assertThat(bands.z(2).centreSecPerKm).isEqualTo(333)
        assertThat(bands.z(2).highSecPerKm).isEqualTo(333)
        assertThat(bands.z(2).inZoneSec).isEqualTo(900.0)
        assertThat(bands.z(4).centreSecPerKm).isEqualTo(250)
        // Without the shift the same stream files 20 fast samples under the low zone.
        assertThat(fastSamplesInLowZoneWithoutLag(run.streams!!)).isEqualTo(20)
    }

    @Test
    fun pz03_first_ten_minutes_excluded() {
        val run = PaceRun(
            activityId = 1L,
            day = today,
            sportType = SportType.RUN_OUTDOOR,
            durationSec = 540,
            distanceMeters = 1620.0,
            avgHr = 140,
            streams = streamsOf(listOf(Seg(540, 140, 3.00))),
        )

        val bands = PaceZoneEngine.compute(input(listOf(run)))

        assertThat(bands.map { it.confidence }).containsExactlyElementsIn(List(5) { PaceConfidence.NONE })
        assertThat(bands.sumOf { it.inZoneSec }).isEqualTo(0.0)
    }

    @Test
    fun pz04_walking_and_stopped_samples_dropped() {
        val run = streamRun(
            1L,
            listOf(Seg(300, 140, 1.20), Seg(300, 140, 0.00), Seg(300, 140, 3.00)),
        )

        val bands = PaceZoneEngine.compute(input(listOf(run)))

        assertThat(bands.z(2).inZoneSec).isEqualTo(300.0)
        assertThat(bands.z(2).centreSecPerKm).isEqualTo(333)
    }

    @Test
    fun pz05_band_is_the_weighted_iqr() {
        val body = listOf(300.0, 310.0, 320.0, 330.0, 340.0)
            .map { Seg(240, 140, speedForPace(it)) }

        val z2 = PaceZoneEngine.compute(input(listOf(streamRun(1L, body)))).z(2)

        assertThat(z2.centreSecPerKm).isEqualTo(320)
        assertThat(z2.lowSecPerKm).isEqualTo(310)
        assertThat(z2.highSecPerKm).isEqualTo(330)
        assertThat(z2.inZoneSec).isEqualTo(1200.0)
    }

    @Test
    fun pz06_120s_is_low_confidence() {
        val exactly = PaceZoneEngine.compute(input(listOf(streamRun(1L, listOf(Seg(120, 140, 3.00))))))
        assertThat(exactly.z(2).inZoneSec).isEqualTo(120.0)
        assertThat(exactly.z(2).confidence).isEqualTo(PaceConfidence.LOW)

        val oneShort = PaceZoneEngine.compute(input(listOf(streamRun(1L, listOf(Seg(119, 140, 3.00))))))
        assertThat(oneShort.z(2).confidence).isEqualTo(PaceConfidence.NONE)
    }

    @Test
    fun pz07_two_activities_five_minutes_is_medium() {
        val runs = listOf(
            streamRun(1L, listOf(Seg(150, 140, 3.00)), day = today - 3),
            streamRun(2L, listOf(Seg(150, 140, 3.00)), day = today - 1),
        )

        val z2 = PaceZoneEngine.compute(input(runs)).z(2)

        assertThat(z2.inZoneSec).isEqualTo(300.0)
        assertThat(z2.activities).isEqualTo(2)
        assertThat(z2.confidence).isEqualTo(PaceConfidence.MEDIUM)
    }

    @Test
    fun pz08_three_activities_twenty_minutes_is_high() {
        val runs = (1L..3L).map { streamRun(it, listOf(Seg(400, 140, 3.00)), day = today - it) }

        val z2 = PaceZoneEngine.compute(input(runs, vdot = 50.0)).z(2)

        assertThat(z2.inZoneSec).isEqualTo(1200.0)
        assertThat(z2.activities).isEqualTo(3)
        assertThat(z2.confidence).isEqualTo(PaceConfidence.HIGH)
        // HIGH is measured only: the anchor (334 s/km) does not move the centre.
        assertThat(z2.centreSecPerKm).isEqualTo(333)
    }

    @Test
    fun pz09_low_confidence_blends_half_with_vdot() {
        // VDOT 48.9 puts the easy (Z2) anchor at 340 s/km; the measured median is 300.
        assertThat(DanielsPaces.secPerKm(48.9, DanielsPace.EASY)).isEqualTo(340)
        val run = streamRun(1L, listOf(Seg(200, 140, speedForPace(300.0))))

        val z2 = PaceZoneEngine.compute(input(listOf(run), vdot = 48.9)).z(2)

        assertThat(z2.confidence).isEqualTo(PaceConfidence.LOW)
        assertThat(z2.centreSecPerKm).isEqualTo(320)
        assertThat(z2.lowSecPerKm).isEqualTo(304)
        assertThat(z2.highSecPerKm).isEqualTo(336)
    }

    @Test
    fun pz10_no_samples_falls_back_to_vdot() {
        val bands = PaceZoneEngine.compute(input(runs = emptyList(), vdot = 50.0))

        val z4 = bands.z(4)
        assertThat(z4.confidence).isEqualTo(PaceConfidence.MODELLED)
        assertThat(z4.centreSecPerKm).isEqualTo(255)
        assertThat(z4.lowSecPerKm).isEqualTo(247)
        assertThat(z4.highSecPerKm).isEqualTo(263)
        assertThat(bands.map { it.centreSecPerKm }).containsExactly(361, 334, 268, 255, 234).inOrder()
    }

    @Test
    fun pz11_treadmill_excluded_by_default() {
        val belt = streamRun(
            1L,
            listOf(Seg(900, 140, 3.00), Seg(900, 165, 4.00)),
            sportType = SportType.RUN_TREADMILL,
        )

        val without = PaceZoneEngine.compute(input(listOf(belt)))
        assertThat(without.map { it.confidence }).containsExactlyElementsIn(List(5) { PaceConfidence.NONE })

        val modelled = PaceZoneEngine.compute(input(listOf(belt), vdot = 50.0))
        assertThat(modelled.map { it.confidence })
            .containsExactlyElementsIn(List(5) { PaceConfidence.MODELLED })

        val included = PaceZoneEngine.compute(input(listOf(belt), includeTreadmillInPrs = true))
        assertThat(included.z(2).centreSecPerKm).isEqualTo(333)
    }

    @Test
    fun pz12_csv_only_activity_caps_at_medium() {
        val single = PaceZoneEngine.compute(
            input(listOf(summaryRun(1L, avgHr = 150, distanceMeters = 10_000.0, durationSec = 3000))),
        ).z(3)
        assertThat(single.centreSecPerKm).isEqualTo(300)
        assertThat(single.inZoneSec).isEqualTo(3000.0)
        assertThat(single.confidence).isEqualTo(PaceConfidence.LOW)

        // Three of them would be HIGH by the ladder (3 activities, 150 min) — rule 7 caps them.
        val many = PaceZoneEngine.compute(
            input((1L..3L).map { summaryRun(it, 150, 10_000.0, 3000) }),
        ).z(3)
        assertThat(many.activities).isEqualTo(3)
        assertThat(many.confidence).isEqualTo(PaceConfidence.MEDIUM)
        assertThat(many.centreSecPerKm).isEqualTo(300)
    }

    // ---- recommendation + windowing ----------------------------------------------------------

    @Test
    fun recommended_pace_takes_the_first_zone_of_the_target_except_tempo_and_intervals() {
        val bands = PaceZoneEngine.compute(input(runs = emptyList(), vdot = 50.0))

        fun zoneOf(type: SessionType) = PaceZoneEngine.recommendedPaceFor(type, bands)?.zone

        assertThat(zoneOf(SessionType.RECOVERY_RUN)).isEqualTo(1)
        assertThat(zoneOf(SessionType.EASY_RUN)).isEqualTo(2)
        assertThat(zoneOf(SessionType.LONG_RUN)).isEqualTo(2)
        assertThat(zoneOf(SessionType.TEMPO_RUN)).isEqualTo(4)
        assertThat(zoneOf(SessionType.INTERVAL_RUN)).isEqualTo(5)
        assertThat(zoneOf(SessionType.ENDURANCE_RIDE)).isEqualTo(2)
        assertThat(PaceZoneEngine.recommendedPaceFor(SessionType.STRENGTH_FULL, bands)).isNull()
        assertThat(PaceZoneEngine.recommendedPaceFor(SessionType.SOCCER_MATCH, bands)).isNull()
        assertThat(PaceZoneEngine.recommendedPaceFor(SessionType.EASY_RUN, bands)?.centreSecPerKm)
            .isEqualTo(334)
    }

    @Test
    fun runs_outside_the_window_and_other_sports_are_ignored() {
        val old = streamRun(1L, listOf(Seg(900, 140, 3.00)), day = today - 91)
        val ride = streamRun(2L, listOf(Seg(900, 140, 3.00)), sportType = SportType.CYCLING)

        val bands = PaceZoneEngine.compute(input(listOf(old, ride)))

        assertThat(bands.map { it.confidence }).containsExactlyElementsIn(List(5) { PaceConfidence.NONE })
    }

    @Test
    fun compute_is_deterministic() {
        val runs = (1L..3L).map { streamRun(it, listOf(Seg(400, 140, 3.00)), day = today - it) }

        assertThat(PaceZoneEngine.compute(input(runs, vdot = 50.0)))
            .isEqualTo(PaceZoneEngine.compute(input(runs.reversed(), vdot = 50.0)))
    }

    /** What the low zone would have collected if the HR samples were not shifted by 20 s. */
    private fun fastSamplesInLowZoneWithoutLag(streams: ActivityStreams): Int =
        (0 until streams.sampleCount - 1).count { i ->
            val t = streams.sampleOffsetsSec[i]
            val hr = streams.hr[i]
            t >= PaceZoneEngine.WARMUP_SKIP_SEC &&
                streams.speedMps!![i] >= 3.90 &&
                hr != null &&
                model.zoneOf(hr) == 2
        }
}
