package com.myhealth.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.myhealth.MyHealthApp
import com.myhealth.domain.model.ImportKind
import com.myhealth.domain.model.ImportProgress

/**
 * Runs one FIT/CSV/ZIP import off the UI (PLAN P7.5/P7.6): a 100 MB Garmin export takes minutes,
 * so it must survive a rotation or the screen being left — hence a `CoroutineWorker` rather than a
 * `viewModelScope` job. Progress is republished through `setProgress`, which the Import screen
 * observes via [SyncScheduler.observeImportState].
 *
 * The graph is read from `(applicationContext as MyHealthApp).graph` like every other worker
 * (§1.3); no `WorkerFactory` is registered.
 */
class ImportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as MyHealthApp).graph
        val uri = inputData.getString(KEY_URI)
            ?: return Result.failure(reason("No document was handed to the import."))
        val kindName = inputData.getString(KEY_KIND)
        val kind = ImportKind.entries.firstOrNull { it.name == kindName }
            ?: return Result.failure(reason("Unsupported file type."))

        var terminal: ImportProgress? = null
        graph.importService.import(uri, kind, inputData.getBoolean(KEY_FORCE, false)).collect { progress ->
            when (progress) {
                is ImportProgress.Working -> setProgress(
                    workDataOf(
                        KEY_PARSED to progress.counts.parsed,
                        KEY_INSERTED to (progress.counts.inserted + progress.counts.merged),
                        KEY_DUPLICATE to progress.counts.duplicate,
                        KEY_FAILED to progress.counts.failed,
                        KEY_CURRENT_ITEM to progress.currentItem,
                    ),
                )
                is ImportProgress.Started -> Unit
                else -> terminal = progress
            }
        }

        return when (val end = terminal) {
            is ImportProgress.Finished -> Result.success(
                workDataOf(
                    KEY_PARSED to end.counts.parsed,
                    KEY_INSERTED to (end.counts.inserted + end.counts.merged),
                    KEY_DUPLICATE to end.counts.duplicate,
                    KEY_FAILED to end.counts.failed,
                    KEY_RECORD_ID to end.record.id,
                ),
            )
            is ImportProgress.AlreadyImported -> Result.success(
                workDataOf(
                    KEY_ALREADY_IMPORTED to true,
                    KEY_RECORD_ID to end.previous.id,
                    KEY_FAILURE_REASON to end.previous.fileName,
                ),
            )
            is ImportProgress.Failed -> Result.failure(reason(end.error.toString()))
            else -> Result.failure(reason("The import produced no result."))
        }
    }

    private fun reason(message: String): Data = workDataOf(KEY_FAILURE_REASON to message)

    companion object {
        const val KEY_URI: String = "uri"
        const val KEY_KIND: String = "kind"
        const val KEY_FORCE: String = "force"
        const val KEY_PARSED: String = "parsed"
        const val KEY_INSERTED: String = "inserted"
        const val KEY_DUPLICATE: String = "duplicate"
        const val KEY_FAILED: String = "failed"
        const val KEY_CURRENT_ITEM: String = "currentItem"
        const val KEY_RECORD_ID: String = "recordId"
        const val KEY_ALREADY_IMPORTED: String = "alreadyImported"
        const val KEY_FAILURE_REASON: String = "failureReason"

        /** Folds the work slot's `WorkInfo`s into what the screen renders. */
        fun stateOf(infos: List<WorkInfo>): ImportWorkState {
            val info = infos.lastOrNull() ?: return ImportWorkState()
            val running = infos.firstOrNull {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
            }
            if (running != null) {
                return ImportWorkState(
                    stage = ImportWorkState.Stage.RUNNING,
                    parsed = running.progress.getInt(KEY_PARSED, 0),
                    inserted = running.progress.getInt(KEY_INSERTED, 0),
                    duplicate = running.progress.getInt(KEY_DUPLICATE, 0),
                    failed = running.progress.getInt(KEY_FAILED, 0),
                    currentItem = running.progress.getString(KEY_CURRENT_ITEM),
                )
            }
            return when (info.state) {
                WorkInfo.State.SUCCEEDED -> succeeded(info)
                WorkInfo.State.FAILED -> ImportWorkState(
                    stage = ImportWorkState.Stage.FAILED,
                    message = info.outputData.getString(KEY_FAILURE_REASON),
                )
                else -> ImportWorkState()
            }
        }

        private fun succeeded(info: WorkInfo): ImportWorkState {
            val data = info.outputData
            return if (data.getBoolean(KEY_ALREADY_IMPORTED, false)) {
                ImportWorkState(
                    stage = ImportWorkState.Stage.ALREADY_IMPORTED,
                    message = data.getString(KEY_FAILURE_REASON),
                )
            } else {
                ImportWorkState(
                    stage = ImportWorkState.Stage.DONE,
                    parsed = data.getInt(KEY_PARSED, 0),
                    inserted = data.getInt(KEY_INSERTED, 0),
                    duplicate = data.getInt(KEY_DUPLICATE, 0),
                    failed = data.getInt(KEY_FAILED, 0),
                )
            }
        }
    }
}
