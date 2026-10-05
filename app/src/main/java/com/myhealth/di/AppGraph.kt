package com.myhealth.di

import android.app.Application
import androidx.work.WorkManager
import com.myhealth.BuildConfig
import com.myhealth.data.backup.AndroidBackupContentSource
import com.myhealth.data.backup.BackupContentSource
import com.myhealth.data.db.MyHealthDatabase
import com.myhealth.data.db.RoomTransactionRunner
import com.myhealth.data.db.buildMyHealthDatabase
import com.myhealth.data.healthconnect.HcBackfill
import com.myhealth.data.healthconnect.HcMapper
import com.myhealth.data.healthconnect.HcPermissions
import com.myhealth.data.healthconnect.HcReader
import com.myhealth.data.healthconnect.HcSyncService
import com.myhealth.data.healthconnect.HealthConnectMapper
import com.myhealth.data.healthconnect.HealthConnectProvider
import com.myhealth.data.healthconnect.HealthConnectReader
import com.myhealth.data.ocr.MlKitBarcodeSource
import com.myhealth.data.ocr.MlKitTextSource
import com.myhealth.data.prefs.DataStoreSettingsRepository
import com.myhealth.data.repository.AndroidImportContentSource
import com.myhealth.data.repository.ImportContentSource
import com.myhealth.data.repository.TransactionRunner
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.sync.SyncScheduler
import com.myhealth.sync.WorkManagerSyncScheduler
import java.io.File
import java.time.Clock
import java.time.ZoneId

/**
 * The Android dependency container (§1.3): [CoreGraph] holds every shared repository and service
 * (P21); this class adds what only Android has — the Room/SupportSQLite database, the DataStore
 * file, SAF document access, Health Connect, ML Kit and WorkManager. Constructed once in
 * [com.myhealth.MyHealthApp.onCreate].
 *
 * Rules: holds no Activity/Compose references; everything a test needs must be constructible
 * without an `AppGraph`; engines are stateless and take a [Clock] so tests can pin "today".
 */
class AppGraph(private val app: Application) : CoreGraph() {

    override val clock: Clock by lazy { Clock.systemDefaultZone() }

    /** Every "day" boundary in the app is a local day in this zone (§1.6). */
    val zoneId: ZoneId by lazy { clock.zone }

    override val db: MyHealthDatabase by lazy { buildMyHealthDatabase(app, debug = BuildConfig.DEBUG) }

    override val transactionRunner: TransactionRunner by lazy { RoomTransactionRunner(db) }

    override val importContent: ImportContentSource by lazy { AndroidImportContentSource(app) }

    override val backupContent: BackupContentSource by lazy { AndroidBackupContentSource(app) }

    override val appVersion: String = BuildConfig.VERSION_NAME

    override val settings: SettingsRepository by lazy { DataStoreSettingsRepository(app) }

    /** Health Connect availability + permissions (P2.1). */
    val healthConnect: HealthConnectProvider by lazy { HealthConnectProvider(app) }

    /** The Integrations screen's (P2.8) only window onto Health Connect — `ui/` may not import
     * `com.myhealth.data.*` directly, so this `di`-owned seam stands in for [healthConnect]. */
    override val hcIntegration: HealthConnectIntegration by lazy { HealthConnectIntegration(healthConnect) }

    val hcMapper: HcMapper by lazy { HealthConnectMapper() }

    /** Null when the Health Connect SDK is unavailable — sync must then stay switched off. */
    val hcReader: HcReader? by lazy { healthConnect.client()?.let { HealthConnectReader(it) } }

    val hcSync: HcSyncService? by lazy {
        hcReader?.let { reader ->
            HcSyncService(
                reader = reader,
                mapper = hcMapper,
                activityRepo = activityRepo,
                healthRepo = healthRepo,
                bodyRepo = bodyRepo,
                syncStateRepo = syncStateRepo,
                clock = clock,
            )
        }
    }

    val hcBackfill: HcBackfill? by lazy {
        hcSync?.let { sync ->
            HcBackfill(
                sync = sync,
                syncStateRepo = syncStateRepo,
                historyGranted = { HcPermissions.HISTORY in healthConnect.granted() },
                clock = clock,
            )
        }
    }

    // ---- scanning: camera + OCR (P4.8–P4.10) ----------------------------------------------

    /** Where a captured label JPEG is kept until the review screen has shown it (P4.8). */
    val cacheDir: File get() = app.cacheDir

    /** Both ML Kit detectors are expensive to create, so the process keeps one of each (P4.8). */
    private val textSource: MlKitTextSource by lazy { MlKitTextSource() }

    private val barcodeSource: MlKitBarcodeSource by lazy { MlKitBarcodeSource() }

    /** The camera screen's `di`-owned window onto `data/ocr` (P4.8) — see [ScanSources]. */
    val scanSources: ScanSources by lazy { MlKitScanSources(app, textSource, barcodeSource) }

    /** WorkManager entry point (P2.7); on-demand initialized from [com.myhealth.MyHealthApp]'s
     * `Configuration.Provider` since the default `androidx.startup` initializer is disabled. */
    override val syncScheduler: SyncScheduler by lazy { WorkManagerSyncScheduler(WorkManager.getInstance(app), clock) }
}
