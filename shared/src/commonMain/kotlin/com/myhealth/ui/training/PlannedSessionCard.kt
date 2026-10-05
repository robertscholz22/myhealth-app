package com.myhealth.ui.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.mathRound
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.engine.suggest.IntervalStructures
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.WorkoutStep
import com.myhealth.domain.model.WorkoutStepKind
import com.myhealth.domain.model.WorkoutStructure
import com.myhealth.domain.model.WorkoutStructureCodec
import com.myhealth.ui.activities.formatDistanceKm
import com.myhealth.ui.calendar.OverflowMenu
import com.myhealth.ui.calendar.displayName
import com.myhealth.ui.common.SportIcon
import com.myhealth.ui.common.zeroPad
import com.myhealth.ui.theme.MyHealthTheme
import com.myhealth.ui.zones.zoneChipLabel

/** The per-session actions of §4.2 "Training plan", grouped so the row keeps a short signature. */
data class PlannedSessionActions(
    val onToggleLock: (PlannedSession) -> Unit = {},
    val onEdit: (Long) -> Unit = {},
    val onMarkDone: (Long) -> Unit = {},
    val onSkip: (Long) -> Unit = {},
    val onReopen: (Long) -> Unit = {},
    val onDelete: (Long) -> Unit = {},
)

/**
 * One planned session on the week board: sport icon, session type, intensity chip, the duration /
 * distance target, its status, and the lock the suggester must respect (C6, §3.5.3).
 *
 * The lock is its own tap target rather than a menu entry — it is the one action the owner uses
 * while reading the board ("keep this one, regenerate the rest").
 */
@Composable
fun PlannedSessionCard(
    session: PlannedSession,
    actions: PlannedSessionActions,
    modifier: Modifier = Modifier,
    hrZoneModel: HrZoneModel? = null,
    /** The linked `strength_workout`'s name (P14.7, §4.1/§4.2) — `null` until the caller's workout
     * list has loaded, or when [PlannedSession.workoutId] is itself `null`. */
    workoutName: String? = null,
    /** True when that workout's kind is one of the three P17 `MOBILITY_*` kinds — [workoutName]
     * then renders as "Routine: …" instead of "Workout: …" (P17.2). Meaningless when [workoutName]
     * is `null`. */
    isMobilityWorkout: Boolean = false,
    /** The workout's first three exercises with their prescriptions (P16.2, §P16 "Where it shows"),
     * e.g. "Bench press 3 × 8 @ 50 kg, Squat 3 × 5 @ 40 kg…" — `null` alongside [workoutName]. */
    workoutExercisesLine: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SportIcon(session.sportType.group, modifier = Modifier.padding(end = 8.dp).size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = session.sessionType.displayName(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(session.intensity.label()) },
                )
            }
            Text(
                text = plannedSessionSubtitle(session, linkedLabel = stringResource(Res.string.training_session_linked)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TargetZoneChip(session.sessionType, hrZoneModel)
            workoutName?.let {
                val format = if (isMobilityWorkout) Res.string.session_routine_format else Res.string.session_workout_format
                Text(
                    text = stringResource(format, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            workoutExercisesLine?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WorkoutStructureSection(session.structureJson)
        }
        IconButton(onClick = { actions.onToggleLock(session) }) {
            Icon(
                imageVector = if (session.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = if (session.locked) {
                    stringResource(Res.string.session_unlock_desc)
                } else {
                    stringResource(Res.string.session_lock_desc)
                },
                tint = if (session.locked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        OverflowMenu(
            sessionMenu(
                session = session,
                actions = actions,
                markDoneLabel = stringResource(Res.string.training_mark_done),
                skipLabel = stringResource(Res.string.training_skip),
                reopenLabel = stringResource(Res.string.training_reopen),
                editLabel = stringResource(Res.string.training_edit),
                deleteLabel = stringResource(Res.string.action_delete),
            ),
        )
    }
}

private fun sessionMenu(
    session: PlannedSession,
    actions: PlannedSessionActions,
    markDoneLabel: String,
    skipLabel: String,
    reopenLabel: String,
    editLabel: String,
    deleteLabel: String,
): List<Pair<String, () -> Unit>> = buildList {
    if (session.status == PlannedStatus.PLANNED) {
        add(markDoneLabel to { actions.onMarkDone(session.id) })
        add(skipLabel to { actions.onSkip(session.id) })
    } else {
        add(reopenLabel to { actions.onReopen(session.id) })
    }
    add(editLabel to { actions.onEdit(session.id) })
    add(deleteLabel to { actions.onDelete(session.id) })
}

/** `"45 min · 8.00 km · 120 AU · Planned"` — whatever the session actually carries. */
fun plannedSessionSubtitle(session: PlannedSession, linkedLabel: String): String = buildList {
    session.targetDurationMin?.let { add("$it min") }
    formatDistanceKm(session.targetDistanceMeters)?.let { add(it) }
    session.targetPaceSecPerKm?.let { add(formatPaceSecPerKm(it)) }
    session.estimatedTrimp?.let { add("${mathRound(it)} AU") }
    add(session.status.displayName())
    if (session.linkedActivityId != null) add(linkedLabel)
}.joinToString(" · ")

/** `"5:30 /km"` from seconds per kilometre. */
fun formatPaceSecPerKm(secPerKm: Int): String = "${secPerKm / 60}:${(secPerKm % 60).zeroPad(2)} /km"

/** The target-zone chip of §4.1/§4.2's plan-UI rows ("Z2 · 134–147 bpm"); renders nothing when the
 * session type has no zone target or the zone model has not resolved yet. */
@Composable
fun TargetZoneChip(sessionType: SessionType, hrZoneModel: HrZoneModel?, modifier: Modifier = Modifier) {
    val label = zoneChipLabel(sessionType, hrZoneModel) ?: return
    AssistChip(onClick = {}, enabled = false, label = { Text(label) }, modifier = modifier)
}

/**
 * The structure summary line ("5 × 1000 m @ 3:54") with an expandable step list (§4.1/§4.2): one
 * line per warm-up / `N ×` work / recovery / cool-down step, each with its own target. Renders
 * nothing when [structureJson] does not decode to a structure with a work step.
 */
@Composable
fun WorkoutStructureSection(structureJson: String?, modifier: Modifier = Modifier) {
    val structure = remember(structureJson) { WorkoutStructureCodec.decode(structureJson) } ?: return
    val summary = remember(structure) { IntervalStructures.summary(structure) } ?: return
    var expanded by remember(structureJson) { mutableStateOf(false) }
    val warmupPrefix = stringResource(Res.string.session_structure_warmup_prefix)
    val recoveryPrefix = stringResource(Res.string.session_structure_recovery_prefix)
    val cooldownPrefix = stringResource(Res.string.session_structure_cooldown_prefix)

    Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) Res.string.session_structure_collapse else Res.string.session_structure_expand,
                ),
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            structureStepLines(structure, warmupPrefix, recoveryPrefix, cooldownPrefix).forEach { line ->
                Text(
                    text = "• $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
            }
        }
    }
}

/**
 * One line per top-level step of [structure] (§3.11): a `REPEAT` expands into its work line
 * ("5 × 1000 m @ 3:54" / "2 × (10 × 0:30) @ 371 W") and, when it has one, a recovery line; every
 * other step (warm-up, a continuous work step, cool-down) is one line. Pure — unit-tested
 * (`ZonesUiStateTest`).
 */
fun structureStepLines(
    structure: WorkoutStructure,
    warmupPrefix: String,
    recoveryPrefix: String,
    cooldownPrefix: String,
): List<String> = structure.steps.flatMap { step ->
    when (step.kind) {
        WorkoutStepKind.REPEAT -> repeatLines(step, recoveryPrefix)
        WorkoutStepKind.WARMUP -> listOf(warmupPrefix + " " + stepUnitLabel(step))
        WorkoutStepKind.COOLDOWN -> listOf(cooldownPrefix + " " + stepUnitLabel(step))
        WorkoutStepKind.RECOVERY -> listOf(recoveryPrefix + " " + stepUnitLabel(step))
        WorkoutStepKind.WORK -> listOf(stepUnitLabel(step))
    }
}

private fun repeatLines(step: WorkoutStep, recoveryPrefix: String): List<String> {
    val work = step.children.firstOrNull { it.kind == WorkoutStepKind.WORK } ?: return emptyList()
    val recovery = step.children.firstOrNull { it.kind == WorkoutStepKind.RECOVERY }
    val unit = unitOf(work)
    val core = if (work.repeat > 1) {
        "${step.repeat} × (${work.repeat} × $unit)"
    } else {
        "${step.repeat} × $unit"
    }
    val lines = mutableListOf(core + IntervalStructures.targetSuffix(work))
    recovery?.let { lines += "$recoveryPrefix ${unitOf(it)}" }
    return lines
}

/** "1000 m @ 3:54" / "4:00 in Z4–Z5" — the unit plus its target, for a single (non-repeat) step. */
private fun stepUnitLabel(step: WorkoutStep): String = unitOf(step) + IntervalStructures.targetSuffix(step)

/** "1000 m" or "3:54" — a step's distance or duration, with no target suffix. */
private fun unitOf(step: WorkoutStep): String =
    step.distanceMeters?.let { "${TrimpDefaults.roundHalfUp(it)} m" } ?: IntervalStructures.mmss(step.durationSec ?: 0)

@Preview(showBackground = true)
@Composable
private fun PlannedSessionCardPreview() {
    MyHealthTheme(dynamicColor = false) {
        PlannedSessionCard(
            session = previewSession(),
            actions = PlannedSessionActions(),
            modifier = Modifier.padding(16.dp),
        )
    }
}

internal fun previewSession(
    id: Long = 1L,
    day: Long = 20_710L,
    sessionType: SessionType = SessionType.TEMPO_RUN,
    status: PlannedStatus = PlannedStatus.PLANNED,
    locked: Boolean = false,
): PlannedSession = PlannedSession(
    id = id,
    planId = 1L,
    day = day,
    startMinuteOfDay = null,
    sportType = SportType.RUN_OUTDOOR,
    sessionType = sessionType,
    intensity = Intensity.HIGH,
    targetDurationMin = 50,
    targetDistanceMeters = 10_000.0,
    targetPaceSecPerKm = 285,
    estimatedTrimp = 105.0,
    description = null,
    rationale = null,
    status = status,
    locked = locked,
    linkedActivityId = null,
    sourceSuggestionId = null,
    createdAtMillis = 0L,
    updatedAtMillis = 0L,
)
