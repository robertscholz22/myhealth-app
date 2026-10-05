package com.myhealth.data.db.converter

import androidx.room.TypeConverter
import com.myhealth.data.log.PlatformLog
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.DayType
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Feedback
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.ImportKind
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.MealSlot
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.NeatLevel
import com.myhealth.domain.model.PlanStatus
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.QuantityUnit
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.TrainingPhase

private const val LOG_TAG = "MyHealth"

/**
 * Room type converters (PLAN §2.1/§2.2): every domain enum is stored as `TEXT` = `name()`.
 *
 * Decoding never throws. An unknown / forward-incompatible string decodes to the enum's
 * last-resort member (`UNKNOWN` where one exists, otherwise the documented neutral member) and
 * logs a warning, so a database written by a newer build can still be read by an older one.
 *
 * All converters are declared non-null on both sides. Room's null-aware converter store wraps
 * them with the necessary null checks for nullable columns, so a single pair per enum covers both
 * `Foo` and `Foo?` fields.
 */
class Converters {

    private fun <T : Enum<T>> decode(raw: String, values: Array<T>, fallback: T): T {
        val match = values.firstOrNull { it.name == raw }
        if (match == null) {
            PlatformLog.w(LOG_TAG, "Converters: unknown ${fallback::class.simpleName} '$raw' -> $fallback")
        }
        return match ?: fallback
    }

    // ---- Sex -------------------------------------------------------------------------------

    @TypeConverter
    fun fromSex(value: Sex): String = value.name

    @TypeConverter
    fun toSex(value: String): Sex = decode(value, Sex.entries.toTypedArray(), Sex.OTHER)

    // ---- NeatLevel -------------------------------------------------------------------------

    @TypeConverter
    fun fromNeatLevel(value: NeatLevel): String = value.name

    @TypeConverter
    fun toNeatLevel(value: String): NeatLevel =
        decode(value, NeatLevel.entries.toTypedArray(), NeatLevel.LIGHT_ACTIVE)

    // ---- SportType / SportGroup ------------------------------------------------------------

    @TypeConverter
    fun fromSportType(value: SportType): String = value.name

    @TypeConverter
    fun toSportType(value: String): SportType =
        decode(value, SportType.entries.toTypedArray(), SportType.UNKNOWN)

    @TypeConverter
    fun fromSportGroup(value: SportGroup): String = value.name

    @TypeConverter
    fun toSportGroup(value: String): SportGroup =
        decode(value, SportGroup.entries.toTypedArray(), SportGroup.OTHER)

    // ---- ActivitySource / LoadMethod --------------------------------------------------------

    @TypeConverter
    fun fromActivitySource(value: ActivitySource): String = value.name

    @TypeConverter
    fun toActivitySource(value: String): ActivitySource =
        decode(value, ActivitySource.entries.toTypedArray(), ActivitySource.MANUAL)

    @TypeConverter
    fun fromLoadMethod(value: LoadMethod): String = value.name

    @TypeConverter
    fun toLoadMethod(value: String): LoadMethod =
        decode(value, LoadMethod.entries.toTypedArray(), LoadMethod.DURATION_ONLY)

    // ---- Calendar --------------------------------------------------------------------------

    @TypeConverter
    fun fromEventType(value: EventType): String = value.name

    @TypeConverter
    fun toEventType(value: String): EventType =
        decode(value, EventType.entries.toTypedArray(), EventType.OTHER)

    @TypeConverter
    fun fromLinkMethod(value: LinkMethod): String = value.name

    @TypeConverter
    fun toLinkMethod(value: String): LinkMethod =
        decode(value, LinkMethod.entries.toTypedArray(), LinkMethod.MANUAL)

    // ---- Plan / suggestion ------------------------------------------------------------------

    @TypeConverter
    fun fromPlanStatus(value: PlanStatus): String = value.name

    @TypeConverter
    fun toPlanStatus(value: String): PlanStatus =
        decode(value, PlanStatus.entries.toTypedArray(), PlanStatus.DRAFT)

    @TypeConverter
    fun fromSessionType(value: SessionType): String = value.name

    @TypeConverter
    fun toSessionType(value: String): SessionType =
        decode(value, SessionType.entries.toTypedArray(), SessionType.CROSS_TRAINING)

    @TypeConverter
    fun fromIntensity(value: Intensity): String = value.name

    @TypeConverter
    fun toIntensity(value: String): Intensity =
        decode(value, Intensity.entries.toTypedArray(), Intensity.MODERATE)

    @TypeConverter
    fun fromPlannedStatus(value: PlannedStatus): String = value.name

    @TypeConverter
    fun toPlannedStatus(value: String): PlannedStatus =
        decode(value, PlannedStatus.entries.toTypedArray(), PlannedStatus.PLANNED)

    @TypeConverter
    fun fromSuggestionStatus(value: SuggestionStatus): String = value.name

    @TypeConverter
    fun toSuggestionStatus(value: String): SuggestionStatus =
        decode(value, SuggestionStatus.entries.toTypedArray(), SuggestionStatus.PROPOSED)

    @TypeConverter
    fun fromTrainingPhase(value: TrainingPhase): String = value.name

    @TypeConverter
    fun toTrainingPhase(value: String): TrainingPhase =
        decode(value, TrainingPhase.entries.toTypedArray(), TrainingPhase.BASE)

    // ---- Goals -------------------------------------------------------------------------------

    @TypeConverter
    fun fromGoalType(value: GoalType): String = value.name

    @TypeConverter
    fun toGoalType(value: String): GoalType =
        decode(value, GoalType.entries.toTypedArray(), GoalType.CONSISTENCY)

    @TypeConverter
    fun fromGoalStatus(value: GoalStatus): String = value.name

    @TypeConverter
    fun toGoalStatus(value: String): GoalStatus =
        decode(value, GoalStatus.entries.toTypedArray(), GoalStatus.ACTIVE)

    // ---- Nutrition ---------------------------------------------------------------------------

    @TypeConverter
    fun fromMeasureBasis(value: MeasureBasis): String = value.name

    @TypeConverter
    fun toMeasureBasis(value: String): MeasureBasis =
        decode(value, MeasureBasis.entries.toTypedArray(), MeasureBasis.PER_100G)

    @TypeConverter
    fun fromQuantityUnit(value: QuantityUnit): String = value.name

    @TypeConverter
    fun toQuantityUnit(value: String): QuantityUnit =
        decode(value, QuantityUnit.entries.toTypedArray(), QuantityUnit.G)

    @TypeConverter
    fun fromMealSlot(value: MealSlot): String = value.name

    @TypeConverter
    fun toMealSlot(value: String): MealSlot =
        decode(value, MealSlot.entries.toTypedArray(), MealSlot.LUNCH)

    @TypeConverter
    fun fromDayType(value: DayType): String = value.name

    @TypeConverter
    fun toDayType(value: String): DayType =
        decode(value, DayType.entries.toTypedArray(), DayType.REST)

    // ---- Load / import -----------------------------------------------------------------------

    @TypeConverter
    fun fromRecoveryBand(value: RecoveryBand): String = value.name

    @TypeConverter
    fun toRecoveryBand(value: String): RecoveryBand =
        decode(value, RecoveryBand.entries.toTypedArray(), RecoveryBand.MODERATE)

    /**
     * `ride_best.kind` (P12). The last-resort member is [RideBestKind.POWER_5MIN]: an unknown
     * kind written by a newer build is far more likely to be another power window than a time
     * over a distance, and a stray power row only ever shows up as one more line on the Bike
     * screen, whereas decoding it as a `TIME_*` kind would put watts into a seconds column and
     * poison the "fastest 40 km" PR (which is a `MIN`, so a bogus 200 would win outright).
     */
    @TypeConverter
    fun fromRideBestKind(value: RideBestKind): String = value.name

    @TypeConverter
    fun toRideBestKind(value: String): RideBestKind =
        decode(value, RideBestKind.entries.toTypedArray(), RideBestKind.POWER_5MIN)

    /**
     * `strength_workout.kind` (P14). The last-resort member is [StrengthWorkoutKind.CUSTOM]: a kind
     * this build does not know is, by definition, not one of the four it can reason about, and
     * `CUSTOM` is exactly the "a workout the app has no opinion about" bucket.
     */
    @TypeConverter
    fun fromStrengthWorkoutKind(value: StrengthWorkoutKind): String = value.name

    @TypeConverter
    fun toStrengthWorkoutKind(value: String): StrengthWorkoutKind =
        decode(value, StrengthWorkoutKind.entries.toTypedArray(), StrengthWorkoutKind.CUSTOM)

    /**
     * `exercise_progress.lastFeedback` / `strength_set_log.feedback` (P16.1). The last-resort
     * member is [Feedback.HARD] — the neutral one: an unreadable feedback must not move a load.
     */
    @TypeConverter
    fun fromFeedback(value: Feedback): String = value.name

    @TypeConverter
    fun toFeedback(value: String): Feedback =
        decode(value, Feedback.entries.toTypedArray(), Feedback.HARD)

    @TypeConverter
    fun fromImportKind(value: ImportKind): String = value.name

    @TypeConverter
    fun toImportKind(value: String): ImportKind =
        decode(value, ImportKind.entries.toTypedArray(), ImportKind.FIT_FILE)

    // ---- List<String> <-> CSV ----------------------------------------------------------------

    /** Joins with `,`; empty list -> empty string. Values must not themselves contain a comma. */
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(",")

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
