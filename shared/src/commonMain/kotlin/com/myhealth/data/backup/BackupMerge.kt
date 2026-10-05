package com.myhealth.data.backup

import com.myhealth.data.db.dao.BackupDao

/**
 * The MERGE half of [BackupService] (PLAN P8.4): insert only what the device does not already
 * have, matched on each table's **natural key** rather than on its primary key.
 *
 * Why not a plain upsert by id: two databases that were never the same database hand out the same
 * autoincrement ids to entirely different rows, so restoring by id would silently overwrite. A
 * merge therefore re-inserts a missing row with a *fresh* id and rewrites every foreign key that
 * pointed at it through [Remap].
 *
 * Rules:
 * - A row whose natural key already exists is skipped; nothing on the device is ever overwritten
 *   or deleted. Re-importing the same file twice is a no-op, which is what the smoke test checks.
 * - Owned children (streams, laps, overrides, template/meal items, suggested sessions, strength
 *   exercise rows) are only inserted when their parent was newly created — an existing parent
 *   already has its children.
 * - Single-key caches (`profile`, `daily_*`, `nutrition_target_snapshot`, `sync_state`) are keyed
 *   by their own primary key: the device's own computed rows win over the backup's older ones.
 */
internal class BackupMerge(private val dao: BackupDao) {

    /** Old id → id in this database, plus which of them this merge actually created. */
    private class Remap {
        val ids: MutableMap<Long, Long> = mutableMapOf()
        val created: MutableSet<Long> = mutableSetOf()

        /** Rows this merge actually inserted (two old ids may collapse onto one new row). */
        var inserted: Int = 0

        fun target(oldId: Long?): Long? = oldId?.let { ids[it] }

        fun isNew(oldId: Long?): Boolean = oldId != null && oldId in created
    }

    @Suppress("LongMethod")
    suspend fun merge(file: BackupFile, existing: BackupFile): Int {
        var written = 0

        written += mergeKeyed(file.profile, existing.profile, { it.id }, dao::insertProfile)
        written += mergeKeyed(
            file.dailyHealthSummary, existing.dailyHealthSummary, { it.day }, dao::insertDailyHealthSummary,
        )
        written += mergeKeyed(file.dailyLoad, existing.dailyLoad, { it.day }, dao::insertDailyLoad)
        written += mergeKeyed(
            file.nutritionTargetSnapshot,
            existing.nutritionTargetSnapshot,
            { it.day },
            dao::insertNutritionTargetSnapshot,
        )
        written += mergeKeyed(file.syncState, existing.syncState, { it.key }, dao::insertSyncState)
        // P16.1: keyed by the catalog id it already is. The device's own state wins — a progression
        // it has been advancing is newer than whatever the backup froze.
        written += mergeKeyed(
            file.exerciseProgress, existing.exerciseProgress, { it.exerciseId },
            dao::insertExerciseProgress,
        )

        val ingredients = mergeRoot(
            file.ingredient, existing.ingredient, { it.id },
            { it.barcode ?: "${it.name.lowercase()}|${it.brand.orEmpty().lowercase()}" },
            { it.copy(id = 0L) }, dao::insertIngredient,
        )
        val activities = mergeRoot(
            file.activitySession, existing.activitySession, { it.id },
            { "${it.startAtMillis}|${it.sportGroup}" }, { it.copy(id = 0L) }, dao::insertActivitySession,
        )
        val events = mergeRoot(
            file.calendarEvent, existing.calendarEvent, { it.id },
            { "${it.startDay}|${it.startMinuteOfDay}|${it.title.lowercase()}" },
            { it.copy(id = 0L, linkedActivityId = activities.target(it.linkedActivityId)) },
            dao::insertCalendarEvent,
        )
        val goals = mergeRoot(
            file.goal, existing.goal, { it.id }, { "${it.type}|${it.title.lowercase()}|${it.targetDay}" },
            { it.copy(id = 0L, linkedEventId = events.target(it.linkedEventId)) }, dao::insertGoal,
        )
        val plans = mergeRoot(
            file.trainingPlan, existing.trainingPlan, { it.id }, { "${it.name.lowercase()}|${it.startDay}" },
            { it.copy(id = 0L, primaryGoalId = goals.target(it.primaryGoalId)) }, dao::insertTrainingPlan,
        )
        val batches = mergeRoot(
            file.suggestionBatch, existing.suggestionBatch, { it.id },
            { "${it.generatedAtMillis}|${it.inputsHash}" }, { it.copy(id = 0L) }, dao::insertSuggestionBatch,
        )
        val templates = mergeRoot(
            file.mealTemplate, existing.mealTemplate, { it.id }, { it.name.lowercase() },
            { it.copy(id = 0L) }, dao::insertMealTemplate,
        )
        val mealLogs = mergeRoot(
            file.mealLog, existing.mealLog, { it.id },
            { "${it.day}|${it.slot}|${it.atMinuteOfDay}|${it.name.orEmpty().lowercase()}" },
            { it.copy(id = 0L, templateId = templates.target(it.templateId)) }, dao::insertMealLog,
        )
        written += ingredients.inserted + activities.inserted + events.inserted + goals.inserted +
            plans.inserted + batches.inserted + templates.inserted + mealLogs.inserted

        written += mergeRoot(
            file.activitySourceRecord, existing.activitySourceRecord, { it.id },
            { "${it.source}|${it.externalId}" },
            { it.copy(id = 0L, activityId = activities.target(it.activityId)) },
            dao::insertActivitySourceRecord,
        ).inserted
        written += mergeRoot(
            file.bodyMeasurement, existing.bodyMeasurement, { it.id },
            { it.externalId?.let { ext -> "${it.source}|$ext" } ?: "manual|${it.measuredAtMillis}" },
            { it.copy(id = 0L) }, dao::insertBodyMeasurement,
        ).inserted
        written += mergeRoot(
            file.sleepSession, existing.sleepSession, { it.id }, { "${it.night}" },
            { it.copy(id = 0L) }, dao::insertSleepSession,
        ).inserted
        // P14: a workout is identified by its built-in id, or by its name when the user made it.
        // Merging by name is what stops a restore from duplicating "Upper A" on every import.
        val workouts = mergeRoot(
            file.strengthWorkout, existing.strengthWorkout, { it.id },
            { it.templateId ?: "custom|${it.name.lowercase()}" }, { it.copy(id = 0L) },
            dao::insertStrengthWorkout,
        )
        written += workouts.inserted
        val plannedSessions = mergeRoot(
            file.plannedSession, existing.plannedSession, { it.id },
            { "${it.day}|${it.sportType}|${it.sessionType}|${it.startMinuteOfDay}" },
            {
                it.copy(
                    id = 0L,
                    planId = plans.target(it.planId),
                    linkedActivityId = activities.target(it.linkedActivityId),
                    sourceSuggestionId = null,
                    workoutId = workouts.target(it.workoutId),
                )
            },
            dao::insertPlannedSession,
        )
        written += plannedSessions.inserted
        written += mergeRoot(
            file.waterLog, existing.waterLog, { it.id }, { "${it.day}|${it.atMinuteOfDay}|${it.ml}" },
            { it.copy(id = 0L) }, dao::insertWaterLog,
        ).inserted
        written += mergeRoot(
            file.runningBest, existing.runningBest, { it.id },
            { "${it.distanceMeters}|${it.timeSec}|${it.day}|${it.method}" },
            { it.copy(id = 0L, activityId = activities.target(it.activityId)) }, dao::insertRunningBest,
        ).inserted
        // P12: `ride_best` mirrors `running_best` — the effort itself is the natural key, and the
        // activity link is remapped so a restored best still points at the right ride.
        written += mergeRoot(
            file.rideBest, existing.rideBest, { it.id },
            { "${it.kind}|${it.value}|${it.day}" },
            { it.copy(id = 0L, activityId = activities.target(it.activityId)) }, dao::insertRideBest,
        ).inserted
        written += mergeRoot(
            file.importRecord, existing.importRecord, { it.id }, { it.fileHashSha256 },
            { it.copy(id = 0L) }, dao::insertImportRecord,
        ).inserted
        // P11.1: `periodStartDay` is the natural key (and the table's unique index), so a merge
        // never produces two cycles anchored on the same day.
        written += mergeRoot(
            file.cycleEntry, existing.cycleEntry, { it.id }, { "${it.periodStartDay}" },
            { it.copy(id = 0L) }, dao::insertCycleEntry,
        ).inserted

        // P14: one logged set is uniquely identified by when it was completed plus which set of
        // which exercise it was; both of its links are soft, so an unmatched header just drops out.
        written += mergeRoot(
            file.strengthSetLog, existing.strengthSetLog, { it.id },
            { "${it.completedAtMillis}|${it.exerciseId}|${it.setIndex}" },
            {
                it.copy(
                    id = 0L,
                    plannedSessionId = plannedSessions.target(it.plannedSessionId),
                    activityId = activities.target(it.activityId),
                )
            },
            dao::insertStrengthSetLog,
        ).inserted

        written += mergeChildren(
            file.strengthWorkoutExercise, { it.workoutId }, workouts,
            { row, parent -> row.copy(id = 0L, workoutId = parent) },
            dao::insertStrengthWorkoutExercise,
        )
        written += mergeChildren(
            file.activityStream, { it.activityId }, activities,
            { row, parent -> row.copy(activityId = parent) }, dao::insertActivityStream,
        )
        written += mergeChildren(
            file.activityLap, { it.activityId }, activities,
            { row, parent -> row.copy(id = 0L, activityId = parent) }, dao::insertActivityLap,
        )
        written += mergeChildren(
            file.eventOverride, { it.eventId }, events,
            { row, parent -> row.copy(id = 0L, eventId = parent) }, dao::insertEventOverride,
        )
        written += mergeChildren(
            file.suggestedSession, { it.batchId }, batches,
            { row, parent -> row.copy(id = 0L, batchId = parent) }, dao::insertSuggestedSession,
        )
        written += mergeChildren(
            file.mealTemplateItem.filter { ingredients.target(it.ingredientId) != null },
            { it.templateId }, templates,
            { row, parent ->
                row.copy(
                    id = 0L,
                    templateId = parent,
                    ingredientId = checkNotNull(ingredients.target(row.ingredientId)),
                )
            },
            dao::insertMealTemplateItem,
        )
        written += mergeChildren(
            file.mealLogItem, { it.mealLogId }, mealLogs,
            { row, parent ->
                row.copy(id = 0L, mealLogId = parent, ingredientId = ingredients.target(row.ingredientId))
            },
            dao::insertMealLogItem,
        )
        return written
    }

    /** A table keyed by its own primary key: insert only the keys the device does not have. */
    private suspend fun <T : Any, K> mergeKeyed(
        incoming: List<T>,
        existing: List<T>,
        key: (T) -> K,
        insert: suspend (List<T>) -> List<Long>,
    ): Int {
        val have = existing.mapTo(mutableSetOf(), key)
        val rows = incoming.filterNot { key(it) in have }
        return if (rows.isEmpty()) 0 else insert(rows).size
    }

    /**
     * A table with a natural key: rows already present map old id → existing id, the rest are
     * inserted with a fresh id. Two incoming rows sharing a key collapse onto the same new row.
     */
    private suspend fun <T : Any> mergeRoot(
        incoming: List<T>,
        existing: List<T>,
        id: (T) -> Long,
        key: (T) -> String,
        prepare: (T) -> T,
        insert: suspend (List<T>) -> List<Long>,
    ): Remap {
        val remap = Remap()
        val known = existing.associateBy(key)
        val pending = LinkedHashMap<String, T>()
        val oldIdsByKey = LinkedHashMap<String, MutableList<Long>>()
        for (row in incoming) {
            val k = key(row)
            val hit = known[k]
            if (hit != null) {
                remap.ids[id(row)] = id(hit)
            } else {
                if (k !in pending) pending[k] = row
                oldIdsByKey.getOrPut(k) { mutableListOf() }.add(id(row))
            }
        }
        if (pending.isEmpty()) return remap
        val keys = pending.keys.toList()
        val newIds = insert(keys.map { prepare(pending.getValue(it)) })
        remap.inserted = newIds.size
        keys.forEachIndexed { index, k ->
            oldIdsByKey.getValue(k).forEach { oldId ->
                remap.ids[oldId] = newIds[index]
                remap.created += oldId
            }
        }
        return remap
    }

    /** Owned children: inserted only under a parent this merge created. */
    private suspend fun <T : Any> mergeChildren(
        incoming: List<T>,
        parentOldId: (T) -> Long,
        parents: Remap,
        prepare: (T, Long) -> T,
        insert: suspend (List<T>) -> List<Long>,
    ): Int {
        val rows = incoming
            .filter { parents.isNew(parentOldId(it)) }
            .mapNotNull { row -> parents.target(parentOldId(row))?.let { prepare(row, it) } }
        return if (rows.isEmpty()) 0 else insert(rows).size
    }
}
