package com.myhealth.domain.repository

import com.myhealth.domain.model.AppSettings
import com.myhealth.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * DataStore-backed settings (PLAN P1.8). This is the only repository interface P1.6/P1.8 define;
 * P1.7 adds the remaining ones (profile, body, activity, …) in a later task — kept minimal here on
 * purpose so this task does not anticipate that contract.
 *
 * [settings] never fails: it reflects DataStore's own last-known values. Every setter is a small,
 * named convenience over [update] so call sites don't need to know the whole [AppSettings] shape.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)

    suspend fun setSleepTargetHours(hours: Double)
    suspend fun setIncludeTreadmillInPrs(value: Boolean)
    suspend fun setMobilityOnRestDays(value: Boolean)
    suspend fun setSyncIntervalHours(hours: Int)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setUseDynamicColor(value: Boolean)
    suspend fun setAllowDestructiveMigration(value: Boolean)
    suspend fun setSuggestionHorizonDays(days: Int)
    suspend fun setOffUserAgentContact(contact: String)
    suspend fun setGarminDirectEnabled(value: Boolean)
    suspend fun setHasCompletedOnboarding(value: Boolean)
    suspend fun setCycleTrackingEnabled(value: Boolean)
}
