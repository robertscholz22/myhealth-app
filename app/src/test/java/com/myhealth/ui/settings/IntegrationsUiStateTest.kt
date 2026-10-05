package com.myhealth.ui.settings

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.healthconnect.HcPermissions
import org.junit.Test

/**
 * [permissionLabel] must never leave a permission unexplained on the Integrations screen (P2.8) —
 * this only touches `data.healthconnect.HcPermissions` from a test, never from `ui/` main sources
 * (`ArchitectureTest` only scans `src/main`).
 */
class IntegrationsUiStateTest {

    @Test
    fun every_permission_in_hc_permissions_all_has_a_non_blank_label() {
        val offenders = HcPermissions.ALL.filter { permissionLabel(it).isBlank() }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun known_permissions_get_their_curated_label() {
        assertThat(permissionLabel("android.permission.health.READ_SLEEP")).isEqualTo("Sleep")
        assertThat(permissionLabel(HcPermissions.HISTORY)).isEqualTo("Full history (30+ days)")
    }

    @Test
    fun an_unrecognised_permission_still_gets_a_readable_fallback_label() {
        val label = permissionLabel("android.permission.health.READ_SOMETHING_NEW")

        assertThat(label).isEqualTo("Something New")
    }

    @Test
    fun a_newly_granted_power_permission_is_reported_as_new_detail() {
        val power = HcPermissions.OPTIONAL_DETAIL.first { it.endsWith("READ_POWER") }
        val before = HcPermissions.REQUIRED_CORE
        val after = HcPermissions.REQUIRED_CORE + power
        assertThat(newlyGrantedDetailPermissions(before, after, HcPermissions.OPTIONAL_DETAIL))
            .containsExactly(power)
    }

    @Test
    fun no_re_read_when_the_granted_set_was_not_loaded_yet_or_nothing_detail_changed() {
        val power = HcPermissions.OPTIONAL_DETAIL.first { it.endsWith("READ_POWER") }
        val detail = HcPermissions.OPTIONAL_DETAIL
        assertThat(newlyGrantedDetailPermissions(emptySet(), HcPermissions.ALL, detail)).isEmpty()
        assertThat(newlyGrantedDetailPermissions(HcPermissions.ALL, HcPermissions.ALL, detail)).isEmpty()
        assertThat(
            newlyGrantedDetailPermissions(setOf(power), setOf(power, HcPermissions.HISTORY), detail),
        ).isEmpty()
    }
}
