package com.myhealth.ui.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The scan screen's pure state transitions — in particular the "Scan from photo" path added for
 * NOTE-6, whose ML Kit / picker halves cannot be exercised off a device.
 */
class ScanUiStateTest {

    @Test
    fun picking_a_photo_starts_processing_and_mutes_the_live_analyser() {
        val started = ScanUiState(mode = ScanMode.BARCODE, lastError = "old", manualBarcode = "123")
            .photoStarted()

        assertThat(started.isProcessing).isTrue()
        assertThat(started.isFromPhoto).isTrue()
        assertThat(started.analysisActive).isFalse()
        assertThat(started.lastError).isNull()
        assertThat(started.manualBarcode).isNull()
    }

    @Test
    fun dismissing_the_picker_restores_the_analyser_only_in_barcode_mode() {
        val barcode = ScanUiState(mode = ScanMode.BARCODE).photoStarted().photoCancelled()
        assertThat(barcode.isProcessing).isFalse()
        assertThat(barcode.isFromPhoto).isFalse()
        assertThat(barcode.analysisActive).isTrue()

        val label = ScanUiState(mode = ScanMode.LABEL).photoStarted().photoCancelled()
        assertThat(label.analysisActive).isFalse()
    }

    @Test
    fun a_photo_failure_surfaces_the_message_and_ends_the_photo_run() {
        val failed = ScanUiState().photoStarted().photoFailed("No barcode was found in that picture.")

        assertThat(failed.isProcessing).isFalse()
        assertThat(failed.isFromPhoto).isFalse()
        assertThat(failed.lastError).isEqualTo("No barcode was found in that picture.")
        assertThat(failed.nav).isNull()
    }

    @Test
    fun a_recognised_label_navigates_to_the_review_screen_from_either_source() {
        assertThat(ScanUiState().photoStarted().reviewReady().nav).isEqualTo(ScanNav.Review)

        val fromCamera = ScanUiState(isProcessing = true).reviewReady()
        assertThat(fromCamera.nav).isEqualTo(ScanNav.Review)
        assertThat(fromCamera.isProcessing).isFalse()
        assertThat(fromCamera.isFromPhoto).isFalse()
    }

    @Test
    fun barcode_mode_is_derived_from_the_mode() {
        assertThat(ScanUiState(mode = ScanMode.BARCODE).isBarcodeMode).isTrue()
        assertThat(ScanUiState(mode = ScanMode.LABEL).isBarcodeMode).isFalse()
    }
}
