package com.myhealth.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.myhealth.domain.util.firstOfMonth
import com.myhealth.domain.util.plusMonths
import com.myhealth.domain.util.plusWeeks
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVmWithSavedState
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.monthsUntil

/** Enough pages for ±50 years of months / ±11 years of weeks around the page the screen opens on. */
private const val PAGE_COUNT = 1201
private const val PAGE_CENTER = PAGE_COUNT / 2

@Composable
fun CalendarScreen(
    onOpenDay: (Long) -> Unit,
    onAddEvent: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVmWithSavedState { graph, handle ->
        CalendarViewModel(graph.calendarRepo, graph.cycleRepo, handle, graph.clock)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    CalendarContent(
        state = state,
        onModeChange = vm::setMode,
        onAnchorChange = vm::setAnchor,
        onSelectDay = vm::selectDay,
        onToday = vm::goToToday,
        onOpenDay = onOpenDay,
        onAddEvent = onAddEvent,
        onOpenActivity = onOpenActivity,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarContent(
    state: CalendarUiState,
    onModeChange: (CalendarMode) -> Unit,
    onAnchorChange: (Long) -> Unit,
    onSelectDay: (Long) -> Unit,
    onToday: () -> Unit,
    onOpenDay: (Long) -> Unit,
    onAddEvent: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.title) },
                actions = {
                    IconButton(onClick = onToday) {
                        Icon(Icons.Filled.Today, contentDescription = stringResource(Res.string.calendar_action_today_desc))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddEvent(state.selectedDay) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.calendar_action_new_event_desc))
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ModeChips(mode = state.mode, onModeChange = onModeChange)
            HorizontalDivider()
            when (state.mode) {
                CalendarMode.MONTH -> MonthPager(
                    state = state,
                    onAnchorChange = onAnchorChange,
                    onDayClick = { day ->
                        onSelectDay(day)
                        onOpenDay(day)
                    },
                )
                CalendarMode.WEEK -> WeekPager(
                    state = state,
                    onAnchorChange = onAnchorChange,
                    onSelectDay = onSelectDay,
                    onOpenDay = onOpenDay,
                    onOpenActivity = onOpenActivity,
                )
            }
        }
    }
}

@Composable
private fun ModeChips(mode: CalendarMode, onModeChange: (CalendarMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CalendarMode.entries.forEach { entry ->
            FilterChip(
                selected = mode == entry,
                onClick = { onModeChange(entry) },
                label = {
                    Text(
                        if (entry == CalendarMode.MONTH) {
                            stringResource(Res.string.calendar_mode_month)
                        } else {
                            stringResource(Res.string.calendar_mode_week)
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun MonthPager(
    state: CalendarUiState,
    onAnchorChange: (Long) -> Unit,
    onDayClick: (Long) -> Unit,
) {
    val base = remember { state.anchorDate.firstOfMonth() }
    val pagerState = rememberPagerState(initialPage = PAGE_CENTER) { PAGE_COUNT }
    val anchorPage = remember(state.anchorDay) {
        PAGE_CENTER + base.monthsUntil(state.anchorDate.firstOfMonth())
    }

    LaunchedEffect(anchorPage) {
        if (anchorPage in 0 until PAGE_COUNT && anchorPage != pagerState.currentPage) {
            pagerState.scrollToPage(anchorPage)
        }
    }
    LaunchedEffect(pagerState, base) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            onAnchorChange(base.plusMonths((page - PAGE_CENTER).toLong()).toEpochDays())
        }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
        MonthGrid(
            anchor = base.plusMonths((page - PAGE_CENTER).toLong()),
            days = state.days,
            cycleMarkers = state.cycleMarkers,
            today = state.today,
            selectedDay = state.selectedDay,
            onDayClick = onDayClick,
        )
    }
}

@Composable
private fun WeekPager(
    state: CalendarUiState,
    onAnchorChange: (Long) -> Unit,
    onSelectDay: (Long) -> Unit,
    onOpenDay: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
) {
    val base = remember { weekDays(state.anchorDate).first() }
    val pagerState = rememberPagerState(initialPage = PAGE_CENTER) { PAGE_COUNT }
    val anchorPage = remember(state.anchorDay) {
        PAGE_CENTER + base.daysUntil(weekDays(state.anchorDate).first()) / 7
    }

    LaunchedEffect(anchorPage) {
        if (anchorPage in 0 until PAGE_COUNT && anchorPage != pagerState.currentPage) {
            pagerState.scrollToPage(anchorPage)
        }
    }
    LaunchedEffect(pagerState, base) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            onAnchorChange(base.plusWeeks((page - PAGE_CENTER).toLong()).toEpochDays())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            WeekStrip(
                weekAnchor = base.plusWeeks((page - PAGE_CENTER).toLong()),
                days = state.days,
                cycleMarkers = state.cycleMarkers,
                today = state.today,
                selectedDay = state.selectedDay,
                onSelect = onSelectDay,
            )
        }
        HorizontalDivider()
        AgendaList(
            date = state.selectedDate,
            day = state.selected,
            onOpenDay = onOpenDay,
            onOpenActivity = onOpenActivity,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun previewState(mode: CalendarMode): CalendarUiState {
    val today = LocalDate(2026, 9, 14).toEpochDays()
    return CalendarUiState(
        mode = mode,
        anchorDay = today,
        selectedDay = today,
        today = today,
        days = mapOf(
            today to previewCalendarDay(today),
            today + 2 to previewCalendarDay(today + 2),
        ),
        isLoading = false,
    )
}

@Preview(showBackground = true, widthDp = 380, heightDp = 720, name = "Month")
@Composable
private fun CalendarContentMonthPreview() {
    MyHealthTheme(dynamicColor = false) {
        CalendarContent(
            state = previewState(CalendarMode.MONTH),
            onModeChange = {},
            onAnchorChange = {},
            onSelectDay = {},
            onToday = {},
            onOpenDay = {},
            onAddEvent = {},
            onOpenActivity = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 720, name = "Week")
@Composable
private fun CalendarContentWeekPreview() {
    MyHealthTheme(dynamicColor = false) {
        CalendarContent(
            state = previewState(CalendarMode.WEEK),
            onModeChange = {},
            onAnchorChange = {},
            onSelectDay = {},
            onToday = {},
            onOpenDay = {},
            onAddEvent = {},
            onOpenActivity = {},
        )
    }
}
