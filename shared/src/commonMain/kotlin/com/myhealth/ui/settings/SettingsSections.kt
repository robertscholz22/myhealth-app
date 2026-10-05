package com.myhealth.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.myhealth.data.time.systemClock
import com.myhealth.data.time.today
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.unit.dp
import com.myhealth.domain.engine.strength.EquipmentSetCodec
import com.myhealth.domain.model.AppSettings
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.ThemeMode
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.strength.label
import com.myhealth.ui.zones.ZoneTable
import com.myhealth.ui.zones.lightweightHrZoneModel
import com.myhealth.ui.zones.schemeLabel
import kotlinx.datetime.LocalDate

/** The non-profile [AppSettings] keys (§4.2 Settings / P1.8). */
@Composable
internal fun AppPreferencesSection(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit) {
    SectionCard(title = stringResource(Res.string.settings_section_app)) {
        DropdownField(
            label = stringResource(Res.string.settings_theme_label),
            options = ThemeMode.entries,
            selected = settings.themeMode,
            optionLabel = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
            onSelect = { onSettingsChange(settings.copy(themeMode = it)) },
        )
        SwitchRow(
            label = stringResource(Res.string.settings_dynamic_color_label),
            checked = settings.useDynamicColor,
            onCheckedChange = { onSettingsChange(settings.copy(useDynamicColor = it)) },
            // Testing hook (PLAN P10.2): every SwitchRow's Switch is otherwise indistinguishable
            // from the others by text, since the label sits next to it rather than on it.
            switchTestTag = "settings_dynamic_color_switch",
        )
        NumberField(
            label = stringResource(Res.string.settings_sync_interval_label),
            value = settings.syncIntervalHours.toDouble(),
            onValueChange = { it?.let { v -> onSettingsChange(settings.copy(syncIntervalHours = v.toInt().coerceAtLeast(1))) } },
            suffix = stringResource(Res.string.settings_unit_hours),
            decimals = 0,
        )
        NumberField(
            label = stringResource(Res.string.settings_suggestion_horizon_label),
            value = settings.suggestionHorizonDays.toDouble(),
            onValueChange = { it?.let { v -> onSettingsChange(settings.copy(suggestionHorizonDays = v.toInt().coerceAtLeast(1))) } },
            suffix = stringResource(Res.string.settings_unit_days),
            decimals = 0,
        )
        SwitchRow(
            label = stringResource(Res.string.settings_include_treadmill_prs_label),
            checked = settings.includeTreadmillInPrs,
            onCheckedChange = { onSettingsChange(settings.copy(includeTreadmillInPrs = it)) },
        )
        // P11.3: on by default for FEMALE profiles (P11.1's onboarding/settings hooks set this),
        // but anyone can opt in or out here regardless of `sex`.
        SwitchRow(
            label = stringResource(Res.string.cycle_track_switch_label),
            checked = settings.cycleTrackingEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(cycleTrackingEnabled = it)) },
            switchTestTag = "settings_cycle_tracking_switch",
        )
        OutlinedTextField(
            value = settings.offUserAgentContact,
            onValueChange = { onSettingsChange(settings.copy(offUserAgentContact = it)) },
            label = { Text(stringResource(Res.string.settings_off_contact_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Debug / experimental toggles (§4.2 Settings). */
@Composable
internal fun AdvancedSection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onRemoveOrphanedImportData: () -> Unit,
    onRecomputeTrainingLoad: () -> Unit,
) {
    var confirmingCleanup by remember { mutableStateOf(false) }

    SectionCard(title = stringResource(Res.string.settings_section_advanced)) {
        SwitchRow(
            label = stringResource(Res.string.settings_garmin_direct_label),
            checked = settings.garminDirectEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(garminDirectEnabled = it)) },
        )
        SwitchRow(
            label = stringResource(Res.string.settings_allow_destructive_migration_label),
            checked = settings.allowDestructiveMigration,
            onCheckedChange = { onSettingsChange(settings.copy(allowDestructiveMigration = it)) },
        )
        OutlinedButton(
            onClick = { confirmingCleanup = true },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(Res.string.settings_orphan_cleanup_button))
        }
        OutlinedButton(
            onClick = onRecomputeTrainingLoad,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(Res.string.settings_recompute_load_button))
        }
        Text(
            text = stringResource(Res.string.settings_recompute_load_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmingCleanup) {
        OrphanCleanupDialog(
            onConfirm = {
                confirmingCleanup = false
                onRemoveOrphanedImportData()
            },
            onDismiss = { confirmingCleanup = false },
        )
    }
}

/**
 * Confirms the BUG-12b orphan cleanup (§4.2 Settings / Advanced): activities imported from files
 * before version 1.0.2 can no longer be undone individually, so this is a one-way removal.
 */
@Composable
private fun OrphanCleanupDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_orphan_cleanup_dialog_title)) },
        text = { Text(stringResource(Res.string.settings_orphan_cleanup_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.settings_orphan_cleanup_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/**
 * The four zone-boundary bpm fields, tolerating a blank slot (P14.6, §4.2 Settings "Heart-rate
 * zones") — unlike [com.myhealth.domain.engine.load.HrZoneModel]'s own manual-bounds parser, which
 * is intentionally all-or-nothing, this is what the four boxes are *sourced from*, so a partially
 * filled set redisplays correctly instead of collapsing to nothing.
 */
fun parseHrZoneBoundsSlots(json: String?): List<Int?> {
    val raw = json?.trim()?.removeSurrounding("[", "]")
    val parts = raw?.split(',') ?: return List(4) { null }
    if (parts.size != 4) return List(4) { null }
    return parts.map { it.trim().toIntOrNull() }
}

/** `null` when every slot is blank (no override at all); otherwise the `[z2,z3,z4,z5]` blob a
 * blank slot stays empty in — which [HrZoneModel]'s own parser then rejects as "not four ascending
 * bpm" (§3.9's `hz08`), so a partial edit never accidentally becomes a manual scheme. */
fun encodeHrZoneBoundsSlots(slots: List<Int?>): String? =
    if (slots.all { it == null }) null else "[" + slots.joinToString(",") { it?.toString() ?: "" } + "]"

/** Whether the four boxes are a usable manual override (PLAN §4.2's "ascending" validation
 * message, `zui09`/`zui10`): [Empty] (no override, no message), [Valid] (all four, ascending, no
 * message), or [Invalid] (some but not all four, or not ascending — the message shows). */
enum class HrZoneBoundsStatus { EMPTY, VALID, INVALID }

fun hrZoneBoundsStatus(slots: List<Int?>): HrZoneBoundsStatus {
    if (slots.all { it == null }) return HrZoneBoundsStatus.EMPTY
    if (slots.any { it == null }) return HrZoneBoundsStatus.INVALID
    val values = slots.filterNotNull()
    return if (values.zipWithNext().all { (a, b) -> a < b }) HrZoneBoundsStatus.VALID else HrZoneBoundsStatus.INVALID
}

/**
 * Heart-rate zones (PLAN §4.2 Settings, P14.6): the resolved scheme in plain words, the
 * lactate-threshold HR, the four zone-boundary bpm fields and a live preview of the resulting
 * zones — reusing [ZoneTable], the same composable the Zones & paces screen shows.
 */
@Composable
internal fun HeartRateZonesSection(profile: Profile, onProfileChange: (Profile) -> Unit) {
    val slots = parseHrZoneBoundsSlots(profile.hrZoneBoundsJson)
    val status = hrZoneBoundsStatus(slots)
    val previewModel = lightweightHrZoneModel(profile, systemClock().today())

    SectionCard(title = stringResource(Res.string.settings_section_hr_zones)) {
        previewModel?.let {
            Text(
                text = stringResource(Res.string.settings_hr_scheme_format, it.scheme.schemeLabel()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NumberField(
            label = stringResource(Res.string.settings_hr_lthr_label),
            value = profile.lactateThresholdHrManual?.toDouble(),
            onValueChange = { onProfileChange(profile.copy(lactateThresholdHrManual = it?.toInt())) },
            suffix = stringResource(Res.string.settings_unit_bpm),
            decimals = 0,
        )
        HR_ZONE_BOUND_LABELS.forEachIndexed { index, labelRes ->
            NumberField(
                label = stringResource(labelRes),
                value = slots[index]?.toDouble(),
                onValueChange = { v ->
                    val updated = slots.toMutableList().also { it[index] = v?.toInt() }
                    onProfileChange(profile.copy(hrZoneBoundsJson = encodeHrZoneBoundsSlots(updated)))
                },
                suffix = stringResource(Res.string.settings_unit_bpm),
                decimals = 0,
            )
        }
        if (status == HrZoneBoundsStatus.INVALID) {
            Text(
                text = stringResource(Res.string.settings_hr_bounds_ascending_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        previewModel?.let {
            Text(
                text = stringResource(Res.string.settings_hr_bounds_preview_title),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            ZoneTable(it)
        }
    }
}

/**
 * "My equipment" (PLAN §P16 "My equipment", P16.2): a multi-select chip row over [Equipment] —
 * every chip selected means `availableEquipmentJson = null` (everything), which is what
 * [EquipmentSetCodec.encode] already does when handed the full set. Deselecting the last chip is a
 * no-op rather than a reset to "everything": an owner clearing every chip almost certainly meant
 * "I have nothing left to pick", not "show me everything again".
 */
@Composable
internal fun StrengthEquipmentSection(profile: Profile, onProfileChange: (Profile) -> Unit) {
    val selected = EquipmentSetCodec.decode(profile.availableEquipmentJson) ?: Equipment.entries.toSet()
    SectionCard(title = stringResource(Res.string.settings_section_strength)) {
        Text(
            text = stringResource(Res.string.settings_my_equipment_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Equipment.entries) { equipment ->
                FilterChip(
                    selected = equipment in selected,
                    onClick = {
                        val updated = if (equipment in selected) selected - equipment else selected + equipment
                        if (updated.isNotEmpty()) {
                            onProfileChange(profile.copy(availableEquipmentJson = EquipmentSetCodec.encode(updated)))
                        }
                    },
                    label = { Text(equipment.label()) },
                )
            }
        }
    }
}

private val HR_ZONE_BOUND_LABELS = listOf(
    Res.string.settings_hr_bound_z2_label,
    Res.string.settings_hr_bound_z3_label,
    Res.string.settings_hr_bound_z4_label,
    Res.string.settings_hr_bound_z5_label,
)

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTestTag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = switchTestTag?.let { Modifier.testTag(it) } ?: Modifier,
        )
    }
}
