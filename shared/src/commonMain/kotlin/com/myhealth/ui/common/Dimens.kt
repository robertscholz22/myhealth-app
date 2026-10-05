package com.myhealth.ui.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The two spacing constants P8.6 makes consistent across every screen, on top of the 4/8/12/16/24
 * spacing scale of PLAN §1.7:
 *
 * - [SCREEN_PADDING] — the gutter a screen's root list/column puts around its content.
 * - [CARD_CORNER_RADIUS] — the radius of every card-shaped surface (`SectionCard`, `ErrorBanner`,
 *   the stale-suggestions hint, the chart cards).
 *
 * Using the constants rather than a literal `16.dp`/`12.dp` is what keeps them consistent: there
 * is one place to change, and a grep for the name shows every screen that opted in.
 */
val SCREEN_PADDING: Dp = 16.dp

/** §4.3: 12 dp, the M3 `medium` shape this app uses for every card. */
val CARD_CORNER_RADIUS: Dp = 12.dp
