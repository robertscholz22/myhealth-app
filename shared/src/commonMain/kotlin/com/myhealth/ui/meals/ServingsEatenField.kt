package com.myhealth.ui.meals

import androidx.compose.runtime.Composable
import com.myhealth.domain.model.MacroTotals
import com.myhealth.resources.*
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.fmtInt
import com.myhealth.ui.common.mathRound
import com.myhealth.ui.common.stringResource

/**
 * P24: "Servings eaten" for logging a template, used by the templates list's "Log now" dialog and
 * Add food's Templates tab. The supporting line is the live kcal of what will be logged ([preview]),
 * plus how many servings the template makes when that is not one.
 */
@Composable
internal fun ServingsEatenField(
    servings: Double?,
    onServingsChange: (Double?) -> Unit,
    preview: MacroTotals?,
    made: Double,
) {
    val valid = (servings ?: 0.0) > 0.0
    val kcal = preview?.let { fmtInt(mathRound(it.kcal)) }
    NumberField(
        label = stringResource(Res.string.mealtpl_log_servings_label),
        value = servings,
        onValueChange = onServingsChange,
        decimals = servingsDecimals(servings),
        isError = !valid,
        supportingText = when {
            !valid -> stringResource(Res.string.mealtpl_log_servings_error)
            kcal == null -> null
            made == 1.0 -> stringResource(Res.string.mealtpl_log_servings_preview, kcal)
            else -> stringResource(Res.string.mealtpl_log_servings_preview_of, kcal, fmtServings(made))
        },
    )
}
