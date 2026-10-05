package com.myhealth.ui.load

import com.myhealth.domain.engine.strength.MuscleLoadState
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.MuscleLoadBand
import com.myhealth.resources.*
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.StringResource

/**
 * Pure display logic for the Load & Recovery "Muscle load" card (PLAN §3.12.4 / §4.2, P14.8): the
 * three most-loaded groups with their AU and band, the heat-map intensity [BodyFigure] draws, and
 * the one-line hint the owner asked for ("no leg day after an intense run, but upper body would be
 * ok"). Kept free of `Compose`/`stringResource` so it is unit-tested in `MuscleLoadUiStateTest`
 * without Robolectric — the screen resolves [MuscleLoadHint] to its string, the same way
 * `ZonesUiState` resolves `ConfidenceMessageKind`.
 */

/** One row of the card's top-three list: a group, its load rounded to whole AU, and its band. */
data class MuscleLoadRow(val group: MuscleGroup, val au: Int, val band: MuscleLoadBand)

/** Which one-line hint the card (and, gated further, Today's chip) shows — `null` is "nothing". */
enum class MuscleLoadHint { UPPER_DAY_FITS, LOWER_DAY_FITS }

/** How many groups the card lists (`mlui01`). */
private const val TOP_ROW_COUNT = 3

/**
 * The three most-loaded groups, AU descending; ties are broken by [MuscleGroup]'s own declared
 * order so the list never reshuffles between two equal-AU groups from one render to the next
 * (`mlui01`).
 */
fun topMuscleLoadRows(state: MuscleLoadState): List<MuscleLoadRow> =
    state.byGroup.entries
        .sortedWith(compareByDescending<Map.Entry<MuscleGroup, Double>> { it.value }.thenBy { it.key.ordinal })
        .take(TOP_ROW_COUNT)
        .map { (group, load) -> MuscleLoadRow(group = group, au = load.roundToInt(), band = state.bandOf(group)) }

/** The band label string, reusing [BodyFigureLegend]'s own fresh/loaded/fatigued swatches text
 * (`body_figure_legend_*`) so the card's row list can never disagree with its own legend (`mlui02`). */
fun muscleLoadBandLabelRes(band: MuscleLoadBand): StringResource = when (band) {
    MuscleLoadBand.FRESH -> Res.string.body_figure_legend_fresh
    MuscleLoadBand.LOADED -> Res.string.body_figure_legend_loaded
    MuscleLoadBand.FATIGUED -> Res.string.body_figure_legend_fatigued
}

/**
 * The [com.myhealth.ui.common.body.BodyFigure] heat-map highlight for a [MuscleLoadState]: `load /
 * ref` clamped to `0..1` per group (§3.12.2's "the same composable is the Load screen's heat map"),
 * every [MuscleGroup] included so the figure never leaves a group unpainted (`mlui03`).
 */
fun muscleLoadHeatMap(state: MuscleLoadState): Map<MuscleGroup, Float> =
    MuscleGroup.entries.associateWith { state.intensityOf(it).toFloat() }

/** Whether there is anything at all to show — no session in the 14-day window leaves every group
 * at `0.0` AU, which the card treats as its empty state (`mlui04`). */
fun hasMuscleLoadActivity(state: MuscleLoadState): Boolean = state.byGroup.values.any { it > 0.0 }

/**
 * The card's hint (§4.2 Load & recovery, P14.8): an upper-body day when the legs are anything but
 * fresh and the rest of the body is, a lower-body day when the legs themselves are fresh, nothing
 * in every other case (`mlui04`).
 */
fun muscleLoadHint(state: MuscleLoadState): MuscleLoadHint? = when {
    state.lowerBody != MuscleLoadBand.FRESH && state.upperBody == MuscleLoadBand.FRESH ->
        MuscleLoadHint.UPPER_DAY_FITS
    state.lowerBody == MuscleLoadBand.FRESH -> MuscleLoadHint.LOWER_DAY_FITS
    else -> null
}

/**
 * Today's chip (§4.2 Today, P14.8) is the same hint, shown only in the more urgent case: at least
 * one lower-body group is actually `FATIGUED` (not merely `LOADED`) — since a group's band is a
 * monotonic function of its load, "any lower-body group is FATIGUED" is exactly
 * `state.lowerBody == FATIGUED` (`lowerBody` already tracks the *most loaded* of the five). `LOWER_
 * DAY_FITS` can never coexist with a fatigued lower body, so this only ever yields `UPPER_DAY_FITS`
 * or `null`.
 */
fun todayMuscleLoadHint(state: MuscleLoadState): MuscleLoadHint? =
    muscleLoadHint(state).takeIf { state.lowerBody == MuscleLoadBand.FATIGUED }

/** The hint's string resource — "Legs are loaded — an upper-body day fits today" / "Legs are
 * fresh — a lower-body day fits". */
fun MuscleLoadHint.labelRes(): StringResource = when (this) {
    MuscleLoadHint.UPPER_DAY_FITS -> Res.string.load_muscle_hint_upper
    MuscleLoadHint.LOWER_DAY_FITS -> Res.string.load_muscle_hint_lower
}
