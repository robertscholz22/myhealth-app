package com.myhealth.domain.util

import com.myhealth.domain.model.EngineWarningCode

/**
 * Engines never throw for bad input (PLAN §1.5): they clamp inputs and return these instead.
 */
data class EngineWarning(val code: EngineWarningCode, val message: String)
