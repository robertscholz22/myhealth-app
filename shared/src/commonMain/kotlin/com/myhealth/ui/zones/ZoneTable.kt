package com.myhealth.ui.zones

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import org.jetbrains.compose.resources.StringResource
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.unit.dp
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.model.HrZoneScheme

/** `hr_zone_1_name`…`hr_zone_5_name` (PLAN §3.9) for zone index `1..5`. */
internal fun zoneNameRes(index: Int): StringResource = when (index) {
    1 -> Res.string.hr_zone_1_name
    2 -> Res.string.hr_zone_2_name
    3 -> Res.string.hr_zone_3_name
    4 -> Res.string.hr_zone_4_name
    else -> Res.string.hr_zone_5_name
}

/** The zone scheme in plain words (§4.2 "Zones & paces" / Settings' "Heart-rate zones" section). */
@Composable
fun HrZoneScheme.schemeLabel(): String = when (this) {
    HrZoneScheme.HRR_KARVONEN -> stringResource(Res.string.zones_scheme_karvonen)
    HrZoneScheme.LTHR_FRIEL -> stringResource(Res.string.zones_scheme_lthr)
    HrZoneScheme.MANUAL -> stringResource(Res.string.zones_scheme_manual)
}

/**
 * The five named zones with their bpm ranges (PLAN §3.9), Z1 first: "Z4 · Threshold · 162–175
 * bpm", Z5 open-ended ("176+ bpm"). The scheme sits above the table in plain words.
 */
@Composable
fun ZoneTable(model: HrZoneModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = model.scheme.schemeLabel(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        model.zones.forEach { zone ->
            Text(
                text = zoneRowLabel(zone, stringResource(zoneNameRes(zone.index))),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
