package com.myhealth.domain.engine.suggest

import okio.ByteString.Companion.encodeUtf8
import com.myhealth.domain.util.NumberFormat

/**
 * `suggestion_batch.inputsHash` (PLAN §3.5.6 step 9): SHA-256 over a canonical serialization of the
 * [SuggestionInput].
 *
 * Canonical means: every collection is sorted by a stable key, every `Double` is rendered at a
 * fixed precision with `Locale.US`, every `null` becomes the empty string, and no `hashCode()` or
 * map iteration order is involved. Two runs over the same data therefore produce the same digest in
 * this and any future process (test `sug14`), and the digest changes exactly when something the
 * engine actually reads changed.
 *
 * `java.security.MessageDigest` is used deliberately (R5 forbids adding a library for this).
 */
object SuggestionInputsHash {

    fun of(input: SuggestionInput): String = sha256(canonical(input))

    /** The exact string that gets hashed — exposed for tests and for debugging a regeneration. */
    fun canonical(input: SuggestionInput): String = buildString {
        line("today", input.todayDay.toString())
        line("horizonDays", input.horizonDays.toString())
        line("planStartDay", input.planStartDay?.toString())
        line(
            "profile",
            listOf(
                input.profile.id.toString(),
                input.profile.sex.name,
                input.profile.birthDay.toString(),
                num(input.profile.heightCm),
                input.profile.neatLevel.name,
                input.profile.preferredSportsJson,
                input.profile.mobilityOnRestDays.toString(),
                num(input.profile.goalWeightKg),
                num(input.profile.goalPaceKgPerWeek),
            ).joinToString("|"),
        )
        bikeLine(input)?.let { line("bike", it) }
        pacesLine(input)?.let { line("paces", it) }
        muscleLine(input)?.let { line("muscle", it) }
        goalFormLine(input)?.let { line("goalForm", it) }
        poolLine(input)?.let { line("pool", it) }
        input.goals.sortedWith(compareBy({ it.priority }, { it.id })).forEach { goal ->
            line(
                "goal",
                listOf(
                    goal.id.toString(), goal.type.name, goal.status.name, goal.priority.toString(),
                    goal.targetDay?.toString() ?: "", num(goal.targetDistanceMeters),
                    goal.targetTimeSec?.toString() ?: "", num(goal.targetWeightKg), num(goal.targetValue),
                ).joinToString("|") + (if (goal.isRace) "" else "|deadline"),
            )
        }
        input.events.sortedWith(compareBy({ it.occurrenceDay }, { it.eventId }, { it.type.name })).forEach { e ->
            line(
                "event",
                listOf(
                    e.eventId.toString(), e.occurrenceDay.toString(), e.type.name,
                    e.effectiveStartMinuteOfDay?.toString() ?: "", e.effectiveDurationMin?.toString() ?: "",
                    e.sportType?.name ?: "", e.isKeyEvent.toString(),
                ).joinToString("|"),
            )
        }
        input.lockedPlanned.sortedWith(compareBy({ it.day }, { it.id })).forEach { s ->
            line(
                "locked",
                listOf(
                    s.id.toString(), s.day.toString(), s.sportType.name, s.sessionType.name,
                    s.intensity.name, s.targetDurationMin?.toString() ?: "", num(s.estimatedTrimp),
                ).joinToString("|"),
            )
        }
        input.recentLoad.sortedBy { it.day }.forEach { load ->
            line(
                "load",
                listOf(
                    load.day.toString(), num(load.trimp), num(load.atl), num(load.ctl),
                    num(load.acwr), num(load.tsb),
                ).joinToString("|"),
            )
        }
        input.recovery?.let { state ->
            line(
                "recovery",
                listOf(
                    state.day.toString(), state.score?.toString() ?: "", state.band?.name ?: "",
                    num(state.confidence),
                ).joinToString("|"),
            )
        }
        input.cycleStatusByDay.entries.sortedBy { it.key }.forEach { (day, status) ->
            line(
                "cycle",
                listOf(
                    day.toString(), status.dayOfCycle.toString(), status.phase.name,
                    status.isLateLuteal.toString(), status.isPredicted.toString(),
                    status.cycleLengthDays.toString(), status.periodLengthDays.toString(),
                    status.nextPeriodStart.toString(), status.confidence.name,
                ).joinToString("|"),
            )
        }
        input.recentActivities.sortedWith(compareBy({ it.day }, { it.id })).forEach { a ->
            line(
                "activity",
                listOf(
                    a.id.toString(), a.day.toString(), a.sportType.name,
                    a.durationSec.toString(), num(a.trimp),
                ).joinToString("|"),
            )
        }
    }

    /**
     * P12.3's two profile fields (`ftpWattsManual`, `indoorTrainerAvailable`), emitted **only when
     * one of them is set**. A profile that never touched the bike settings therefore serializes
     * byte-for-byte as it did before P12 — so upgrading does not invalidate every stored batch and
     * `sug01`…`sug26` keep their digests (test `sug28`), while flipping the trainer switch or
     * setting an FTP override regenerates the week (test `sug32`).
     */
    private fun bikeLine(input: SuggestionInput): String? {
        val ftp = input.profile.ftpWattsManual
        val trainer = input.profile.indoorTrainerAvailable
        if (ftp == null && !trainer) return null
        return listOf(ftp?.toString() ?: "", trainer.toString()).joinToString("|")
    }

    /**
     * P14.3's `vdot` / `paceBands` / `ftpWatts`, emitted **only when at least one of them is
     * known** — the same "omitted when empty" rule [bikeLine] follows, and for the same reason: an
     * athlete with no running PR, no measured band and no FTP hashes exactly as before P14, so
     * `sug01`…`sug33` keep their digests (`sug28`). A new PR, a newly measured band or a changed
     * FTP does move the digest, which is what makes the week regenerate.
     */
    private fun pacesLine(input: SuggestionInput): String? {
        if (input.vdot == null && input.paceBands.isEmpty() && input.ftpWatts == null) return null
        val bands = input.paceBands.sortedBy { it.zone }.joinToString(",") { band ->
            listOf(
                band.zone.toString(),
                band.centreSecPerKm?.toString() ?: "",
                band.lowSecPerKm?.toString() ?: "",
                band.highSecPerKm?.toString() ?: "",
                band.confidence.name,
            ).joinToString(":")
        }
        return listOf(num(input.vdot), input.ftpWatts?.toString() ?: "", bands).joinToString("|")
    }

    /**
     * P14.5's muscle-load state and template alternation, emitted **only when
     * `SuggestionInput.muscleLoad` is set** — the same `bike=` trick, for the same reason: an
     * athlete whose muscle load was never computed hashes exactly as before P14.5, so `sug01`…
     * `sug36` keep their digests (`sug40`) and upgrading does not force the regeneration of an open
     * batch. Once it *is* set, a change in any group's load moves the digest, which is what makes
     * "you ran hard yesterday" regenerate the week.
     */
    private fun muscleLine(input: SuggestionInput): String? {
        val state = input.muscleLoad ?: return null
        val groups = state.byGroup.entries
            .sortedBy { it.key.ordinal }
            .joinToString(",") { (group, load) -> group.name + ":" + num(load) }
        val templates = input.lastAcceptedTemplateByKind.entries
            .sortedBy { it.key.ordinal }
            .joinToString(",") { (kind, templateId) -> kind.name + ":" + templateId }
        return listOf(
            num(state.ref),
            state.lowerBody.name,
            state.upperBody.name,
            groups,
            templates,
        ).joinToString("|")
    }

    fun sha256(value: String): String {
        return value.encodeUtf8().sha256().hex()
    }

    private fun num(value: Double?): String = value?.let { NumberFormat.fixed(it, 3) } ?: ""

    private fun StringBuilder.line(key: String, value: String?) {
        append(key).append('=').append(value ?: "").append('\n')
    }

    /** P19: the goal layer's facts; absent (no line at all) while `goalForm` is `null`. */
    private fun goalFormLine(input: SuggestionInput): String? {
        val form = input.goalForm ?: return null
        return listOf(
            form.vdotSourceDay?.toString() ?: "",
            num(form.longestRunMeters28d),
            form.timeTrialDays.sorted().joinToString(","),
        ).joinToString("|")
    }

    /** P19: the checked workouts and the rotation state; absent while `strengthPool` is `null`. */
    private fun poolLine(input: SuggestionInput): String? {
        val pool = input.strengthPool ?: return null
        val workouts = pool.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.kind.name}:${it.updatedAtMillis}" }
        val last = input.lastWorkoutIdBySessionType.entries
            .sortedBy { it.key.name }
            .joinToString(",") { "${it.key.name}=${it.value}" }
        return "$workouts|$last"
    }
}
