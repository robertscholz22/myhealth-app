package com.myhealth.domain.engine.suggest

import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.RationaleEntry
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.datetime.DayOfWeek

/** Where a [GridItem] came from — fixed items (§3.5.6 step 1) versus the engine's own placements. */
enum class ItemOrigin { EVENT, LOCKED_PLANNED, BLOCKED, SUGGESTED }

/**
 * One thing occupying one day of the planning grid: a calendar event, a locked planned session, a
 * `BLOCKED` marker, or a session the engine placed.
 */
data class GridItem(
    val origin: ItemOrigin,
    val sessionType: SessionType,
    val sportType: SportType,
    val intensity: Intensity,
    val minutes: Int,
    val estTrimp: Double,
    val eventType: EventType? = null,
    val score: Double = 0.0,
    val rationale: List<RationaleEntry> = emptyList(),
    /**
     * Placed by post-pass 7c ([ActiveRecovery]): an easy run or spin that fills a rest day the way
     * mobility does — it keeps the day a rest day for C3 and never counts against a cap or the
     * budget. A `RECOVERY_RUN` the greedy loop placed as a real session has this `false`.
     */
    val isActiveRecovery: Boolean = false,
) {
    val isFixed: Boolean get() = origin != ItemOrigin.SUGGESTED
    val isBlocked: Boolean get() = origin == ItemOrigin.BLOCKED
    val isMobility: Boolean get() = sessionType == SessionType.MOBILITY

    /** Mobility or an active-recovery filler: a day holding only these is still a rest day. */
    val isRestDayFiller: Boolean get() = isMobility || isActiveRecovery
    val isHard: Boolean get() = intensity == Intensity.HIGH || intensity == Intensity.MAX
    val sportGroup: SportGroup get() = sportType.group

    /** A match or a race — the two events C1/C2/C4 protect. */
    val isKeyEvent: Boolean
        get() = eventType == EventType.SOCCER_MATCH || eventType == EventType.RACE
}

/** One day of the grid (§3.5.6 step 1). */
data class DayPlan(val day: Long, val items: List<GridItem> = emptyList()) {

    val isBlocked: Boolean get() = items.any { it.isBlocked }

    /** Everything that actually costs the athlete something — a `BLOCKED` marker does not. */
    val sessions: List<GridItem> get() = items.filterNot { it.isBlocked }

    /** Sessions that carry real training stress: everything but mobility and active recovery. */
    val nonMobilitySessions: List<GridItem> get() = sessions.filterNot { it.isRestDayFiller }

    /**
     * §3.5.3 C3 / §3.5.6 steps 7c–7d: a rest day is a day with no session above mobility — "a day
     * with only `MOBILITY` still counts as a rest day" — and, since 0.3.0, a day holding only an
     * active-recovery filler (an easy run or spin placed by [ActiveRecovery]) counts the same way.
     */
    val isRestDay: Boolean get() = nonMobilitySessions.isEmpty()

    /** A rest day that holds nothing but mobility (or nothing at all): the week's true rest day. */
    val isTrueRestDay: Boolean get() = isRestDay && sessions.none { it.isActiveRecovery }

    /** A day that can still take a suggestion: nothing on it at all. */
    val isCompletelyFree: Boolean get() = items.isEmpty()

    val fixedLoad: Double get() = items.filter { it.isFixed }.sumOf { it.estTrimp }

    fun plus(item: GridItem): DayPlan = copy(items = items + item)

    fun minus(item: GridItem): DayPlan {
        val index = items.indexOf(item)
        return if (index < 0) this else copy(items = items.filterIndexed { i, _ -> i != index })
    }
}

/**
 * The `[today, today + horizonDays)` planning grid (§3.5.6 step 1). Immutable: placing a session
 * returns a new grid, which is what lets the greedy loop re-evaluate constraints against "the grid
 * as it currently stands" without any hidden mutation.
 */
data class SuggestionGrid(val startDay: Long, val endDayExclusive: Long, val days: List<DayPlan>) {

    val dayCount: Int get() = days.size

    fun dayAt(day: Long): DayPlan? = days.firstOrNull { it.day == day }

    fun itemsOn(day: Long): List<GridItem> = dayAt(day)?.items ?: emptyList()

    /** Every `(day, item)` pair in the grid, ascending by day. */
    fun entries(): List<Pair<Long, GridItem>> = days.flatMap { plan -> plan.items.map { plan.day to it } }

    fun suggested(): List<Pair<Long, GridItem>> = entries().filter { it.second.origin == ItemOrigin.SUGGESTED }

    fun place(day: Long, item: GridItem): SuggestionGrid =
        copy(days = days.map { if (it.day == day) it.plus(item) else it })

    fun remove(day: Long, item: GridItem): SuggestionGrid =
        copy(days = days.map { if (it.day == day) it.minus(item) else it })

    val fixedLoad: Double get() = days.sumOf { it.fixedLoad }

    val suggestedLoad: Double get() = suggested().sumOf { it.second.estTrimp }

    /**
     * Every rolling 7-day window of the horizon (C3/C5/C10). A horizon shorter than a week has a
     * single window covering all of it, so the "one rest day per week" rule still applies.
     */
    fun rollingWindows(windowDays: Int = Periodization.DAYS_PER_WEEK): List<LongRange> {
        if (dayCount <= windowDays) return listOf(startDay until endDayExclusive)
        return (startDay..(endDayExclusive - windowDays)).map { start -> start until (start + windowDays) }
    }

    companion object {

        /**
         * Step 1 of §3.5.6: seed every day with its fixed items — match / training / race events as
         * [SessionCatalog] entries, locked planned sessions, and `BLOCKED` markers. Events outside
         * `[today, today + horizonDays)` are **not** seeded (they still reach C1/C2 through
         * [ConstraintContext.matchOrRaceDays], which spans the horizon + 3 days).
         */
        fun seed(input: SuggestionInput): SuggestionGrid {
            val start = input.todayDay
            val end = input.horizonEndDay
            val days = (start until end).map { day ->
                val eventItems = input.events
                    .filter { it.occurrenceDay == day }
                    .mapNotNull { occurrence -> eventItem(occurrence.type, occurrence.effectiveDurationMin) }
                val lockedItems = input.lockedPlanned
                    .filter { it.day == day && it.locked }
                    .map(::lockedItem)
                DayPlan(day = day, items = eventItems + lockedItems)
            }
            return SuggestionGrid(startDay = start, endDayExclusive = end, days = days)
        }

        private fun eventItem(type: EventType, durationMin: Int?): GridItem? {
            if (type == EventType.BLOCKED) {
                return GridItem(
                    origin = ItemOrigin.BLOCKED,
                    sessionType = SessionType.REST,
                    sportType = SportType.OTHER,
                    intensity = Intensity.RECOVERY,
                    minutes = 0,
                    estTrimp = 0.0,
                    eventType = type,
                )
            }
            val entry = SessionCatalog.forEvent(type) ?: return null
            val minutes = durationMin ?: entry.defaultMin
            return GridItem(
                origin = ItemOrigin.EVENT,
                sessionType = entry.sessionType,
                sportType = entry.sportType,
                intensity = entry.intensity,
                minutes = minutes,
                estTrimp = entry.estTrimpFor(minutes),
                eventType = type,
            )
        }

        private fun lockedItem(session: PlannedSession): GridItem {
            val entry = SessionCatalog.entryFor(session.sessionType)
            val minutes = session.targetDurationMin ?: entry?.defaultMin ?: 45
            return GridItem(
                origin = ItemOrigin.LOCKED_PLANNED,
                sessionType = session.sessionType,
                sportType = session.sportType,
                intensity = session.intensity,
                minutes = minutes,
                estTrimp = session.estimatedTrimp ?: entry?.estTrimpFor(minutes) ?: 0.0,
            )
        }
    }
}

/**
 * `Profile.preferredSportsJson` as the suggester reads it (C10/C12). The object is the
 * `{"RUN":3,"STRENGTH":2,"SOCCER":2}` map `ui/common/PreferredSports.kt` writes, optionally with a
 * `"longRunWeekday"` string (`"SATURDAY"`); unknown keys and malformed JSON degrade to "no
 * preference" rather than throwing — the §2.1 converter rule applied to a preferences blob.
 */
object SportPreferences {

    private val json = Json { ignoreUnknownKeys = true }

    const val LONG_RUN_WEEKDAY_KEY: String = "longRunWeekday"

    /**
     * POLISH-11: when every configured sport is capped at 0 (a skipped/cleared onboarding step,
     * not a deliberate "no running, no strength, no soccer" choice), that is read as "no caps
     * configured" rather than "ban every sport" — otherwise C10 discards every catalog candidate
     * and the suggester can only propose cross-training/mobility. A single sport explicitly left
     * at 0 alongside a non-zero one is still a real cap on that sport.
     */
    fun capsOf(preferredSportsJson: String): Map<SportGroup, Int> {
        val caps = objectOf(preferredSportsJson)
            ?.entries
            ?.mapNotNull { (key, value) ->
                val group = SportGroup.entries.firstOrNull { it.name == key } ?: return@mapNotNull null
                val cap = (value as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                group to cap
            }
            ?.toMap()
            .orEmpty()
        return if (caps.isNotEmpty() && caps.values.all { it == 0 }) emptyMap() else caps
    }

    fun longRunWeekdayOf(preferredSportsJson: String): DayOfWeek? {
        val raw = (objectOf(preferredSportsJson)?.get(LONG_RUN_WEEKDAY_KEY) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content ?: return null
        return DayOfWeek.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }

    private fun objectOf(raw: String): JsonObject? = try {
        json.parseToJsonElement(raw) as? JsonObject
    } catch (e: Exception) {
        null
    }
}
