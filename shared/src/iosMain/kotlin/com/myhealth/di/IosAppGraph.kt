package com.myhealth.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.myhealth.data.backup.BackupContentSource
import com.myhealth.data.db.MyHealthDatabase
import com.myhealth.data.db.migration.Migrations
import com.myhealth.data.prefs.DataStoreSettingsRepository
import com.myhealth.data.repository.ImportContentSource
import com.myhealth.data.repository.TransactionRunner
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.SystemPlatformClock
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.sync.InProcessSyncScheduler
import com.myhealth.sync.SyncScheduler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * The iOS dependency container (P21): [CoreGraph] plus what the iOS shell opens itself — the
 * database and settings file in the app's Documents directory (bundled SQLite, so the schema and
 * migrations are exactly Android's), `file://` document access, and the in-process scheduler.
 * HealthKit, background tasks, the camera and the document pickers follow in P22.
 */
class IosAppGraph : CoreGraph() {

    override val clock: PlatformClock = SystemPlatformClock

    override val db: MyHealthDatabase by lazy {
        Room.databaseBuilder<MyHealthDatabase>(name = documentsPath(MyHealthDatabase.NAME))
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*Migrations.ALL)
            .build()
    }

    override val transactionRunner: TransactionRunner by lazy {
        object : TransactionRunner {
            override suspend fun <T> invoke(block: suspend () -> T): T =
                db.useWriterConnection { connection -> connection.immediateTransaction { block() } }
        }
    }

    private val files: FileContentSource by lazy { FileContentSource() }

    override val importContent: ImportContentSource get() = files

    override val backupContent: BackupContentSource get() = files

    override val appVersion: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0.0.0"

    /** Same file name as Android's `settings.preferences_pb`. */
    override val settings: SettingsRepository by lazy {
        DataStoreSettingsRepository(
            PreferenceDataStoreFactory.createWithPath { documentsPath("settings.preferences_pb").toPath() },
        )
    }

    override val hcIntegration: HcIntegration = HealthKitPending

    override val syncScheduler: SyncScheduler by lazy {
        InProcessSyncScheduler(
            scope = appScope,
            importer = { importService },
            recomputeLoad = { fromDay -> loadRecomputeService.recompute(fromDay) },
            recomputeTargets = { targetRecomputeService.recompute().failure == null },
            today = { clock.todayEpochDay() },
        )
    }

    companion object {
        /** One graph per process, created and started by the first [com.myhealth.MainViewController]. */
        val instance: IosAppGraph by lazy { IosAppGraph().also { it.start() } }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun documentsPath(fileName: String): String {
    val documents = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    return requireNotNull(documents?.path) { "No Documents directory" } + "/" + fileName
}

/** Health access is not wired yet (HealthKit, P22): the Integrations screen shows "unavailable". */
private object HealthKitPending : HcIntegration {
    override fun status(): HcStatus = HcStatus.UNAVAILABLE
    override suspend fun granted(): Set<String> = emptySet()
    override val allPermissions: Set<String> = emptySet()
    override val optionalDetailPermissions: Set<String> = emptySet()
}

/**
 * Documents by `file://` URL or plain path — what the share sheet and the Files app hand an app
 * once the document has been copied into its sandbox (P22 adds the pickers).
 */
private class FileContentSource : ImportContentSource, BackupContentSource {

    private fun pathOf(uri: String): Path =
        (if (uri.startsWith("file:")) NSURL.URLWithString(uri)?.path ?: uri.removePrefix("file://") else uri).toPath()

    override suspend fun displayName(uri: String): String = pathOf(uri).name

    override suspend fun openSource(uri: String): Source = FileSystem.SYSTEM.source(pathOf(uri))

    override suspend fun openInput(uri: String): Source = FileSystem.SYSTEM.source(pathOf(uri))

    override suspend fun openOutput(uri: String): Sink = FileSystem.SYSTEM.sink(pathOf(uri))
}
