package com.myhealth.domain.engine.goal

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.model.SportType
import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.bike.FtpSource
import com.myhealth.domain.engine.suggest.SuggestFixtures
import com.myhealth.testutil.Fixtures
import org.junit.Test
import kotlinx.datetime.LocalDate

/**
 * [GoalProgress] against PLAN P6.1: `goal01`…`goal05` are the plan's named cases (`goal06` is the
 * repository's single-primary invariant, see `RoomGoalRepositoryTest`), `goal07`/`goal08` cover the
 * manual and no-data branches.
 */
class GoalProgressTest {

    private val today = LocalDate(2026, 9, 14)
    private val todayDay = today.toEpochDays()

    private fun best(distance: Double, timeSec: Int, dayOffset: Long = -5): RunningBest = RunningBest(
        id = distance.toLong() + timeSec,
        distanceMeters = distance,
        timeSec = timeSec,
        activityId = 1L,
        day = todayDay + dayOffset,
        method = "FULL_ACTIVITY",
        isEstimated = false,
        paceSecPerKm = (timeSec / (distance / 1000.0)).toInt(),
        createdAtMillis = 0L,
    )

    private fun weight(dayOffset: Long, kg: Double, atMillis: Long = 0L): BodyMeasurement = BodyMeasurement(
        id = dayOffset + 1000,
        measuredAtMillis = if (atMillis != 0L) atMillis else (todayDay + dayOffset) * 86_400_000L,
        day = todayDay + dayOffset,
        weightKg = kg,
        bodyFatPercent = null,
        muscleMassKg = null,
        boneMassKg = null,
        bodyWaterPercent = null,
        source = ActivitySource.MANUAL,
    )

    private fun raceGoal(targetSec: Int = 1200, distance: Double = 5000.0, targetDayOffset: Long = 60): Goal =
        SuggestFixtures.raceGoal(
            targetDay = todayDay + targetDayOffset,
            targetTimeSec = targetSec,
            distanceMeters = distance,
        )

    @Test
    fun goal01_race_time_percent() {
        val progress = GoalProgress.compute(
            goal = raceGoal(targetSec = 1200),
            bests = listOf(best(5000.0, 1274)), // 21:14
            weights = emptyList(),
            today = today,
        )
        assertThat(progress.percent).isWithin(1e-6).of(1200.0 / 1274.0)
        assertThat(progress.statusText).contains("21:14")
        assertThat(progress.isManual).isFalse()

        // A best that already beats the target is capped at 100 % and counts as on track.
        val met = GoalProgress.compute(raceGoal(1200), listOf(best(5000.0, 1150)), emptyList(), today)
        assertThat(met.percent).isWithin(1e-9).of(1.0)
        assertThat(met.onTrack).isTrue()
    }

    @Test
    fun goal02_race_time_on_track_via_riegel() {
        val goal = raceGoal(targetSec = 1200)
        // 10 km in 40:50 predicts 5 km in 2450 * 2^-1.06 = 1175 s, inside 1200 * 1.02.
        val quick = GoalProgress.compute(goal, listOf(best(5000.0, 1274), best(10000.0, 2450)), emptyList(), today)
        assertThat(quick.onTrack).isTrue()
        assertThat(quick.statusText).contains("on track")

        // 10 km in 45:00 predicts ~1295 s — behind.
        val slow = GoalProgress.compute(goal, listOf(best(5000.0, 1274), best(10000.0, 2700)), emptyList(), today)
        assertThat(slow.onTrack).isFalse()
        assertThat(slow.statusText).contains("behind")

        // P19.1: the same fast effort 200 days ago is outside the 180-day window and proves nothing.
        val stale = GoalProgress.compute(
            goal,
            listOf(best(5000.0, 1274, dayOffset = -200), best(10000.0, 2450, dayOffset = -200)),
            emptyList(),
            today,
        )
        assertThat(stale.onTrack).isFalse()
        assertThat(stale.isUnknown).isTrue()
    }

    @Test
    fun goal13_old_pr_is_only_a_note_recent_slower_effort_is_current_form() {
        // A typical case: an old all-time best, a slower recent 5 km, target 20:00.
        val goal = raceGoal(targetSec = 1200)
        val old = best(5000.0, 1214, dayOffset = -988)
        val recent = best(5000.0, 1318, dayOffset = -9)
        val progress = GoalProgress.compute(goal, listOf(old, recent), emptyList(), today)
        assertThat(progress.percent).isWithin(1e-6).of(1200.0 / 1318.0)
        assertThat(progress.statusText).startsWith("Recent best 21:58 (5 Sep)")
        assertThat(progress.statusText).contains("behind")
        assertThat(progress.onTrack).isFalse()
        assertThat(progress.isUnknown).isFalse()
    }

    @Test
    fun goal14_nothing_recent_is_unknown_with_the_all_time_note() {
        val goal = raceGoal(targetSec = 1200)
        val progress = GoalProgress.compute(goal, listOf(best(5000.0, 1214, dayOffset = -988)), emptyList(), today)
        assertThat(progress.isUnknown).isTrue()
        assertThat(progress.percent).isWithin(1e-9).of(0.0)
        assertThat(progress.statusText)
            .isEqualTo("No 5 km effort in the last 6 months (all-time 20:14, Dec 2023) — run a time trial to measure your form.")
    }

    @Test
    fun goal15_half_marathon_predicted_from_a_recent_5k() {
        val goal = raceGoal(targetSec = 5100, distance = 21097.5, targetDayOffset = 200)
        val progress = GoalProgress.compute(goal, listOf(best(5000.0, 1318, dayOffset = -9)), emptyList(), today)
        // Riegel: 1318 × (21097.5 / 5000)^1.06 ≈ 6095 s = 1:41:35.
        assertThat(progress.statusText).startsWith("Predicted 1:41:")
        assertThat(progress.statusText).contains("from your 5 km on 5 Sep — behind.")
        assertThat(progress.percent).isWithin(0.01).of(5100.0 / 6095.0)
    }

    @Test
    fun goal03_body_weight_progress() {
        val goal = weightGoal(targetKg = 80.0, createdOffset = -35, targetDayOffset = 35)
        val progress = GoalProgress.compute(
            goal = goal,
            bests = emptyList(),
            weights = listOf(weight(-35, 85.0), weight(-14, 83.5), weight(0, 82.5)),
            today = today,
        )
        // 85 -> 80 is 5 kg; 2.5 kg done.
        assertThat(progress.percent).isWithin(1e-6).of(0.5)
        assertThat(progress.statusText).contains("82.5 kg")
        assertThat(progress.onTrack).isTrue()
    }

    @Test
    fun goal04_body_weight_behind_schedule() {
        // 5 kg in 10 weeks = 0.5 kg/week required; 0.4 kg/week is the 80 % threshold.
        val goal = weightGoal(targetKg = 80.0, createdOffset = -35, targetDayOffset = 35)

        val behind = GoalProgress.compute(goal, emptyList(), listOf(weight(-35, 85.0), weight(0, 84.5)), today)
        assertThat(behind.onTrack).isFalse()
        assertThat(behind.statusText).contains("behind")

        val onTrack = GoalProgress.compute(goal, emptyList(), listOf(weight(-35, 85.0), weight(0, 82.75)), today)
        assertThat(onTrack.onTrack).isTrue()
    }

    @Test
    fun goal05_consistency_goal() {
        val goal = SuggestFixtures.goal(type = GoalType.CONSISTENCY, title = "4 sessions a week", targetValue = 4.0)
        val threePerWeek = (0L until 12L).map { SuggestFixtures.activity(todayDay - it * 2, trimp = 50.0, id = it) }
        val behind = GoalProgress.compute(goal, emptyList(), emptyList(), today, threePerWeek)
        assertThat(behind.percent).isWithin(1e-6).of(0.75)
        assertThat(behind.onTrack).isFalse()

        val sixteen = (0L until 16L).map { SuggestFixtures.activity(todayDay - it, trimp = 50.0, id = it) }
        val met = GoalProgress.compute(goal, emptyList(), emptyList(), today, sixteen)
        assertThat(met.percent).isWithin(1e-9).of(1.0)
        assertThat(met.onTrack).isTrue()

        // Activities older than four weeks do not count.
        val stale = (0L until 16L).map { SuggestFixtures.activity(todayDay - 40 - it, trimp = 50.0, id = it) }
        assertThat(GoalProgress.compute(goal, emptyList(), emptyList(), today, stale).percent)
            .isWithin(1e-9).of(0.0)
    }

    @Test
    fun goal07_manual_goals_are_reported_as_manual() {
        listOf(GoalType.STRENGTH_LIFT, GoalType.SOCCER_AVAILABILITY).forEach { type ->
            val progress = GoalProgress.compute(
                goal = SuggestFixtures.goal(type = type, targetValue = 120.0),
                bests = emptyList(),
                weights = emptyList(),
                today = today,
            )
            assertThat(progress.isManual).isTrue()
            assertThat(progress.percent).isWithin(1e-9).of(0.0)
            assertThat(progress.statusText).contains("manually")
        }
    }

    @Test
    fun goal08_goals_without_data_report_zero_and_say_so() {
        val race = GoalProgress.compute(raceGoal(), emptyList(), emptyList(), today)
        assertThat(race.percent).isWithin(1e-9).of(0.0)
        assertThat(race.onTrack).isFalse()
        assertThat(race.statusText).contains("5 km")

        val weightless = GoalProgress.compute(
            weightGoal(targetKg = 80.0, createdOffset = -10, targetDayOffset = 30),
            emptyList(),
            emptyList(),
            today,
        )
        assertThat(weightless.percent).isWithin(1e-9).of(0.0)
        assertThat(weightless.statusText).contains("Log a weight")

        // A race goal missing its target fields is manual, not a crash.
        val incomplete = GoalProgress.compute(
            SuggestFixtures.goal(type = GoalType.RACE_TIME),
            emptyList(),
            emptyList(),
            today,
        )
        assertThat(incomplete.isManual).isTrue()
    }

    @Test
    fun the_start_weight_is_the_first_measurement_after_the_goal_was_created() {
        val created = Fixtures.millis("2026-08-10T08:00:00Z")
        val series = listOf(
            weight(-60, 90.0, atMillis = Fixtures.millis("2026-07-16T08:00:00Z")),
            weight(-30, 85.0, atMillis = Fixtures.millis("2026-08-15T08:00:00Z")),
            weight(0, 82.5, atMillis = Fixtures.millis("2026-09-14T08:00:00Z")),
        )
        assertThat(GoalProgress.startMeasurement(series, created)?.weightKg).isEqualTo(85.0)
        // A goal older than every measurement falls back to the earliest one.
        assertThat(GoalProgress.startMeasurement(series, 0L)?.weightKg).isEqualTo(90.0)
    }

    @Test
    fun time_and_distance_labels_are_human_readable() {
        assertThat(GoalProgress.formatTime(1274)).isEqualTo("21:14")
        assertThat(GoalProgress.formatTime(3671)).isEqualTo("1:01:11")
        assertThat(GoalProgress.formatTime(-5)).isEqualTo("0:00")
        assertThat(GoalProgress.distanceLabel(5000.0)).isEqualTo("5 km")
        assertThat(GoalProgress.distanceLabel(21097.5)).isEqualTo("half marathon")
        assertThat(GoalProgress.distanceLabel(1609.34)).isEqualTo("mile")
        assertThat(GoalProgress.distanceLabel(800.0)).isEqualTo("800 m")
    }

    @Test
    fun goal09_bike_ftp_percent_and_on_track_at_95pct() {
        val goal = SuggestFixtures.goal(type = GoalType.BIKE_FTP, title = "300 W", targetValue = 300.0)

        val atThreshold = GoalProgress.compute(
            goal = goal,
            bests = emptyList(),
            weights = emptyList(),
            today = today,
            ftp = FtpEstimate(285, FtpSource.STREAM_20MIN, basisActivityId = 4L, basisDay = todayDay - 3),
        )
        assertThat(atThreshold.percent).isWithin(1e-9).of(0.95)
        assertThat(atThreshold.onTrack).isTrue()
        assertThat(atThreshold.statusText).contains("285 W")
        assertThat(atThreshold.isManual).isFalse()

        // One watt below the 95 % threshold is behind.
        val behind = GoalProgress.compute(
            goal, emptyList(), emptyList(), today,
            ftp = FtpEstimate(284, FtpSource.SESSION_NP),
        )
        assertThat(behind.onTrack).isFalse()
        assertThat(behind.statusText).contains("behind")

        // No estimate at all is 0 %, not manual.
        val none = GoalProgress.compute(goal, emptyList(), emptyList(), today)
        assertThat(none.percent).isWithin(1e-9).of(0.0)
        assertThat(none.onTrack).isFalse()
        assertThat(none.isManual).isFalse()
    }

    @Test
    fun goal10_bike_volume_hours_over_4_weeks() {
        val goal = SuggestFixtures.goal(type = GoalType.BIKE_VOLUME, title = "4 h a week", targetValue = 4.0)
        // Eight 90-minute rides inside the window = 12 h over 4 weeks = 3 h/week.
        val rides = (0L until 8L).map {
            SuggestFixtures.activity(
                day = todayDay - it * 3,
                trimp = 60.0,
                sportType = SportType.CYCLING,
                id = it,
                durationSec = 90 * 60,
            )
        }
        val noise = listOf(
            // A long run does not count towards riding hours.
            SuggestFixtures.activity(todayDay - 1, trimp = 80.0, sportType = SportType.RUN_OUTDOOR, id = 50L, durationSec = 5 * 3600),
            // A ride outside the four weeks does not either.
            SuggestFixtures.activity(todayDay - 40, trimp = 60.0, sportType = SportType.CYCLING, id = 51L, durationSec = 5 * 3600),
        )

        val progress = GoalProgress.compute(goal, emptyList(), emptyList(), today, rides + noise)

        assertThat(progress.percent).isWithin(1e-9).of(0.75)
        assertThat(progress.onTrack).isFalse()
        assertThat(progress.statusText).contains("3.0 h/week")

        // Four more rides reach the target.
        val more = rides + (10L until 14L).map {
            SuggestFixtures.activity(todayDay - it, trimp = 60.0, sportType = SportType.CYCLING_INDOOR, id = it, durationSec = 60 * 60)
        }
        val met = GoalProgress.compute(goal, emptyList(), emptyList(), today, more)
        assertThat(met.percent).isWithin(1e-9).of(1.0)
        assertThat(met.onTrack).isTrue()
    }

    @Test
    fun goal11_bike_event_time_from_ride_best() {
        val goal = SuggestFixtures.goal(
            type = GoalType.BIKE_EVENT,
            title = "40 km time trial",
            targetDay = todayDay + 30,
            targetDistanceMeters = 40_000.0,
            targetTimeSec = 4_200,
        )

        val behind = GoalProgress.compute(
            goal, emptyList(), emptyList(), today,
            rideBests = listOf(rideBest(RideBestKind.TIME_40K, 4_478.0), rideBest(RideBestKind.TIME_10K, 1_000.0)),
        )
        assertThat(behind.percent).isWithin(1e-9).of(4_200.0 / 4_478.0)
        assertThat(behind.onTrack).isFalse()
        assertThat(behind.statusText).contains("1:14:38")
        assertThat(behind.isManual).isFalse()

        val met = GoalProgress.compute(
            goal, emptyList(), emptyList(), today,
            rideBests = listOf(rideBest(RideBestKind.TIME_40K, 4_100.0)),
        )
        assertThat(met.percent).isWithin(1e-9).of(1.0)
        assertThat(met.onTrack).isTrue()

        // No 40 km effort yet: 0 %, and the text says which distance is missing.
        val none = GoalProgress.compute(goal, emptyList(), emptyList(), today)
        assertThat(none.percent).isWithin(1e-9).of(0.0)
        assertThat(none.statusText).contains("40 km")
    }

    @Test
    fun goal12_bike_event_date_only_is_manual() {
        val goal = SuggestFixtures.goal(
            type = GoalType.BIKE_EVENT,
            title = "Gran fondo",
            targetDay = todayDay + 60,
            targetDistanceMeters = 100_000.0,
        )

        val progress = GoalProgress.compute(
            goal, emptyList(), emptyList(), today,
            rideBests = listOf(rideBest(RideBestKind.TIME_100K, 12_000.0)),
        )

        assertThat(progress.isManual).isTrue()
        assertThat(progress.onTrack).isTrue()
        assertThat(progress.percent).isWithin(1e-9).of(0.0)
    }

    private fun rideBest(kind: RideBestKind, value: Double): RideBest = RideBest(
        id = value.toLong(),
        kind = kind,
        value = value,
        activityId = 1L,
        day = todayDay - 7,
        isEstimated = false,
        createdAtMillis = 0L,
    )

    private fun weightGoal(targetKg: Double, createdOffset: Long, targetDayOffset: Long): Goal =
        SuggestFixtures.goal(
            type = GoalType.BODY_WEIGHT,
            title = "Down to $targetKg kg",
            targetDay = todayDay + targetDayOffset,
            targetWeightKg = targetKg,
            status = GoalStatus.ACTIVE,
            createdAtMillis = (todayDay + createdOffset) * 86_400_000L,
        )
}
