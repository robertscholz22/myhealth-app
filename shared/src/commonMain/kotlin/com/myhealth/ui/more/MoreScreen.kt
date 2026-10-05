package com.myhealth.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.ui.nav.ActivitiesRoute
import com.myhealth.ui.nav.BackupRoute
import com.myhealth.ui.nav.BikeRoute
import com.myhealth.ui.nav.BodyRoute
import com.myhealth.ui.nav.CycleRoute
import com.myhealth.ui.nav.GoalsRoute
import com.myhealth.ui.nav.ImportRoute
import com.myhealth.ui.nav.IngredientsRoute
import com.myhealth.ui.nav.IntegrationsRoute
import com.myhealth.ui.nav.LoadRoute
import com.myhealth.ui.nav.MealTemplatesRoute
import com.myhealth.ui.nav.RunningPrsRoute
import com.myhealth.ui.nav.SettingsRoute
import com.myhealth.ui.nav.WorkoutsRoute
import com.myhealth.ui.nav.ExercisesRoute
import com.myhealth.ui.nav.ZonesRoute
import com.myhealth.ui.theme.MyHealthTheme
import org.jetbrains.compose.resources.StringResource

/** Navigation hub (§4.1): links to every destination that is not on the bottom bar. */
data class MoreEntry(val labelRes: StringResource, val route: Any)

/** The static entries, plus "Cycle" (PLAN §5 P11.3) when [cycleTrackingEnabled] — the one entry
 * whose visibility depends on anything other than "this feature exists". */
fun moreEntries(cycleTrackingEnabled: Boolean): List<MoreEntry> = buildList {
    add(MoreEntry(Res.string.more_entry_activities, ActivitiesRoute))
    add(MoreEntry(Res.string.body_title, BodyRoute))
    if (cycleTrackingEnabled) add(MoreEntry(Res.string.more_entry_cycle, CycleRoute))
    add(MoreEntry(Res.string.more_entry_running_prs, RunningPrsRoute))
    add(MoreEntry(Res.string.more_entry_bike, BikeRoute))
    add(MoreEntry(Res.string.more_entry_zones, ZonesRoute))
    add(MoreEntry(Res.string.more_entry_workouts, WorkoutsRoute))
    add(MoreEntry(Res.string.more_entry_exercises, ExercisesRoute))
    add(MoreEntry(Res.string.more_entry_load_recovery, LoadRoute))
    add(MoreEntry(Res.string.more_entry_ingredients, IngredientsRoute))
    add(MoreEntry(Res.string.more_entry_meal_templates, MealTemplatesRoute))
    add(MoreEntry(Res.string.more_entry_goals, GoalsRoute))
    add(MoreEntry(Res.string.more_entry_import, ImportRoute))
    add(MoreEntry(Res.string.more_entry_backup, BackupRoute))
    add(MoreEntry(Res.string.settings_title, SettingsRoute))
    add(MoreEntry(Res.string.more_entry_integrations, IntegrationsRoute))
}

@Composable
fun MoreScreen(onNavigate: (Any) -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph -> MoreViewModel(graph.cycleRepo) }
    val cycleTrackingEnabled by vm.cycleTrackingEnabled.collectAsStateWithLifecycle()
    MoreContent(entries = moreEntries(cycleTrackingEnabled), onNavigate = onNavigate, modifier = modifier)
}

@Composable
private fun MoreContent(
    entries: List<MoreEntry>,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(entries) { entry ->
            ListItem(
                headlineContent = { Text(stringResource(entry.labelRes)) },
                modifier = Modifier.clickable { onNavigate(entry.route) },
            )
            HorizontalDivider()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MoreContentPreview() {
    MyHealthTheme(dynamicColor = false) { MoreContent(moreEntries(cycleTrackingEnabled = true), onNavigate = {}) }
}
