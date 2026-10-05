package com.myhealth.ui.strength

import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.ui.common.UiMessage

/** ViewModel state for [WorkoutsScreen] (PLAN §4.2 "Strength workouts", P14.7, More entry). */
data class WorkoutsUiState(
    val isLoading: Boolean = true,
    val workouts: List<StrengthWorkout> = emptyList(),
    val pendingDeleteId: Long? = null,
    val planForDayId: Long? = null,
    val message: UiMessage? = null,
)
