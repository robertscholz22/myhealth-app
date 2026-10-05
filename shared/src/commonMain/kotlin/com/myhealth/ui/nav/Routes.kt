package com.myhealth.ui.nav

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes (§4.1). Every destination of the finished app is declared here
 * from P0 on; screens that do not exist yet render a "Coming soon" placeholder.
 */

@Serializable data object TodayRoute
@Serializable data object CalendarRoute
@Serializable data class DayDetailRoute(val epochDay: Long)
@Serializable data object TrainingRoute
@Serializable data object SuggestionReviewRoute
@Serializable data class PlannedSessionEditRoute(val id: Long = -1, val epochDay: Long = -1)
@Serializable data object ActivitiesRoute
@Serializable data class ActivityDetailRoute(val id: Long)
@Serializable data object NutritionRoute
@Serializable data class NutritionDayRoute(val epochDay: Long)
@Serializable data class AddFoodRoute(val epochDay: Long, val slot: String)
@Serializable data object IngredientsRoute
@Serializable data class IngredientEditRoute(val id: Long = -1, val barcode: String? = null)
@Serializable data object ScanRoute
@Serializable data object OcrReviewRoute
@Serializable data object MealTemplatesRoute
@Serializable data class MealTemplateEditRoute(val id: Long = -1)
@Serializable data class EventEditRoute(val id: Long = -1, val epochDay: Long = -1)
@Serializable data object BodyRoute
@Serializable data object LoadRoute
@Serializable data object RunningPrsRoute
@Serializable data object GoalsRoute
@Serializable data class GoalEditRoute(val id: Long = -1)
@Serializable data object SettingsRoute
@Serializable data object IntegrationsRoute
@Serializable data object ImportRoute
@Serializable data object BackupRoute
@Serializable data object OnboardingRoute
@Serializable data object GarminDirectRoute
@Serializable data object MoreRoute
@Serializable data object CycleRoute
@Serializable data object BikeRoute
@Serializable data object ZonesRoute
@Serializable data object WorkoutsRoute
@Serializable data class WorkoutEditRoute(val id: Long = -1)
@Serializable data object ExercisesRoute
@Serializable data class ExerciseDetailRoute(val exerciseId: String)
