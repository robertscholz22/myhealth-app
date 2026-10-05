package com.myhealth.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.myhealth.data.applehealth.HealthKitAccess
import com.myhealth.data.applehealth.HealthKitReader
import com.myhealth.data.backup.BackupContentSource
import com.myhealth.data.db.MyHealthDatabase
import com.myhealth.data.db.migration.Migrations
import com.myhealth.data.healthconnect.HcBackfill
import com.myhealth.data.healthconnect.HcSyncService
import com.myhealth.data.healthconnect.HealthConnectMapper
import com.myhealth.data.healthconnect.SyncSummary
import com.myhealth.data.prefs.DataStoreSettingsRepository
import com.myhealth.data.repository.ImportContentSource
import com.myhealth.data.repository.TransactionRunner
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.SystemPlatformClock
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.platform.IosDocuments
import com.myhealth.sync.HealthJobs
import com.myhealth.sync.InProcessSyncScheduler
import com.myhealth.sync.SyncScheduler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.FileSystem
import okio.Buffer
import okio.Timeout
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
 * migrations are exactly Android's), `file://` document access, Apple Health behind the shared
 * Health Connect sync (P22.1) and the in-process scheduler.
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

    // ---- Apple Health (P22.1) ---------------------------------------------------------------

    val healthKit: HealthKitAccess by lazy { HealthKitAccess() }

    override val hcIntegration: HcIntegration by lazy { AppleHealthIntegration(healthKit) }

    /** The shared Health Connect sync over the HealthKit reader; `null` where Health is missing (iPad). */
    val healthSync: HcSyncService? by lazy {
        if (!healthKit.isAvailable) return@lazy null
        HcSyncService(
            reader = HealthKitReader(healthKit.store, clock),
            mapper = HealthConnectMapper(),
            activityRepo = activityRepo,
            healthRepo = healthRepo,
            bodyRepo = bodyRepo,
            syncStateRepo = syncStateRepo,
            clock = clock,
        )
    }

    /** HealthKit has no 30-day history limit, so the backfill needs no extra permission. */
    private val healthBackfill: HcBackfill? by lazy {
        healthSync?.let { HcBackfill(sync = it, syncStateRepo = syncStateRepo, historyGranted = { true }, clock = clock) }
    }

    /**
     * Before the permission sheet was answered nothing is read: an anchor taken without access
     * would later replay the whole history.
     */
    private val healthJobs = HealthJobs(
        sync = { whenConnected { it.syncIncremental() } },
        backfill = { fromDay -> whenConnected { healthBackfill!!.run(fromDay) } },
        reread = { days -> whenConnected { it.rereadExerciseDetail(days) } },
    )

    private suspend fun whenConnected(block: suspend (HcSyncService) -> Outcome<*>): Outcome<*> {
        val sync = healthSync ?: return Outcome.Ok(SyncSummary())
        if (!healthKit.wasAnswered()) return Outcome.Ok(SyncSummary())
        return block(sync)
    }

    override val syncScheduler: SyncScheduler by lazy {
        InProcessSyncScheduler(
            scope = appScope,
            importer = { importService },
            recomputeLoad = { fromDay -> loadRecomputeService.recompute(fromDay) },
            recomputeTargets = { targetRecomputeService.recompute().failure == null },
            today = { clock.todayEpochDay() },
            health = healthJobs,
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

/** The Integrations screen's view of Apple Health (P22.1). */
private class AppleHealthIntegration(private val access: HealthKitAccess) : HcIntegration {
    override val platform: HealthPlatform = HealthPlatform.APPLE_HEALTH
    override fun status(): HcStatus = if (access.isAvailable) HcStatus.AVAILABLE else HcStatus.UNAVAILABLE
    override suspend fun granted(): Set<String> = if (access.wasAnswered()) allPermissions else emptySet()
    override val allPermissions: Set<String> get() = access.readIdentifiers
    override val optionalDetailPermissions: Set<String> = emptySet()
}

/** Documents by `file://` URL or plain path — always inside the sandbox on iOS (`IosDocuments`). */
private class FileContentSource : ImportContentSource, BackupContentSource {

    private fun pathOf(uri: String): Path =
        (if (uri.startsWith("file:")) NSURL.URLWithString(uri)?.path ?: uri.removePrefix("file://") else uri).toPath()

    override suspend fun displayName(uri: String): String = pathOf(uri).name

    override suspend fun openSource(uri: String): Source = FileSystem.SYSTEM.source(pathOf(uri))

    override suspend fun openInput(uri: String): Source = FileSystem.SYSTEM.source(pathOf(uri))

    /** A backup written to the export folder is offered to "save to Files" once it is complete (P22.2). */
    override suspend fun openOutput(uri: String): Sink {
        val path = pathOf(uri)
        val sink = FileSystem.SYSTEM.sink(path)
        if (!IosDocuments.isExport(path.toString())) return sink
        return object : Sink {
            override fun write(source: Buffer, byteCount: Long) = sink.write(source, byteCount)
            override fun flush() = sink.flush()
            override fun timeout(): Timeout = sink.timeout()
            override fun close() {
                sink.close()
                IosDocuments.presentExport(path.toString())
            }
        }
    }
}
