package com.myhealth.di

import com.myhealth.data.repository.StrengthWorkoutSeeder
import com.myhealth.data.time.PlatformClock
import com.myhealth.domain.repository.ActivityImporter
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.BackupRepository
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.CycleRepository
import com.myhealth.domain.repository.GoalRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.ImportRepository
import com.myhealth.domain.repository.IngredientRepository
import com.myhealth.domain.repository.LoadRepository
import com.myhealth.domain.repository.MealRepository
import com.myhealth.domain.repository.NutritionRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.RideBestRepository
import com.myhealth.domain.repository.RunningBestRepository
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.repository.SuggestionRepository
import com.myhealth.domain.repository.SyncStateRepository
import com.myhealth.sync.SyncScheduler
import com.myhealth.ui.camera.DraftStore
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Everything the screens take from the object graph (P20.3): the common UI only sees this
 * interface; `AppGraph` implements it on Android (and the iOS graph will in P22). Platform-only
 * members — the camera's ML Kit sources, `cacheDir` — stay on the platform graph.
 */
interface UiGraph {
    val clock: PlatformClock
    val settings: SettingsRepository
    val profileRepo: ProfileRepository
    val bodyRepo: BodyRepository
    val syncStateRepo: SyncStateRepository
    val healthRepo: HealthRepository
    val ingredientRepo: IngredientRepository
    val mealRepo: MealRepository
    val cycleRepo: CycleRepository
    val nutritionRepo: NutritionRepository
    val activityRepo: ActivityRepository
    val runningBestRepo: RunningBestRepository
    val rideBestRepo: RideBestRepository
    val currentBodyWeightKg: suspend () -> Double
    val strengthRepo: StrengthRepository
    val strengthWorkoutSeeder: StrengthWorkoutSeeder
    val loadRepo: LoadRepository
    val goalRepo: GoalRepository
    val planRepo: PlanRepository
    val calendarRepo: CalendarRepository
    val suggestionRepo: SuggestionRepository
    val importRepo: ImportRepository
    val importService: ActivityImporter

    /** A file shared or opened into the app, waiting for the Import screen (P7.6). */
    val pendingImportUri: MutableStateFlow<String?>
    val backupRepo: BackupRepository
    val hcIntegration: HcIntegration
    val draftStore: DraftStore
    val offLookup: OffLookup
    val syncScheduler: SyncScheduler
}
