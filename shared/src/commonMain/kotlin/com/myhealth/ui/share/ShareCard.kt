package com.myhealth.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import com.myhealth.domain.repository.ShareRepository
import com.myhealth.domain.share.LocalShareState
import com.myhealth.domain.share.ShareRef
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.resources.*
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.stringResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareCardState(
    val connected: Boolean = false,
    val state: LocalShareState? = null,
    val working: Boolean = false,
    val error: AppError? = null,
    /** Set once "Make my copy" made one; the screen opens it. */
    val copy: ShareRef? = null,
) {
    /** Someone else's item: shown, never saved here. */
    val readOnly: Boolean get() = state is LocalShareState.Theirs
}

/** Backs [ShareCard] for one item. */
class ShareCardViewModel(private val repo: ShareRepository, private val ref: ShareRef) : ViewModel() {

    private val _state = MutableStateFlow(ShareCardState())
    val state: StateFlow<ShareCardState> = _state.asStateFlow()

    init {
        combine(repo.setup, repo.links) { setup, _ -> setup != null }
            .onEach { connected -> _state.update { it.copy(connected = connected, state = repo.stateOf(ref)) } }
            .launchIn(viewModelScope)
    }

    /** After an edit was saved, "changed since upload" may have flipped. */
    fun recheck() {
        viewModelScope.launch { _state.update { it.copy(state = repo.stateOf(ref)) } }
    }

    fun share() = act { repo.share(ref) }
    fun stop() = act { repo.stopSharing(ref) }
    fun copy() = act {
        repo.fork(ref).also { r -> if (r is Outcome.Ok) _state.update { it.copy(copy = r.value) } }
    }

    fun copyOpened() = _state.update { it.copy(copy = null) }

    private fun act(block: suspend () -> Outcome<*>) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            val r = try { block() } finally { _state.update { it.copy(working = false) } }
            _state.update { it.copy(error = (r as? Outcome.Err)?.error, state = repo.stateOf(ref)) }
        }
    }
}

/** The share state of [ref] for its screen; null ref (a new, unsaved item) → nothing to share yet. */
@Composable
fun rememberShareCard(ref: ShareRef?): ShareCardViewModel? {
    if (ref == null) return null
    val graph = com.myhealth.di.appGraph()
    return androidx.lifecycle.viewmodel.compose.viewModel(
        key = "share_${ref.kind}_${ref.localKey}",
        factory = androidx.lifecycle.viewmodel.viewModelFactory { initializer { ShareCardViewModel(graph.shareRepo, ref) } },
    )
}

/**
 * P27.5: "Share with family" / "Upload changes" / "Stop sharing" on my items; "by Anna · read-only"
 * and "Make my copy" on someone else's; "based on … by …" on copies. Silent when not connected and
 * private (sharing stays out of the way until a library is joined).
 */
@Composable
fun ShareCard(vm: ShareCardViewModel?, onOpenCopy: (ShareRef) -> Unit, modifier: Modifier = Modifier) {
    vm ?: return
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.recheck(); onPauseOrDispose { } }
    LaunchedEffect(s.copy) { s.copy?.let { vm.copyOpened(); onOpenCopy(it) } }
    val state = s.state ?: return
    if (!s.connected && state == LocalShareState.Private) return
    SectionCard(stringResource(Res.string.share_title), modifier.testTag("share_card")) {
        val line = when (state) {
            LocalShareState.Private -> stringResource(Res.string.share_state_private)
            is LocalShareState.Shared -> stringResource(if (state.changed) Res.string.share_state_changed else Res.string.share_state_shared)
            is LocalShareState.Theirs -> stringResource(
                if (state.gone) Res.string.share_state_theirs_gone else Res.string.share_state_theirs, state.authorName,
            )
            is LocalShareState.Fork -> stringResource(Res.string.share_state_fork, state.basedOnName, state.basedOnAuthor) +
                (state.shared?.let { "\n" + stringResource(if (it.changed) Res.string.share_state_changed else Res.string.share_state_shared) } ?: "")
        }
        Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("share_state"))
        if (state is LocalShareState.Theirs) {
            Text(stringResource(Res.string.share_theirs_hint, state.authorName), style = MaterialTheme.typography.bodySmall)
        }
        if (!s.connected && state !is LocalShareState.Theirs) {
            Text(stringResource(Res.string.share_not_connected_hint), style = MaterialTheme.typography.bodySmall)
        }
        s.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val shared = when (state) {
            is LocalShareState.Shared -> state
            is LocalShareState.Fork -> state.shared
            else -> null
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state is LocalShareState.Theirs -> Button(onClick = vm::copy, enabled = !s.working, modifier = Modifier.testTag("share_copy")) {
                    Text(stringResource(Res.string.share_action_copy))
                }
                !s.connected -> Unit
                shared == null -> Button(onClick = vm::share, enabled = !s.working, modifier = Modifier.testTag("share_share")) {
                    Text(stringResource(Res.string.share_action_share))
                }
                else -> {
                    if (shared.changed) {
                        Button(onClick = vm::share, enabled = !s.working, modifier = Modifier.testTag("share_upload")) {
                            Text(stringResource(Res.string.share_action_upload))
                        }
                    }
                    TextButton(onClick = vm::stop, enabled = !s.working, modifier = Modifier.testTag("share_stop")) {
                        Text(stringResource(Res.string.share_action_stop))
                    }
                }
            }
        }
    }
}
