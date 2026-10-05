package com.myhealth.ui.common

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource

/**
 * The app's strings (P20.3): Compose Multiplatform resources, formatted like Android's
 * `getString(id, args…)`.
 *
 * Compose resources only substitute `%1$s`/`%1$d` themselves; the app also uses `%1$.1f` and `%%`
 * and relies on the device locale's decimal separator ("0,88" on a German phone). So the raw text
 * comes from the resource and [formatResourceString] formats it — on Android with
 * `String.format(Locale.getDefault(), …)`, exactly what `Resources.getString(id, args)` does.
 * Without arguments the text is returned as is, `%%` included, as on Android.
 */
@Composable
fun stringResource(resource: StringResource): String = org.jetbrains.compose.resources.stringResource(resource)

@Composable
fun stringResource(resource: StringResource, vararg formatArgs: Any): String =
    formatResourceString(org.jetbrains.compose.resources.stringResource(resource), formatArgs)

/** [stringResource] outside composition (view models, snackbars). */
suspend fun getString(resource: StringResource): String = org.jetbrains.compose.resources.getString(resource)

suspend fun getString(resource: StringResource, vararg formatArgs: Any): String =
    formatResourceString(org.jetbrains.compose.resources.getString(resource), formatArgs)

/** `String.format(<device locale>, pattern, *args)`. */
expect fun formatResourceString(pattern: String, args: Array<out Any>): String
