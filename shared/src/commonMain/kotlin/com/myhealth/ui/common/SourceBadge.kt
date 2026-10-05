package com.myhealth.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.ActivitySource
import com.myhealth.ui.theme.MyHealthTheme

/** Short label for one [ActivitySource], shown on [SourceBadge]. */
@Composable
fun ActivitySource.label(): String = when (this) {
    ActivitySource.HEALTH_CONNECT -> stringResource(Res.string.source_health_connect)
    ActivitySource.FIT_IMPORT -> stringResource(Res.string.source_fit_import)
    ActivitySource.CSV_IMPORT -> stringResource(Res.string.source_csv_import)
    ActivitySource.GARMIN_API -> stringResource(Res.string.source_garmin_api)
    ActivitySource.MANUAL -> stringResource(Res.string.source_manual)
}

/** Small pill showing where a piece of data came from (§4.3). */
@Composable
fun SourceBadge(source: ActivitySource, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = source.label(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** One badge per merged source (an activity merged from several sources shows all of them). */
@Composable
fun SourceBadgeRow(sources: List<ActivitySource>, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        sources.forEach { source -> SourceBadge(source) }
    }
}

@Preview(showBackground = true)
@Composable
private fun SourceBadgeRowPreview() {
    MyHealthTheme(dynamicColor = false) {
        SourceBadgeRow(listOf(ActivitySource.HEALTH_CONNECT, ActivitySource.FIT_IMPORT))
    }
}
