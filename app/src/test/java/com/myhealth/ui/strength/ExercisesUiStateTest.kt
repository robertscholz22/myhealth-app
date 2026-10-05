package com.myhealth.ui.strength

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.engine.strength.ExerciseKind
import com.myhealth.domain.model.Equipment
import org.junit.Test

/** PLAN §P16 "My equipment"/P16.2's `eqmy01` and §P17's `mobui01`, over the pure
 * [ExercisesUiState]. */
class ExercisesUiStateTest {

    @Test
    fun eqmy01_only_my_equipment_filters() {
        val home = setOf(Equipment.BODYWEIGHT, Equipment.DUMBBELL)

        val filtered = ExercisesUiState(myEquipment = home, onlyMyEquipment = true)
        assertThat(filtered.items).isNotEmpty()
        assertThat(home).containsAtLeastElementsIn(filtered.items.map { it.equipment }.toSet())

        // The switch off shows the full catalog again, equipment untouched.
        val unfiltered = filtered.copy(onlyMyEquipment = false)
        assertThat(unfiltered.items.size).isGreaterThan(filtered.items.size)
        assertThat(unfiltered.items.map { it.equipment }.toSet()).isNotEqualTo(home)

        // `myEquipment == null` ("everything") is unaffected by the switch either way.
        val everything = ExercisesUiState(myEquipment = null, onlyMyEquipment = true)
        assertThat(everything.items).hasSize(ExercisesUiState().items.size)
        assertThat(everything.showOnlyMyEquipmentSwitch).isFalse()
        assertThat(filtered.showOnlyMyEquipmentSwitch).isTrue()
    }

    @Test
    fun mobui01_kind_filter() {
        val all = ExercisesUiState(kind = null)
        assertThat(all.items).containsAtLeastElementsIn(ExerciseCatalog.strength)
        assertThat(all.items).containsAtLeastElementsIn(ExerciseCatalog.mobility)

        val mobility = ExercisesUiState(kind = ExerciseKind.MOBILITY)
        assertThat(mobility.items).isNotEmpty()
        assertThat(mobility.items).containsNoneIn(ExerciseCatalog.strength)
        assertThat(mobility.items).containsExactlyElementsIn(ExerciseCatalog.mobility)

        val strength = ExercisesUiState(kind = ExerciseKind.STRENGTH)
        assertThat(strength.items).isNotEmpty()
        assertThat(strength.items).containsNoneIn(ExerciseCatalog.mobility)
        assertThat(strength.items).containsExactlyElementsIn(ExerciseCatalog.strength)
    }
}
