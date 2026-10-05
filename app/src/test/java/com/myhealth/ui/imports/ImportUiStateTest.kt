package com.myhealth.ui.imports

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ImportKind
import com.myhealth.domain.repository.ImportKinds
import com.myhealth.sync.ImportWorkState
import org.junit.Test

/** The pure parts of the Import screen's state (PLAN P7.6). */
class ImportUiStateTest {

    @Test
    fun mime_types_fall_back_for_documents_without_a_useful_name() {
        assertThat(ImportKinds.forMimeType("application/zip")).isEqualTo(ImportKind.GARMIN_ZIP)
        assertThat(ImportKinds.forMimeType("text/csv")).isEqualTo(ImportKind.GARMIN_CSV)
        assertThat(ImportKinds.forMimeType("text/comma-separated-values"))
            .isEqualTo(ImportKind.GARMIN_CSV)
        // A .fit file arrives as octet-stream, which is too generic to act on by itself.
        assertThat(ImportKinds.forMimeType("application/octet-stream")).isNull()
        assertThat(ImportKinds.forMimeType(null)).isNull()
    }

    @Test
    fun the_error_count_of_an_import_record_is_read_from_its_errors_json() {
        assertThat(ImportViewModel.errorCountOf(null)).isEqualTo(0)
        assertThat(ImportViewModel.errorCountOf("")).isEqualTo(0)
        assertThat(ImportViewModel.errorCountOf("[]")).isEqualTo(0)
        assertThat(
            ImportViewModel.errorCountOf(
                """[{"item":"a.fit","message":"bad"},{"item":"b.fit","message":"bad"}]""",
            ),
        ).isEqualTo(2)
        assertThat(ImportViewModel.errorCountOf("not json")).isEqualTo(0)
    }

    @Test
    fun the_screen_is_busy_only_while_the_worker_runs() {
        assertThat(ImportUiState().isRunning).isFalse()
        assertThat(ImportUiState(work = ImportWorkState(stage = ImportWorkState.Stage.RUNNING)).isRunning)
            .isTrue()
        assertThat(ImportUiState(work = ImportWorkState(stage = ImportWorkState.Stage.DONE)).isRunning)
            .isFalse()
    }
}
