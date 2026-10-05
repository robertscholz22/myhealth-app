package com.myhealth.data.repository

import com.myhealth.domain.engine.strength.ExerciseSubstitution
import com.myhealth.domain.engine.strength.StrengthTemplates
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.systemClock

/**
 * Materialises the nine built-in workouts of [StrengthTemplates] into `strength_workout`
 * (PLAN §3.12.3, P14.4; the three `MOBILITY_*` routines were added by P17.1).
 *
 * Seeding happens from **code, not from the migration**, the first time the Workouts screen opens
 * or a strength suggestion is accepted, so a template that turns out to be wrong can be corrected
 * in a later release without a schema change.
 *
 * Since P16.1 each template is **materialised against the owner's equipment** on the way in
 * ([ExerciseSubstitution.materialise]): a row whose implement they do not have is swapped for the
 * closest catalog exercise they can actually do, or dropped when nothing fits — the workout keeps
 * the rest. [availableEquipment] returning `null` (nobody restricted anything, and every call site
 * that predates P16) leaves every template byte-identical.
 *
 * It is idempotent on [com.myhealth.domain.model.StrengthWorkout.templateId] (`sw05`): a template
 * whose row already exists is left exactly as it is — including the user's own edits to it — and
 * `uq_strength_workout_template` is the database-side backstop for the same rule. Seeding twice
 * therefore leaves nine rows, not eighteen.
 */
class StrengthWorkoutSeeder(
    private val repo: StrengthRepository,
    private val clock: PlatformClock = systemClock(),
    /** The profile's "my equipment" set; `null` means everything (P16.1). */
    private val availableEquipment: suspend () -> Set<Equipment>? = { null },
) {

    /**
     * Inserts the built-ins that are missing and returns how many were written (0 on every run
     * after the first). Failures are swallowed per template: a seeder is a convenience, and a
     * half-seeded catalog completes itself on the next call.
     */
    suspend fun seed(): Int {
        var inserted = 0
        val available = availableEquipment()
        for (template in StrengthTemplates.ALL) {
            val templateId = template.templateId ?: continue
            if (repo.getByTemplateId(templateId) != null) continue
            val now = clock.millis()
            val materialised = ExerciseSubstitution.materialise(template, available)
            val written = repo.upsertWorkout(
                materialised.copy(createdAtMillis = now, updatedAtMillis = now),
            )
            if (written is Outcome.Ok) inserted++
        }
        return inserted
    }

    /**
     * The row of one built-in, seeding the whole set first if it has never run — what `accept`
     * calls when a suggestion names a `workoutTemplateId` (P14.5).
     */
    suspend fun workoutFor(templateId: String): StrengthWorkout? {
        repo.getByTemplateId(templateId)?.let { return it }
        seed()
        return repo.getByTemplateId(templateId)
    }
}
