package com.myhealth.ui.strength

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myhealth.domain.engine.strength.video.VideoEffort
import com.myhealth.resources.*
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.stringResource

/**
 * "Mark done" on a video workout (P26.5): how long and how hard. [hasRecording] — the session is
 * already linked to a watch recording, so nothing is estimated and only the confirmation shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDoneSheet(
    name: String,
    defaultMinutes: Int,
    hasRecording: Boolean,
    onDone: (durationMin: Int, effort: VideoEffort) -> Unit,
    onDismiss: () -> Unit,
) {
    var minutesText by remember(name) { mutableStateOf(defaultMinutes.toString()) }
    var effort by remember(name) { mutableStateOf(VideoEffort.MODERATE) }
    val minutes = minutesText.toIntOrNull()?.takeIf { it in 1..600 }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.video_done_title, name), style = MaterialTheme.typography.titleMedium)
            if (hasRecording) {
                Text(stringResource(Res.string.video_done_recorded), style = MaterialTheme.typography.bodyMedium)
            } else {
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { t -> minutesText = t.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(Res.string.video_import_duration_label)) },
                    suffix = { Text(stringResource(Res.string.video_import_minutes_suffix)) },
                    isError = minutes == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("video_done_minutes"),
                )
                Text(stringResource(Res.string.video_done_effort), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoEffort.entries.forEach { e ->
                        FilterChip(
                            selected = effort == e,
                            onClick = { effort = e },
                            label = { Text(e.label()) },
                        )
                    }
                }
                Text(
                    stringResource(Res.string.video_done_estimate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onDone(minutes ?: defaultMinutes, effort) },
                    enabled = hasRecording || minutes != null,
                    modifier = Modifier.testTag("video_done_save"),
                ) { Text(stringResource(Res.string.video_done_save)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun VideoEffort.label(): String = stringResource(
    when (this) {
        VideoEffort.EASY -> Res.string.video_effort_easy
        VideoEffort.MODERATE -> Res.string.video_effort_moderate
        VideoEffort.HARD -> Res.string.video_effort_hard
        VideoEffort.VERY_HARD -> Res.string.video_effort_very_hard
    },
)

/** P26.5: opens a workout's video in the browser / YouTube app. */
@Composable
fun WatchVideoButton(url: String, modifier: Modifier = Modifier) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val link = url.trim()
    if (!com.myhealth.domain.engine.strength.video.VideoLink.isWebLink(link)) return
    TextButton(
        onClick = { runCatching { uriHandler.openUri(link) } },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
        modifier = modifier.testTag("watch_video"),
    ) {
        androidx.compose.material3.Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(stringResource(Res.string.video_watch))
    }
}
