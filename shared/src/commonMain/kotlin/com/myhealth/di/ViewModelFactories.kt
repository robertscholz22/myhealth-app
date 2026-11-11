package com.myhealth.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.myhealth.data.time.todayEpochDay
import kotlinx.coroutines.delay
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

/** How often a visible screen re-reads the date; a resume re-reads it at once. */
const val DAY_POLL_MILLIS: Long = 60_000L

/**
 * The device's current epoch day as Compose state (BUG-30): re-read on every resume and once a
 * minute while the screen is shown, so a process that lives through midnight notices the new day.
 */
@Composable
fun rememberCurrentEpochDay(): Long {
    val clock = appGraph().clock
    var day by remember { mutableLongStateOf(clock.todayEpochDay()) }
    LifecycleResumeEffect(Unit) {
        day = clock.todayEpochDay()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DAY_POLL_MILLIS)
            day = clock.todayEpochDay()
        }
    }
    return day
}

/**
 * [rememberVm] for a screen whose [ViewModel] captures "today" when it is built (BUG-30): the
 * ViewModel is keyed by the current day, so the first composition on a new day gets a fresh one
 * instead of yesterday's flows.
 */
@Composable
inline fun <reified VM : ViewModel> rememberDayVm(noinline create: (UiGraph) -> VM): VM {
    val graph = appGraph()
    val day = rememberCurrentEpochDay()
    return viewModel(
        key = "${VM::class.simpleName}@$day",
        factory = viewModelFactory { initializer { create(graph) } },
    )
}

/** [rememberVmWithSavedState] keyed by the current day, see [rememberDayVm]. */
@Composable
inline fun <reified VM : ViewModel> rememberDayVmWithSavedState(
    noinline create: (UiGraph, SavedStateHandle) -> VM,
): VM {
    val graph = appGraph()
    val day = rememberCurrentEpochDay()
    return viewModel(
        key = "${VM::class.simpleName}@$day",
        factory = viewModelFactory { initializer { create(graph, createSavedStateHandle()) } },
    )
}
