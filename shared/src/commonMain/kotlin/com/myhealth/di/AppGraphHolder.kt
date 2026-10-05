package com.myhealth.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** Provides the process-wide [UiGraph] to composables. Set once by the platform entry point (`MainActivity`). */
val LocalAppGraph = staticCompositionLocalOf<UiGraph> { error("AppGraph missing") }

@Composable
@ReadOnlyComposable
fun appGraph(): UiGraph = LocalAppGraph.current
