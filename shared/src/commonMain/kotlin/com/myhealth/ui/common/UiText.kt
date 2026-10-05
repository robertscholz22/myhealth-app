package com.myhealth.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.StringResource

/**
 * A user-facing message produced outside a composable (PLAN P8.1).
 *
 * ViewModels must not build display strings — `domain/` is platform-free and a `ViewModel` has no
 * resources — so they emit a string resource plus its format arguments and the UI resolves it.
 * [args] are passed straight to `getString(resource, …)`, so they must already be primitives or strings.
 */
@Immutable
data class UiMessage(val resId: StringResource, val args: List<Any> = emptyList()) {

    /** Resolves the message outside composition (snackbars, `LaunchedEffect`, workers). */
    suspend fun resolveText(): String =
        if (args.isEmpty()) getString(resId) else getString(resId, *args.toTypedArray())

    companion object {
        fun of(resId: StringResource, vararg args: Any): UiMessage = UiMessage(resId, args.toList())
    }
}

/** Resolves the message inside composition. */
@Composable
fun UiMessage.resolve(): String =
    if (args.isEmpty()) stringResource(resId) else stringResource(resId, *args.toTypedArray())
