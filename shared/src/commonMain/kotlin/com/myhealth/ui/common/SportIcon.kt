package com.myhealth.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.ui.theme.MyHealthTheme

/** Icon for a [SportGroup] (§4.3 `SportIcon(sportType)` — implemented per-group, sport types share one). */
fun SportGroup.icon(): ImageVector = when (this) {
    SportGroup.SOCCER -> Icons.Filled.SportsSoccer
    SportGroup.RUN -> Icons.AutoMirrored.Filled.DirectionsRun
    SportGroup.STRENGTH -> Icons.Filled.FitnessCenter
    SportGroup.CYCLE -> Icons.AutoMirrored.Filled.DirectionsBike
    SportGroup.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    SportGroup.SWIM -> Icons.Filled.Pool
    SportGroup.OTHER -> Icons.Filled.Terrain
}

/** Display name for a sport-group filter chip / grouping. */
@Composable
fun SportGroup.displayName(): String = when (this) {
    SportGroup.SOCCER -> stringResource(Res.string.sport_group_soccer)
    SportGroup.RUN -> stringResource(Res.string.sport_group_run)
    SportGroup.STRENGTH -> stringResource(Res.string.sport_group_strength)
    SportGroup.CYCLE -> stringResource(Res.string.sport_group_cycle)
    SportGroup.WALK -> stringResource(Res.string.sport_group_walk)
    SportGroup.SWIM -> stringResource(Res.string.sport_group_swim)
    SportGroup.OTHER -> stringResource(Res.string.sport_group_other)
}

/** Title-cased fallback used when an activity has no user title, e.g. `RUN_OUTDOOR` -> "Run outdoor". */
fun SportType.displayName(): String = name.split("_")
    .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

@Composable
fun SportIcon(sportGroup: SportGroup, modifier: Modifier = Modifier) {
    Icon(imageVector = sportGroup.icon(), contentDescription = sportGroup.displayName(), modifier = modifier)
}

@Preview(showBackground = true)
@Composable
private fun SportIconPreview() {
    MyHealthTheme(dynamicColor = false) { SportIcon(SportGroup.RUN) }
}
