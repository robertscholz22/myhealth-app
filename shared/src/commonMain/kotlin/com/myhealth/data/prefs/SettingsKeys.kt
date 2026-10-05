package com.myhealth.data.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.myhealth.domain.model.AppSettings
import com.myhealth.domain.model.ThemeMode

/**
 * DataStore Preferences keys for [AppSettings] (PLAN P1.8). One key per field; defaults mirror
 * [AppSettings]'s own default constructor so there is a single source of truth for them.
 */
object SettingsKeys {
    val SLEEP_TARGET_HOURS = doublePreferencesKey("sleep_target_hours")
    val INCLUDE_TREADMILL_IN_PRS = booleanPreferencesKey("include_treadmill_in_prs")
    val MOBILITY_ON_REST_DAYS = booleanPreferencesKey("mobility_on_rest_days")
    val SYNC_INTERVAL_HOURS = intPreferencesKey("sync_interval_hours")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
    val ALLOW_DESTRUCTIVE_MIGRATION = booleanPreferencesKey("allow_destructive_migration")
    val SUGGESTION_HORIZON_DAYS = intPreferencesKey("suggestion_horizon_days")
    val OFF_USER_AGENT_CONTACT = stringPreferencesKey("off_user_agent_contact")
    val GARMIN_DIRECT_ENABLED = booleanPreferencesKey("garmin_direct_enabled")
    val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
    val SUGGESTIONS_STALE = booleanPreferencesKey("suggestions_stale")
    val CYCLE_TRACKING_ENABLED = booleanPreferencesKey("cycle_tracking_enabled")

    /** Single source of truth for every key's default — reuses [AppSettings]'s own defaults. */
    val Defaults = AppSettings()

    /** [ThemeMode] as read from [THEME_MODE]; unknown/missing values fall back to [Defaults]. */
    fun themeModeOf(raw: String?): ThemeMode =
        raw?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: Defaults.themeMode
}
