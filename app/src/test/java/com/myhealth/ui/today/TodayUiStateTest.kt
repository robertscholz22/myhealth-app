package com.myhealth.ui.today

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlinx.datetime.TimeZone
import java.util.Locale

/** Pure helpers of `TodayUiState.kt` (PLAN P2.10): the sync-status label and the body-chip label.
 * `weightChipLabel` formats through `fmtKg`/`fmtDecimal` (POLISH-12), which follow
 * `Locale.getDefault()`; pinned to `Locale.US` here so the verbatim assertions below are
 * locale-independent. */
class TodayUiStateTest {

    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun today01_lastSyncedLabel_formatsTimeOrReportsNeverSynced() {
        val zone = TimeZone.UTC
        val millis = LocalDateTime.of(2026, 9, 12, 14, 5).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        assertThat(lastSyncedLabel(millis, zone)).isEqualTo("Last synced 14:05")
        assertThat(lastSyncedLabel(null, zone)).isEqualTo("Not yet synced")
    }

    @Test
    fun today02_weightChipLabel_showsSignedDeltaToGoalOrPlainWeight() {
        assertThat(weightChipLabel(78.4, 75.0)).isEqualTo("78.4 kg (+3.4 kg to goal)")
        assertThat(weightChipLabel(73.0, 75.0)).isEqualTo("73.0 kg (-2.0 kg to goal)")
        assertThat(weightChipLabel(78.4, null)).isEqualTo("78.4 kg")
        assertThat(weightChipLabel(null, 75.0)).isNull()
    }
}
