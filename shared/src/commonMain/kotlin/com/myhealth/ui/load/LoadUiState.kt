package com.myhealth.ui.load

import androidx.compose.runtime.Composable
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import com.myhealth.domain.engine.load.AcwrZone
import com.myhealth.domain.engine.load.LoadFlags
import com.myhealth.domain.engine.load.LoadSeriesEngine
import com.myhealth.domain.engine.load.RecoveryFlags
import com.myhealth.domain.engine.strength.MuscleLoadState
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.RecoveryComponent
import com.myhealth.domain.model.RecoveryState
import com.myhealth.ui.common.fmtInt
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.StringResource

/** The three history windows the range selector offers (§4.2 Load & recovery, P5.6). */
enum class LoadRange(val days: Int, val labelRes: StringResource) {
    D28(28, Res.string.load_range_28d),
    D90(90, Res.string.load_range_90d),
    D365(365, Res.string.load_range_365d),
}

/** ViewModel state for [LoadScreen] (PLAN §4.2 Load & recovery, P5.6). */
data class LoadUiState(
    val isLoading: Boolean = true,
    val range: LoadRange = LoadRange.D28,
    /** Ascending by day, `[today - range.days + 1, today]`; missing days simply are not cached yet. */
    val series: List<DailyLoad> = emptyList(),
    /** The most recently computed `daily_load` row, regardless of [range]. */
    val latest: DailyLoad? = null,
    /** Recomputed live from the same inputs `LoadRecomputeService` used, so the score's component
     * breakdown is available even though `daily_load` only caches the totals (§2.2.6). */
    val recovery: RecoveryState? = null,
    /** Today's per-muscle-group load (§3.12.4, P14.8), resolved the same way
     * `SuggestionMuscleResolver` assembles it for the suggester — but directly against the domain
     * repositories, since `ui/` may not import `data/` (`ArchitectureTest`). */
    val muscleLoad: MuscleLoadState? = null,
) {
    val hasData: Boolean get() = series.any { it.sessionCount > 0 }
}

/** `acwr` → its risk zone (§3.2.3), or `null` before there is enough chronic load to define one. */
fun acwrZoneOf(acwr: Double?): AcwrZone? = acwr?.let { LoadSeriesEngine.acwrZone(it) }

/**
 * One-line explanation for a `daily_load.flagsCsv` entry (§2.2.6/§3.2.3/§3.3) — an unrecognised
 * flag (forward-compatibility, §2.1's converter rule) falls back to showing its raw name rather
 * than hiding it.
 */
fun flagExplanation(flag: String): String = when (flag) {
    LoadFlags.RAMP_HIGH -> "Weekly training load rose more than 15% versus the week before."
    LoadFlags.HIGH_MONOTONY -> "Training has been very repetitive this week, with little day-to-day variation."
    LoadFlags.HIGH_STRAIN -> "Weekly strain (load x monotony) is unusually high."
    LoadFlags.NO_REST_DAY_7D -> "No full rest day in the last 7 days."
    LoadFlags.INSUFFICIENT_HISTORY -> "Not enough training history yet for a reliable ACWR."
    RecoveryFlags.SLEEP_DEBT -> "Sleep has been running well below your target lately."
    else -> flag
}

/** Recovery band label for the score chip (§3.3); `null` reads as "not enough data yet". */
fun recoveryBandLabel(band: RecoveryBand?): String = when (band) {
    null -> "Not enough data"
    RecoveryBand.FRESH -> "Fresh"
    RecoveryBand.GOOD -> "Good"
    RecoveryBand.MODERATE -> "Moderate"
    RecoveryBand.FATIGUED -> "Fatigued"
    RecoveryBand.STRAINED -> "Strained"
}

/** "Sleep 32/40" — one recovery-component row (§3.3). */
fun componentLabel(component: RecoveryComponent): String =
    "${component.name.lowercase().replaceFirstChar { it.uppercase() }} " +
        "${fmtInt(component.points.roundToInt())}/${fmtInt(component.maxPoints.roundToInt())}"

/** "62% confidence" for the recovery card's footnote. */
@Composable
fun confidencePercentLabel(confidence: Double): String =
    stringResource(Res.string.load_recovery_confidence_label, (confidence * 100).roundToInt())
