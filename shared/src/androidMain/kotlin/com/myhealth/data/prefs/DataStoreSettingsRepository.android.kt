package com.myhealth.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** The single DataStore Preferences file backing `AppSettings` (PLAN P1.8): `files/datastore/settings.preferences_pb`. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** The Android settings repository over the app's `settings` DataStore file. */
fun DataStoreSettingsRepository(context: Context): DataStoreSettingsRepository =
    DataStoreSettingsRepository(context.applicationContext.settingsDataStore)
