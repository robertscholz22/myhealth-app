package com.myhealth.ui.nav

import com.myhealth.ui.sleep.SleepScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.BackBar
import com.myhealth.ui.common.LocalPlatformUi
import com.myhealth.ui.common.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.myhealth.di.appGraph
import com.myhealth.domain.model.MealSlot
import com.myhealth.ui.activities.ActivitiesScreen
import com.myhealth.ui.activities.ActivityDetailScreen
import com.myhealth.ui.bike.BikeScreen
import com.myhealth.ui.body.BodyScreen
import com.myhealth.ui.calendar.CalendarScreen
import com.myhealth.ui.calendar.DayDetailNavActions
import com.myhealth.ui.calendar.DayDetailScreen
import com.myhealth.ui.calendar.EventEditScreen
import com.myhealth.ui.camera.OcrReviewScreen
import com.myhealth.ui.common.PlaceholderScreen
import com.myhealth.ui.cycle.CycleScreen
import com.myhealth.ui.goals.GoalEditScreen
import com.myhealth.ui.goals.GoalsScreen
import com.myhealth.ui.ingredients.IngredientEditScreen
import com.myhealth.ui.ingredients.IngredientsScreen
import com.myhealth.ui.imports.ImportScreen
import com.myhealth.ui.load.LoadScreen
import com.myhealth.ui.meals.MealTemplateEditScreen
import com.myhealth.ui.meals.MealTemplatesScreen
import com.myhealth.ui.more.MoreScreen
import com.myhealth.ui.nutrition.AddFoodScreen
import com.myhealth.ui.nutrition.NutritionScreen
import com.myhealth.ui.onboarding.OnboardingScreen
import com.myhealth.ui.running.RunningPrsScreen
import com.myhealth.ui.settings.BackupScreen
import com.myhealth.ui.settings.CalendarImportScreen
import com.myhealth.ui.settings.IntegrationsScreen
import com.myhealth.ui.settings.SettingsScreen
import com.myhealth.ui.strength.ExerciseDetailScreen
import com.myhealth.ui.strength.ExerciseEditScreen
import com.myhealth.ui.strength.VideoImportScreen
import com.myhealth.ui.strength.ExercisesScreen
import com.myhealth.ui.strength.WorkoutEditScreen
import com.myhealth.ui.strength.WorkoutsScreen
import com.myhealth.ui.today.TodayScreen
import com.myhealth.ui.training.PlannedSessionEditScreen
import com.myhealth.ui.training.SuggestionReviewScreen
import com.myhealth.ui.training.TrainingNavActions
import com.myhealth.ui.training.TrainingScreen
import com.myhealth.ui.zones.ZonesScreen
import kotlinx.coroutines.flow.map

/**
 * The single navigation graph (§1.7: no nested NavHosts). Every route from §4.1 is registered
 * here; destinations that are not implemented yet render a "Coming soon" placeholder that names
 * the screen, so navigation can be exercised end to end from P0.
 *
 * Start destination (§4.1/P1.10): [OnboardingRoute] when the `profile` row is missing, else
 * [TodayRoute]. [com.myhealth.domain.repository.ProfileRepository.observeProfile] is observed
 * once here, gated by a loading state, so the app never flashes Today before Onboarding.
 */
@Composable
fun MyHealthNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val graph = appGraph()
    val platform = LocalPlatformUi.current
    val loadState by remember(graph) {
        graph.profileRepo.observeProfile().map { profile -> ProfileLoadState.Loaded(profile != null) }
    }.collectAsStateWithLifecycle(initialValue = ProfileLoadState.Loading)

    when (val current = loadState) {
        ProfileLoadState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is ProfileLoadState.Loaded -> {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val onOnboarding = backStackEntry?.destination?.hasRoute(OnboardingRoute::class) == true

            // P7.6: a file shared into the app (or opened from a file manager) is parked in the
            // graph by MainActivity; the Import screen picks it up and clears it.
            val sharedImport by graph.pendingImportUri.collectAsStateWithLifecycle()
            val onImport = backStackEntry?.destination?.hasRoute(ImportRoute::class) == true
            LaunchedEffect(sharedImport, onImport) {
                if (sharedImport != null && !onImport) navController.navigate(ImportRoute)
            }

            // P26.7: a video link shared into the app opens "New video workout" with it filled in.
            val sharedVideo by graph.pendingVideoLink.collectAsStateWithLifecycle()
            LaunchedEffect(sharedVideo, onOnboarding, backStackEntry != null) {
                val shared = sharedVideo ?: return@LaunchedEffect
                if (onOnboarding || backStackEntry == null) return@LaunchedEffect
                graph.pendingVideoLink.value = null
                navController.navigate(VideoImportRoute(url = shared.url.orEmpty(), title = shared.title.orEmpty()))
            }

            Scaffold(
                modifier = modifier.fillMaxSize(),
                bottomBar = { if (!onOnboarding) MyHealthBottomBar(navController) },
            ) { innerPadding ->
                // The shell already applies the system-bar insets to the whole NavHost, so they
                // are consumed here: without this every screen with its own `TopAppBar` would add
                // the status-bar inset a second time and leave a gap above its title (POLISH-2).
                val contentModifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                NavHost(
                    navController = navController,
                    startDestination = if (current.hasProfile) TodayRoute else OnboardingRoute,
                    modifier = contentModifier,
                ) {
                    // ---- bottom-bar destinations -----------------------------------------
                    composable<TodayRoute> {
                        TodayScreen(
                            onOpenActivity = { id -> navController.navigate(ActivityDetailRoute(id)) },
                            onOpenDay = { day -> navController.navigate(DayDetailRoute(day)) },
                            onOpenNutrition = { navController.navigate(NutritionRoute) },
                            onOpenLoad = { navController.navigate(LoadRoute) },
                            onOpenTraining = { navController.navigate(TrainingRoute) },
                            onReviewSuggestions = { navController.navigate(SuggestionReviewRoute) },
                            onOpenCycle = { navController.navigate(CycleRoute) },
                            onOpenBody = { navController.navigate(BodyRoute) },
                            onOpenSleep = { navController.navigate(SleepRoute) },
                            onOpenIntegrations = { navController.navigate(IntegrationsRoute) },
                        )
                    }
                    composable<CalendarRoute> {
                        CalendarScreen(
                            onOpenDay = { day -> navController.navigate(DayDetailRoute(day)) },
                            onAddEvent = { day -> navController.navigate(EventEditRoute(epochDay = day)) },
                            onOpenActivity = { id -> navController.navigate(ActivityDetailRoute(id)) },
                        )
                    }
                    composable<NutritionRoute> {
                        NutritionScreen(
                            epochDay = null,
                            onAddFood = { day, slot ->
                                navController.navigate(AddFoodRoute(epochDay = day, slot = slot.name))
                            },
                            onBack = null,
                        )
                    }
                    composable<TrainingRoute> {
                        TrainingScreen(
                            nav = TrainingNavActions(
                                onReviewSuggestions = { navController.navigate(SuggestionReviewRoute) },
                                onEditSession = { id -> navController.navigate(PlannedSessionEditRoute(id = id)) },
                                onAddSession = { day ->
                                    navController.navigate(PlannedSessionEditRoute(epochDay = day))
                                },
                                onOpenActivity = { id -> navController.navigate(ActivityDetailRoute(id)) },
                                onEditEvent = { id, day -> navController.navigate(EventEditRoute(id = id, epochDay = day)) },
                            ),
                        )
                    }
                    composable<MoreRoute> { MoreScreen(onNavigate = { navController.navigate(it) }) }
                    composable<CycleRoute> { CycleScreen(onBack = { navController.popBackStack() }) }

                    // ---- onboarding -------------------------------------------------------
                    composable<OnboardingRoute> {
                        OnboardingScreen(
                            onDone = {
                                navController.navigate(TodayRoute) {
                                    popUpTo(OnboardingRoute) { inclusive = true }
                                }
                            },
                        )
                    }

                    // ---- calendar / plan ---------------------------------------------------
                    composable<DayDetailRoute> { entry ->
                        val route = entry.toRoute<DayDetailRoute>()
                        DayDetailScreen(
                            epochDay = route.epochDay,
                            nav = DayDetailNavActions(
                                onBack = { navController.popBackStack() },
                                onEditEvent = { id, day -> navController.navigate(EventEditRoute(id = id, epochDay = day)) },
                                onEditSession = { id -> navController.navigate(PlannedSessionEditRoute(id = id)) },
                                onOpenActivity = { id -> navController.navigate(ActivityDetailRoute(id)) },
                                onOpenNutrition = { day -> navController.navigate(NutritionDayRoute(day)) },
                            ),
                        )
                    }
                    composable<EventEditRoute> { entry ->
                        val route = entry.toRoute<EventEditRoute>()
                        EventEditScreen(
                            id = route.id,
                            epochDay = route.epochDay,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable<SuggestionReviewRoute> {
                        SuggestionReviewScreen(onBack = { navController.popBackStack() })
                    }
                    composable<PlannedSessionEditRoute> { entry ->
                        val route = entry.toRoute<PlannedSessionEditRoute>()
                        PlannedSessionEditScreen(
                            id = route.id,
                            epochDay = route.epochDay,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    // ---- activities ---------------------------------------------------------
                    composable<ActivitiesRoute> {
                        BackBar(Res.string.more_entry_activities, onBack = { navController.popBackStack() }) {
                            ActivitiesScreen(onOpenDetail = { id -> navController.navigate(ActivityDetailRoute(id)) })
                        }
                    }
                    composable<ActivityDetailRoute> { entry ->
                        val route = entry.toRoute<ActivityDetailRoute>()
                        ActivityDetailScreen(id = route.id, onBack = { navController.popBackStack() })
                    }

                    // ---- nutrition ----------------------------------------------------------
                    composable<NutritionDayRoute> { entry ->
                        val route = entry.toRoute<NutritionDayRoute>()
                        NutritionScreen(
                            epochDay = route.epochDay,
                            onAddFood = { day, slot ->
                                navController.navigate(AddFoodRoute(epochDay = day, slot = slot.name))
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable<AddFoodRoute> { entry ->
                        val route = entry.toRoute<AddFoodRoute>()
                        AddFoodScreen(
                            epochDay = route.epochDay,
                            slot = mealSlotOf(route.slot),
                            onBack = { navController.popBackStack() },
                            onScan = { navController.navigate(ScanRoute) },
                            onNewIngredient = { navController.navigate(IngredientEditRoute()) },
                        )
                    }
                    composable<IngredientsRoute> {
                        BackBar(Res.string.more_entry_ingredients, onBack = { navController.popBackStack() }) {
                            IngredientsScreen(
                                onOpenIngredient = { id -> navController.navigate(IngredientEditRoute(id = id)) },
                                onNewIngredient = { navController.navigate(IngredientEditRoute()) },
                            )
                        }
                    }
                    composable<IngredientEditRoute> { entry ->
                        val route = entry.toRoute<IngredientEditRoute>()
                        IngredientEditScreen(
                            id = route.id,
                            barcode = route.barcode,
                            onBack = { navController.popBackStack() },
                            onScan = { navController.navigate(ScanRoute) },
                        )
                    }
                    composable<ScanRoute> {
                        platform.ScanScreen(
                            onBack = { navController.popBackStack() },
                            onReview = { navController.navigate(OcrReviewRoute) },
                            // A barcode lookup is done with the camera: drop Scan from the stack
                            // so "back" out of the editor returns to whatever opened the scanner.
                            onIngredient = { barcode ->
                                navController.navigate(IngredientEditRoute(barcode = barcode)) {
                                    popUpTo(ScanRoute) { inclusive = true }
                                }
                            },
                        )
                    }
                    composable<OcrReviewRoute> {
                        OcrReviewScreen(
                            onBack = { navController.popBackStack() },
                            onRetake = { navController.popBackStack() },
                            onAccept = {
                                navController.navigate(IngredientEditRoute()) {
                                    popUpTo(ScanRoute) { inclusive = true }
                                }
                            },
                        )
                    }
                    composable<MealTemplatesRoute> {
                        MealTemplatesScreen(
                            onBack = { navController.popBackStack() },
                            onEditTemplate = { id -> navController.navigate(MealTemplateEditRoute(id = id)) },
                            onNewTemplate = { navController.navigate(MealTemplateEditRoute()) },
                        )
                    }
                    composable<MealTemplateEditRoute> { entry ->
                        val route = entry.toRoute<MealTemplateEditRoute>()
                        MealTemplateEditScreen(id = route.id, onBack = { navController.popBackStack() })
                    }

                    // ---- body / load / goals -------------------------------------------------
                    composable<BodyRoute> { BackBar(Res.string.body_title, onBack = { navController.popBackStack() }) { BodyScreen() } }
                    composable<SleepRoute> { BackBar(Res.string.sleep_title, onBack = { navController.popBackStack() }) { SleepScreen() } }
                    composable<LoadRoute> { BackBar(Res.string.more_entry_load_recovery, onBack = { navController.popBackStack() }) { LoadScreen() } }
                    composable<RunningPrsRoute> {
                        BackBar(Res.string.more_entry_running_prs, onBack = { navController.popBackStack() }) {
                            RunningPrsScreen(onOpenActivity = { id -> navController.navigate(ActivityDetailRoute(id)) })
                        }
                    }
                    composable<BikeRoute> {
                        BikeScreen(
                            onBack = { navController.popBackStack() },
                            onOpenActivity = { id -> navController.navigate(ActivityDetailRoute(id)) },
                        )
                    }
                    composable<ZonesRoute> { ZonesScreen(onBack = { navController.popBackStack() }) }
                    composable<WorkoutsRoute> {
                        WorkoutsScreen(
                            onBack = { navController.popBackStack() },
                            onNewWorkout = { navController.navigate(WorkoutEditRoute()) },
                            onEditWorkout = { id -> navController.navigate(WorkoutEditRoute(id = id)) },
                            onNewVideoWorkout = { navController.navigate(VideoImportRoute()) },
                        )
                    }
                    composable<WorkoutEditRoute> { entry ->
                        val route = entry.toRoute<WorkoutEditRoute>()
                        val created by entry.savedStateHandle.getStateFlow<String?>(CREATED_EXERCISE_KEY, null)
                            .collectAsStateWithLifecycle()
                        WorkoutEditScreen(
                            id = route.id,
                            onBack = { navController.popBackStack() },
                            onCreateExercise = { name ->
                                navController.navigate(ExerciseEditRoute(prefillName = name, returnResult = true))
                            },
                            createdExerciseId = created,
                            onCreatedConsumed = { entry.savedStateHandle[CREATED_EXERCISE_KEY] = null },
                            onRecheckVideo = { id ->
                                navController.navigate(VideoImportRoute(workoutId = id)) {
                                    popUpTo<WorkoutEditRoute> { inclusive = true }
                                }
                            },
                        )
                    }
                    composable<VideoImportRoute> { entry ->
                        val route = entry.toRoute<VideoImportRoute>()
                        val created by entry.savedStateHandle.getStateFlow<String?>(CREATED_EXERCISE_KEY, null)
                            .collectAsStateWithLifecycle()
                        VideoImportScreen(
                            workoutId = route.workoutId.takeIf { it > 0 },
                            sharedUrl = route.url.ifEmpty { null },
                            sharedTitle = route.title.ifEmpty { null },
                            onBack = { navController.popBackStack() },
                            onSaved = { navController.popBackStack() },
                            onCreateExercise = { name ->
                                navController.navigate(ExerciseEditRoute(prefillName = name, returnResult = true))
                            },
                            createdExerciseId = created,
                            onCreatedConsumed = { entry.savedStateHandle[CREATED_EXERCISE_KEY] = null },
                        )
                    }
                    composable<ExercisesRoute> {
                        ExercisesScreen(
                            onBack = { navController.popBackStack() },
                            onOpenExercise = { id -> navController.navigate(ExerciseDetailRoute(exerciseId = id)) },
                            onNewExercise = { name -> navController.navigate(ExerciseEditRoute(prefillName = name)) },
                        )
                    }
                    composable<ExerciseDetailRoute> { entry ->
                        val route = entry.toRoute<ExerciseDetailRoute>()
                        ExerciseDetailScreen(
                            exerciseId = route.exerciseId,
                            onBack = { navController.popBackStack() },
                            onEdit = { id -> navController.navigate(ExerciseEditRoute(id = id)) },
                        )
                    }
                    composable<ExerciseEditRoute> { entry ->
                        val route = entry.toRoute<ExerciseEditRoute>()
                        fun finish(id: String) {
                            if (route.returnResult) {
                                navController.previousBackStackEntry?.savedStateHandle?.set(CREATED_EXERCISE_KEY, id)
                                navController.popBackStack()
                            } else if (route.id.isEmpty()) {
                                navController.popBackStack()
                                navController.navigate(ExerciseDetailRoute(exerciseId = id))
                            } else {
                                navController.popBackStack()
                            }
                        }
                        ExerciseEditScreen(
                            exerciseId = route.id.ifEmpty { null },
                            prefillName = route.prefillName.ifEmpty { null },
                            onBack = { navController.popBackStack() },
                            onSaved = ::finish,
                            onDeleted = { navController.popBackStack(ExercisesRoute, inclusive = false) },
                            onUseExisting = { existing -> finish(existing.id) },
                        )
                    }
                    composable<GoalsRoute> {
                        GoalsScreen(
                            onBack = { navController.popBackStack() },
                            onEditGoal = { id -> navController.navigate(GoalEditRoute(id = id)) },
                            onNewGoal = { navController.navigate(GoalEditRoute()) },
                        )
                    }
                    composable<GoalEditRoute> { entry ->
                        val route = entry.toRoute<GoalEditRoute>()
                        GoalEditScreen(id = route.id, onBack = { navController.popBackStack() })
                    }

                    // ---- system ---------------------------------------------------------------
                    composable<SettingsRoute> { BackBar(Res.string.settings_title, onBack = { navController.popBackStack() }) { SettingsScreen() } }
                    composable<IntegrationsRoute> { BackBar(Res.string.more_entry_integrations, onBack = { navController.popBackStack() }) { IntegrationsScreen() } }
                    composable<CalendarImportRoute> { BackBar(Res.string.calendar_import_title, onBack = { navController.popBackStack() }) { CalendarImportScreen() } }
                    composable<ImportRoute> { BackBar(Res.string.more_entry_import, onBack = { navController.popBackStack() }) { ImportScreen() } }
                    composable<BackupRoute> { BackBar(Res.string.more_entry_backup, onBack = { navController.popBackStack() }) { BackupScreen() } }
                    composable<GarminDirectRoute> {
                        BackBar(Res.string.nav_garmin_direct_title, onBack = { navController.popBackStack() }) {
                            PlaceholderScreen(title = stringResource(Res.string.nav_garmin_direct_title))
                        }
                    }
                }
            }
        }
    }
}

/** [AddFoodRoute] carries the slot as a `String` (§4.1) — unknown values fall back to lunch,
 * matching the converter rule of §2.1 (never crash on unexpected stored/forwarded data). */
private fun mealSlotOf(name: String): MealSlot =
    MealSlot.entries.firstOrNull { it.name == name } ?: MealSlot.LUNCH

/** Distinguishes "no emission yet" from a confirmed "no profile" `null`, so Today never flashes. */
private sealed interface ProfileLoadState {
    data object Loading : ProfileLoadState
    data class Loaded(val hasProfile: Boolean) : ProfileLoadState
}
