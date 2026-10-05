package com.myhealth.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The app's own Material 3 palette (PLAN P8.6a — owner request, 2026-09-12): a **green** scheme.
 *
 * - `primary` comes from the `#2E7D32` (light) / `#81C784` (dark) family,
 * - `secondary` is a teal-green, `tertiary` a lime/olive,
 * - every surface carries a faint green tint (`surfaceTint` is the primary, the neutral ramp is
 *   pulled a few degrees towards green) so cards read as part of the same family,
 * - `error` stays red, and the semantic amber/red warning colours screens use for ACWR zones and
 *   OCR confidence ([WarningAmber], [PositiveGreen], [CautionRed]) are unchanged.
 *
 * These schemes are used unless `AppSettings.useDynamicColor` is on (default **false**), in which
 * case the wallpaper-derived schemes replace them — see [MyHealthTheme].
 */

// ---- brand seeds ---------------------------------------------------------------------------------

internal val Green40 = Color(0xFF2E7D32)
internal val Green80 = Color(0xFF81C784)
internal val TealGreen40 = Color(0xFF00696B)
internal val TealGreen80 = Color(0xFF80D5D5)
internal val Olive40 = Color(0xFF5C6300)
internal val Olive80 = Color(0xFFC2CD62)
internal val Red40 = Color(0xFFBA1A1A)
internal val Red80 = Color(0xFFFFB4AB)

// ---- semantic (non-M3) colours screens use for warnings / positive states ------------------------

/** "Caution" amber — ACWR caution zone, medium OCR confidence. */
val WarningAmber: Color = Color(0xFFF9A825)

/** "Good" green — optimal ACWR zone, high OCR confidence, on-track goals. */
val PositiveGreen: Color = Color(0xFF2E7D32)

/** "Danger" red — high-risk ACWR zone; distinct from the M3 `error` role on purpose. */
val CautionRed: Color = Color(0xFFBA1A1A)

// ---- schemes -------------------------------------------------------------------------------------

internal val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2F1AF),
    onPrimaryContainer = Color(0xFF00210B),
    inversePrimary = Color(0xFF97D895),
    secondary = TealGreen40,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9CF1F2),
    onSecondaryContainer = Color(0xFF002020),
    tertiary = Olive40,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDEA78),
    onTertiaryContainer = Color(0xFF1A1D00),
    error = Red40,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FBF2),
    onBackground = Color(0xFF181D18),
    surface = Color(0xFFF7FBF2),
    onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDDE5DA),
    onSurfaceVariant = Color(0xFF414942),
    surfaceTint = Green40,
    inverseSurface = Color(0xFF2D322C),
    inverseOnSurface = Color(0xFFEEF2EA),
    outline = Color(0xFF717971),
    outlineVariant = Color(0xFFC1C9BF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7FBF2),
    surfaceDim = Color(0xFFD7DBD3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5ED),
    surfaceContainer = Color(0xFFEBEFE7),
    surfaceContainerHigh = Color(0xFFE6EAE1),
    surfaceContainerHighest = Color(0xFFE0E4DC),
)

internal val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Color(0xFF00390F),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFB2F1AF),
    inversePrimary = Green40,
    secondary = TealGreen80,
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF004F50),
    onSecondaryContainer = Color(0xFF9CF1F2),
    tertiary = Olive80,
    onTertiary = Color(0xFF2E3300),
    tertiaryContainer = Color(0xFF444B00),
    onTertiaryContainer = Color(0xFFDDEA78),
    error = Red80,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101410),
    onBackground = Color(0xFFE0E4DC),
    surface = Color(0xFF101410),
    onSurface = Color(0xFFE0E4DC),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC1C9BF),
    surfaceTint = Green80,
    inverseSurface = Color(0xFFE0E4DC),
    inverseOnSurface = Color(0xFF2D322C),
    outline = Color(0xFF8B938A),
    outlineVariant = Color(0xFF414942),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF363A35),
    surfaceDim = Color(0xFF101410),
    surfaceContainerLowest = Color(0xFF0B0F0B),
    surfaceContainerLow = Color(0xFF181D18),
    surfaceContainer = Color(0xFF1C211C),
    surfaceContainerHigh = Color(0xFF272B26),
    surfaceContainerHighest = Color(0xFF323630),
)
