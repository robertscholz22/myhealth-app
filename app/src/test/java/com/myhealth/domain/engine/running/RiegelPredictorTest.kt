package com.myhealth.domain.engine.running

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.RunningBest
import org.junit.Test

/** Riegel extrapolation of PLAN §3.4, including `pr10`. */
class RiegelPredictorTest {

    private val today = 20_000L

    private fun best(
        distanceMeters: Double,
        timeSec: Int,
        day: Long = today - 10,
    ) = RunningBest(
        id = 0L,
        distanceMeters = distanceMeters,
        timeSec = timeSec,
        activityId = 1L,
        day = day,
        method = "FULL_ACTIVITY",
        isEstimated = false,
        paceSecPerKm = (timeSec / (distanceMeters / 1000.0)).toInt(),
        createdAtMillis = 0L,
    )

    @Test
    fun pr10_riegel_5k_to_10k() {
        val predicted = RiegelPredictor.predictSec(5000.0, 1200.0, 10_000.0)!!

        // 1200 * 2^1.06.
        assertThat(predicted).isWithin(1.0).of(2501.9)
    }

    /** POLISH-4: a 20:29 5 km (VDOT ~48) must beat a 56:30 10 km (VDOT ~35) as the source. */
    @Test
    fun pr13_source_effort_prefers_best_vdot() {
        val fiveK = best(5000.0, 20 * 60 + 29)
        val tenK = best(10_000.0, 56 * 60 + 30)

        val source = RiegelPredictor.pickSource(listOf(tenK, fiveK), today)!!

        assertThat(source.distanceMeters).isEqualTo(5000.0)
        assertThat(RiegelPredictor.vdotOf(fiveK)).isGreaterThan(RiegelPredictor.vdotOf(tenK))
        // The prediction now agrees with the 5 km best instead of contradicting it.
        val tenKPrediction = RiegelPredictor
            .predictAll(listOf(tenK, fiveK), today)
            .first { it.distanceMeters == CanonicalDistances.TEN_KM }
        assertThat(tenKPrediction.timeSec).isLessThan(56.0 * 60)
    }

    @Test
    fun a_stale_effort_is_never_the_source_even_when_it_is_the_best() {
        val source = RiegelPredictor.pickSource(
            listOf(best(3000.0, 660), best(21_097.5, 4200, day = today - 400)),
            today,
        )!!

        assertThat(source.distanceMeters).isEqualTo(3000.0)
    }

    @Test
    fun efforts_below_three_km_or_older_than_180_days_are_ignored() {
        assertThat(RiegelPredictor.pickSource(listOf(best(1000.0, 200)), today)).isNull()
        assertThat(RiegelPredictor.pickSource(listOf(best(5000.0, 1200, day = today - 181)), today))
            .isNull()
        assertThat(RiegelPredictor.pickSource(emptyList(), today)).isNull()
    }

    @Test
    fun predict_all_covers_every_canonical_distance() {
        val predictions = RiegelPredictor.predictAll(listOf(best(5000.0, 1200)), today)

        assertThat(predictions).hasSize(CanonicalDistances.ALL.size)
        assertThat(predictions.first { it.distanceMeters == CanonicalDistances.TEN_KM }.timeSec)
            .isWithin(1.0).of(2501.9)
        assertThat(predictions.first { it.distanceMeters == CanonicalDistances.MARATHON }.timeSec)
            .isGreaterThan(2501.9)
    }

    @Test
    fun degenerate_inputs_return_null() {
        assertThat(RiegelPredictor.predictSec(0.0, 1200.0, 10_000.0)).isNull()
        assertThat(RiegelPredictor.predictSec(5000.0, 0.0, 10_000.0)).isNull()
        assertThat(RiegelPredictor.predictSec(5000.0, 1200.0, 0.0)).isNull()
    }
}
