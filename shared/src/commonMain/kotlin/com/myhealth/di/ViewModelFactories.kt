package com.myhealth.di

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Obtains a [ViewModel] with an explicit factory — no reflection, no DI framework (§1.3).
 *
 * Usage: `val vm = rememberVm { graph -> TodayViewModel(graph.profileRepo, graph.clock) }`
 */
@Composable
inline fun <reified VM : ViewModel> rememberVm(noinline create: (UiGraph) -> VM): VM {
    val graph = appGraph()
    return viewModel(factory = viewModelFactory { initializer { create(graph) } })
}

/**
 * Same as [rememberVm], for a [ViewModel] that keeps UI state across process death: the factory
 * also hands it a [SavedStateHandle] built from the owner's `CreationExtras` (§1.3).
 *
 * Usage: `rememberVmWithSavedState { graph, handle -> CalendarViewModel(graph.calendarRepo, handle, graph.clock) }`
 */
@Composable
inline fun <reified VM : ViewModel> rememberVmWithSavedState(
    noinline create: (UiGraph, SavedStateHandle) -> VM,
): VM {
    val graph = appGraph()
    return viewModel(factory = viewModelFactory { initializer { create(graph, createSavedStateHandle()) } })
}
