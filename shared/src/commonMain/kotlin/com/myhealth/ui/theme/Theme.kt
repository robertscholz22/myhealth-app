package com.myhealth.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.myhealth.domain.model.ThemeMode
import com.myhealth.ui.common.dynamicColorScheme

/**
 * App theme: Material 3 with the green scheme of [LightColors]/[DarkColors] (PLAN P8.6a).
 *
 * Dynamic (wallpaper) colour is opt-in: [dynamicColor] defaults to **false** and is fed from
 * `AppSettings.useDynamicColor` by `MainActivity`, which also resolves `AppSettings.themeMode`
 * into [darkTheme] via [isDarkTheme].
 */
@Composable
fun MyHealthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = (if (dynamicColor) dynamicColorScheme(darkTheme) else null)
        ?: if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MyHealthTypography,
        content = content,
    )
}

/** `AppSettings.themeMode` → the `darkTheme` flag; `SYSTEM` follows the device setting. */
@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
