package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.testutil.Fixtures
import org.junit.Test

/** P26.6: video workouts in the suggestion pool. */
class VideoSuggestionTest {

    private val engine = SuggestionEngine(Fixtures.fixedClock("2026-09-14T06:00:00Z"))

    private fun workout(id: Long, kind: StrengthWorkoutKind, video: Int? = null) = StrengthWorkout(
        id = id,
        name = if (video != null) "Video $id" else "W$id",
        kind = kind,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        videoUrl = video?.let { "https://vimeo.com/$id" },
        durationMin = video,
    )

    private fun input(pool: List<StrengthWorkout>) = SuggestFixtures.input(
        events = listOf(SuggestFixtures.event(day(10), com.myhealth.domain.model.EventType.SOCCER_MATCH)),
        recentActivities = listOf(SuggestFixtures.activity(day(-1), 220.0, SportType.RUN_OUTDOOR)),
        profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":3,"STRENGTH":2}"""),
    ).copy(
        muscleLoad = SuggestFixtures.muscleLoad(sessions = listOf(SuggestFixtures.muscleSession(day(-1), trimp = 220.0))),
        strengthPool = pool,
    )

    @Test
    fun a_video_workout_is_suggested_for_its_type_with_its_own_length() {
        val sessions = engine.generate(input(listOf(workout(10, StrengthWorkoutKind.UPPER, video = 23)))).sessions
        val strength = sessions.filter { it.sessionType in Constraints.STRENGTH_TYPES }
        assertThat(strength).isNotEmpty()
        strength.forEach { s ->
            assertThat(s.workoutId).isEqualTo(10L)
            assertThat(s.targetDurationMin).isEqualTo(23)
            assertThat(s.estimatedTrimp).isWithin(1e-9).of(SessionCatalog.entryFor(s.sessionType)!!.estTrimpFor(23))
            assertThat(s.rationale.map { it.text }).contains("Video workout: Video 10 — 0 exercises, 23 min.")
        }
    }

    @Test
    fun a_video_without_a_stored_length_uses_its_estimate() {
        val w = workout(10, StrengthWorkoutKind.UPPER, video = 23).copy(durationMin = null)
        val strength = engine.generate(input(listOf(w))).sessions.filter { it.sessionType in Constraints.STRENGTH_TYPES }
        assertThat(strength).isNotEmpty()
        strength.forEach { assertThat(it.targetDurationMin).isEqualTo(w.estimatedMinutes) }
    }

    @Test
    fun an_ordinary_workout_keeps_the_slot_minutes() {
        val plain = engine.generate(input(listOf(workout(10, StrengthWorkoutKind.UPPER)))).sessions
            .filter { it.sessionType in Constraints.STRENGTH_TYPES }
        val video = engine.generate(input(listOf(workout(10, StrengthWorkoutKind.UPPER, video = 23)))).sessions
            .filter { it.sessionType in Constraints.STRENGTH_TYPES }
        assertThat(plain.map { it.day }).isEqualTo(video.map { it.day })
        assertThat(plain.none { it.targetDurationMin == 23 }).isTrue()
    }

    @Test
    fun video_fields_enter_the_hash_only_where_present() {
        val plain = input(listOf(workout(10, StrengthWorkoutKind.UPPER)))
        assertThat(SuggestionInputsHash.canonical(plain)).contains("10:UPPER:0|")
        assertThat(SuggestionInputsHash.canonical(plain)).doesNotContain(":v")
        val video = input(listOf(workout(10, StrengthWorkoutKind.UPPER, video = 23)))
        assertThat(SuggestionInputsHash.canonical(video)).contains("10:UPPER:0:v23")
        val longer = input(listOf(workout(10, StrengthWorkoutKind.UPPER, video = 30)))
        assertThat(SuggestionInputsHash.of(longer)).isNotEqualTo(SuggestionInputsHash.of(video))
    }

    @Test
    fun a_mobility_video_serves_the_mobility_slot() {
        val pool = listOf(workout(20, StrengthWorkoutKind.MOBILITY_FULL, video = 15))
        val ctx = MuscleContext.of(input(pool))
        assertThat(StrengthRules.choiceFor(SessionType.MOBILITY, ctx)!!.workoutId).isEqualTo(20L)
        assertThat(StrengthRules.workoutEntry(pool.single())).isNull()
    }
}
