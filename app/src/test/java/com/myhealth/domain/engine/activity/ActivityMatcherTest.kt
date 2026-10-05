package com.myhealth.domain.engine.activity

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * The four-part de-dup predicate and the 5-minute bucket key of PLAN §2.4 (task P2.4,
 * cases `dedup01`–`dedup07`).
 */
class ActivityMatcherTest {

    @Test
    fun dedup01_same_activity_from_hc_and_fit_matches() {
        val hc = ActivityFixtures.session(
            source = ActivitySource.HEALTH_CONNECT,
            startIso = "2026-09-12T06:00:00Z",
            durationSec = 3600,
            distanceMeters = 10_000.0,
        )
        // The watch file starts 40 s earlier, runs 25 s longer and measured 60 m more.
        val fit = ActivityFixtures.session(
            source = ActivitySource.FIT_IMPORT,
            startIso = "2026-09-12T05:59:20Z",
            durationSec = 3625,
            distanceMeters = 10_060.0,
        )

        assertThat(ActivityMatcher.matches(hc, fit)).isTrue()
        assertThat(ActivityMatcher.matches(fit, hc)).isTrue()
    }

    @Test
    fun dedup02_start_offset_over_three_minutes_does_not_match() {
        val base = ActivityFixtures.session(ActivitySource.HEALTH_CONNECT, "2026-09-12T06:00:00Z")
        val exactlyThreeMinutes =
            ActivityFixtures.session(ActivitySource.FIT_IMPORT, "2026-09-12T06:03:00Z")
        val oneMillisLater =
            ActivityFixtures.session(ActivitySource.FIT_IMPORT, "2026-09-12T06:03:00.001Z")

        assertThat(ActivityMatcher.matches(base, exactlyThreeMinutes)).isTrue()
        assertThat(ActivityMatcher.matches(base, oneMillisLater)).isFalse()
        assertThat(ActivityMatcher.matches(oneMillisLater, base)).isFalse()
    }

    @Test
    fun dedup03_duration_tolerance_is_five_percent() {
        // The tolerance is 5 % of the LONGER duration, so the boundary is 3789/3790 s, not 3780.
        assertThat(ActivityMatcher.durationsAgree(3600, 3789)).isTrue()
        assertThat(ActivityMatcher.durationsAgree(3600, 3790)).isFalse()
        // Below 1200 s the 60 s floor is the binding tolerance (5 % would be 30 s).
        assertThat(ActivityMatcher.durationsAgree(600, 660)).isTrue()
        assertThat(ActivityMatcher.durationsAgree(600, 661)).isFalse()

        val base = ActivityFixtures.session(ActivitySource.HEALTH_CONNECT, durationSec = 3600)
        val tooLong = ActivityFixtures.session(ActivitySource.FIT_IMPORT, durationSec = 3790)

        assertThat(ActivityMatcher.matches(base, tooLong)).isFalse()
    }

    @Test
    fun dedup04_distance_mismatch_rejects() {
        // 2 % of the LONGER distance beats the 100 m floor here: the boundary is 10 204/10 205 m.
        assertThat(ActivityMatcher.distancesAgree(10_000.0, 10_204.0)).isTrue()
        assertThat(ActivityMatcher.distancesAgree(10_000.0, 10_205.0)).isFalse()
        // Short efforts fall back to the 100 m floor.
        assertThat(ActivityMatcher.distancesAgree(1_000.0, 1_100.0)).isTrue()
        assertThat(ActivityMatcher.distancesAgree(1_000.0, 1_101.0)).isFalse()

        val hc = ActivityFixtures.session(ActivitySource.HEALTH_CONNECT, distanceMeters = 10_000.0)
        val other = ActivityFixtures.session(ActivitySource.FIT_IMPORT, distanceMeters = 14_000.0)

        assertThat(ActivityMatcher.matches(hc, other)).isFalse()
    }

    @Test
    fun dedup05_null_distance_on_one_side_still_matches() {
        val strength = ActivityFixtures.session(
            source = ActivitySource.HEALTH_CONNECT,
            sportType = SportType.STRENGTH,
            distanceMeters = null,
        )
        val fit = ActivityFixtures.session(
            source = ActivitySource.FIT_IMPORT,
            sportType = SportType.STRENGTH,
            distanceMeters = 120.0,
        )

        assertThat(ActivityMatcher.matches(strength, fit)).isTrue()
        assertThat(ActivityMatcher.matches(fit, strength)).isTrue()
        assertThat(ActivityMatcher.distancesAgree(null, null)).isTrue()
    }

    @Test
    fun dedup06_different_sport_group_never_matches() {
        val run = ActivityFixtures.session(ActivitySource.HEALTH_CONNECT, sportType = SportType.RUN_OUTDOOR)
        val ride = ActivityFixtures.session(ActivitySource.FIT_IMPORT, sportType = SportType.CYCLING)

        assertThat(run.sportGroup).isEqualTo(SportGroup.RUN)
        assertThat(ride.sportGroup).isEqualTo(SportGroup.CYCLE)
        assertThat(ActivityMatcher.matches(run, ride)).isFalse()

        // Same group, different type inside it, still matches.
        val trail = ActivityFixtures.session(ActivitySource.FIT_IMPORT, sportType = SportType.RUN_TRAIL)
        assertThat(ActivityMatcher.matches(run, trail)).isTrue()
    }

    @Test
    fun dedup07_bucket_spans_neighbour_buckets() {
        // 06:04:50 and 06:05:10 are 20 s apart but sit in different 5-minute buckets.
        val earlier = Fixtures.millis("2026-09-12T06:04:50Z")
        val later = Fixtures.millis("2026-09-12T06:05:10Z")

        val earlierBucket = DedupeKey.of(SportGroup.RUN, earlier)
        val laterBucket = DedupeKey.of(SportGroup.RUN, later)

        assertThat(earlierBucket).isNotEqualTo(laterBucket)
        assertThat(DedupeKey.neighbours(SportGroup.RUN, later)).contains(earlierBucket)
        assertThat(DedupeKey.neighbours(SportGroup.RUN, earlier)).contains(laterBucket)
        assertThat(DedupeKey.neighbours(SportGroup.RUN, later)).hasSize(3)
        assertThat(ActivityMatcher.startsAgree(earlier, later)).isTrue()

        // The key format is exactly the one stored in `activity_session.dedupeBucket` (§2.2.2).
        assertThat(laterBucket).isEqualTo("RUN|${later / 300_000L}")
    }
}
