package com.myhealth.ui

import com.myhealth.ui.common.getString
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource

/** The text of a string resource as the app shows it (P20.3: Compose resources instead of `R.string`). */
fun str(resource: StringResource, vararg args: Any): String = runBlocking {
    if (args.isEmpty()) getString(resource) else getString(resource, *args)
}
