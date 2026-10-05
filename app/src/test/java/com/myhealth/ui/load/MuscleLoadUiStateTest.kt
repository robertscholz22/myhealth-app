package com.myhealth.ui.load

import com.google.common.truth.Truth.assertThat
import com.myhealth.resources.*
import com.myhealth.domain.engine.strength.MuscleLoadEngine
import com.myhealth.domain.engine.strength.MuscleLoadState
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.MuscleLoadBand
import org.junit.Test

/**
 * The Load & Recovery "Muscle load" card's pure display logic (PLAN §3.12.4/§4.2, P14.8):
 * top-three ordering, the band label, the heat-map intensity and the one-line hint.
 */
class MuscleLoadUiStateTest {

    /** A [MuscleLoadState] with [loads] for the named groups (everything else `0.0`), banded and
     * summarised against [ref] the same way [MuscleLoadEngine.compute] would. */
    private fun stateOf(vararg loads: Pair<MuscleGroup, Double>, ref: Double = 1_000.0): MuscleLoadState {
        val byLoad = loads.toMap()
        val byGroup = MuscleGroup.entries.associateWith { byLoad[it] ?: 0.0 }
        val bands = byGroup.mapValues { (_, load) -> MuscleLoadEngine.bandFor(load, ref) }
        val lower = byGroup.filterKeys { it.isLowerBody }.values.max()
        val upper = byGroup.filterKeys { !it.isLowerBody }.values.max()
        return MuscleLoadState(
            byGroup = byGroup,
            bands = bands,
            ref = ref,
            lowerBody = MuscleLoadEngine.bandFor(lower, ref),
            upperBody = MuscleLoadEngine.bandFor(upper, ref),
        )
    }

    /** A state that only carries the two band facts `muscleLoadHint`/`todayMuscleLoadHint` read. */
    private fun bandState(lowerBody: MuscleLoadBand, upperBody: MuscleLoadBand) = MuscleLoadState(
        byGroup = emptyMap(),
        bands = emptyMap(),
        ref = 100.0,
        lowerBody = lowerBody,
        upperBody = upperBody,
    )

    @Test
    fun mlui01_top_three_ordering_and_ties_by_ordinal() {
        // QUADS (ordinal 12) and HAMSTRINGS (ordinal 13) tie at 50.0; CALVES (ordinal 15) is next.
        val state = stateOf(
            MuscleGroup.HAMSTRINGS to 50.0,
            MuscleGroup.QUADS to 50.0,
            MuscleGroup.CALVES to 30.0,
            MuscleGroup.GLUTES to 10.0,
        )
        val rows = topMuscleLoadRows(state)
        assertThat(rows).hasSize(3)
        assertThat(rows.map { it.group }).containsExactly(
            MuscleGroup.QUADS,
            MuscleGroup.HAMSTRINGS,
            MuscleGroup.CALVES,
        ).inOrder()
        assertThat(rows.map { it.au }).containsExactly(50, 50, 30).inOrder()
    }

    @Test
    fun mlui02_band_label_covers_every_band() {
        assertThat(muscleLoadBandLabelRes(MuscleLoadBand.FRESH)).isEqualTo(Res.string.body_figure_legend_fresh)
        assertThat(muscleLoadBandLabelRes(MuscleLoadBand.LOADED)).isEqualTo(Res.string.body_figure_legend_loaded)
        assertThat(muscleLoadBandLabelRes(MuscleLoadBand.FATIGUED)).isEqualTo(Res.string.body_figure_legend_fatigued)
    }

    @Test
    fun mlui03_heat_intensity_is_load_over_ref_clamped() {
        val state = stateOf(
            MuscleGroup.QUADS to 10.0,
            MuscleGroup.HAMSTRINGS to 40.0,
            ref = 20.0,
        )
        val heat = muscleLoadHeatMap(state)
        assertThat(heat).hasSize(MuscleGroup.entries.size)
        assertThat(heat[MuscleGroup.QUADS]).isEqualTo(0.5f)
        assertThat(heat[MuscleGroup.HAMSTRINGS]).isEqualTo(1.0f) // 40/20 = 2.0, clamped to 1.0
        assertThat(heat[MuscleGroup.CALVES]).isEqualTo(0.0f)
    }

    @Test
    fun mlui04_hint_selection_and_empty_state() {
        // Legs loaded (not fresh), arms fresh -> an upper day; Today only escalates at FATIGUED.
        val loadedLegs = bandState(lowerBody = MuscleLoadBand.LOADED, upperBody = MuscleLoadBand.FRESH)
        assertThat(muscleLoadHint(loadedLegs)).isEqualTo(MuscleLoadHint.UPPER_DAY_FITS)
        assertThat(todayMuscleLoadHint(loadedLegs)).isNull()

        val fatiguedLegs = bandState(lowerBody = MuscleLoadBand.FATIGUED, upperBody = MuscleLoadBand.FRESH)
        assertThat(muscleLoadHint(fatiguedLegs)).isEqualTo(MuscleLoadHint.UPPER_DAY_FITS)
        assertThat(todayMuscleLoadHint(fatiguedLegs)).isEqualTo(MuscleLoadHint.UPPER_DAY_FITS)

        // Legs fresh -> a lower day fits, regardless of the rest of the body; never shown on Today.
        val freshLegs = bandState(lowerBody = MuscleLoadBand.FRESH, upperBody = MuscleLoadBand.LOADED)
        assertThat(muscleLoadHint(freshLegs)).isEqualTo(MuscleLoadHint.LOWER_DAY_FITS)
        assertThat(todayMuscleLoadHint(freshLegs)).isNull()

        // Legs loaded and arms not fresh either -> nothing to say.
        val everythingLoaded = bandState(lowerBody = MuscleLoadBand.LOADED, upperBody = MuscleLoadBand.LOADED)
        assertThat(muscleLoadHint(everythingLoaded)).isNull()

        // No session in the last 14 days leaves every group at 0.0 AU: the card's empty state.
        assertThat(hasMuscleLoadActivity(stateOf())).isFalse()
        assertThat(hasMuscleLoadActivity(stateOf(MuscleGroup.QUADS to 1.0))).isTrue()
    }
}
