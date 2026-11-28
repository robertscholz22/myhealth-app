package com.myhealth.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.ai.AiConnectionCode
import com.myhealth.domain.ai.AiDataGroup
import com.myhealth.domain.ai.AiSetup
import com.myhealth.domain.repository.AiChange
import com.myhealth.domain.repository.AiChangeResult
import com.myhealth.domain.repository.AiPairingCode
import com.myhealth.domain.repository.AiPlanningRepository
import com.myhealth.domain.repository.AiRelayStatus
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AiMessage {
    data class Failed(val error: AppError) : AiMessage
    data class Synced(val applied: Int, val rejected: Int) : AiMessage
    data class Undone(val count: Int) : AiMessage
    data object InvalidCode : AiMessage
}

/** One sync's changes, newest batch first. */
data class AiBatch(val batchId: String, val atMillis: Long, val changes: List<AiChange>) {
    val canUndo: Boolean get() = changes.any { it.result == AiChangeResult.APPLIED }
}

data class AiUiState(
    val loaded: Boolean = false,
    val setup: AiSetup? = null,
    val codeText: String = "",
    val groups: Set<AiDataGroup> = AiDataGroup.ALL,
    val working: Boolean = false,
    val message: AiMessage? = null,
    val pairing: AiPairingCode? = null,
    val relay: AiRelayStatus? = null,
    val batches: List<AiBatch> = emptyList(),
    val confirmDisconnect: Boolean = false,
) {
    val mcpUrl: String? get() = setup?.relayUrl?.let { "$it/mcp" }
}

/** P28.5: More › AI planning. Opening the screen syncs once, so the log and the relay are current. */
class AiPlanningViewModel(private val repo: AiPlanningRepository) : ViewModel() {

    private val _state = MutableStateFlow(AiUiState())
    val state: StateFlow<AiUiState> = _state.asStateFlow()
    private var syncedOnOpen = false

    init {
        combine(repo.setup, repo.changes) { setup, changes -> setup to changes }
            .onEach { (setup, changes) ->
                _state.update { s ->
                    s.copy(
                        loaded = true, setup = setup, groups = setup?.groups ?: s.groups,
                        batches = changes.groupBy { it.batchId }.map { (id, rows) -> AiBatch(id, rows.maxOf { it.appliedAtMillis }, rows.sortedBy { it.id }) }
                            .sortedByDescending { it.atMillis },
                        pairing = if (setup == null) null else s.pairing,
                    )
                }
                if (setup != null && !syncedOnOpen) {
                    syncedOnOpen = true
                    sync(quiet = true)
                }
            }
            .launchIn(viewModelScope)
    }

    fun setCodeText(text: String) = _state.update { it.copy(codeText = text) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun askDisconnect(ask: Boolean) = _state.update { it.copy(confirmDisconnect = ask) }

    fun toggleGroup(group: AiDataGroup) {
        if (group == AiDataGroup.BASICS) return
        val groups = _state.value.groups.let { if (group in it) it - group else it + group }
        _state.update { it.copy(groups = groups) }
        if (_state.value.setup != null) run { repo.setGroups(groups) }
    }

    fun connect() {
        val connection = AiConnectionCode.decode(_state.value.codeText)
            ?: return _state.update { it.copy(message = AiMessage.InvalidCode) }
        run {
            repo.connect(connection, _state.value.groups).also { if (it is Outcome.Ok) _state.update { s -> s.copy(codeText = "") } }
        }
    }

    fun sync(quiet: Boolean = false) = run(quiet) {
        repo.sync().also { r ->
            if (r is Outcome.Ok && !quiet) _state.update { it.copy(message = AiMessage.Synced(r.value.applied, r.value.rejected)) }
            refreshRelay()
        }
    }

    fun showPairingCode() = run {
        repo.pairingCode().also { r -> if (r is Outcome.Ok) _state.update { it.copy(pairing = r.value) } }
    }

    fun undo(batchId: String) = run {
        repo.undo(batchId).also { r -> if (r is Outcome.Ok) _state.update { it.copy(message = AiMessage.Undone(r.value)) } }
    }

    fun disconnect() = run {
        _state.update { it.copy(confirmDisconnect = false, relay = null, pairing = null) }
        repo.disconnect()
    }

    private suspend fun refreshRelay() {
        val r = repo.relayStatus()
        _state.update { it.copy(relay = (r as? Outcome.Ok)?.value ?: it.relay) }
    }

    /** Runs [block] with the progress bar; an error becomes the message unless [quiet]. */
    private fun run(quiet: Boolean = false, block: suspend () -> Outcome<*>) {
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            val r = block()
            _state.update { s ->
                s.copy(working = false, message = if (r is Outcome.Err && !quiet) AiMessage.Failed(r.error) else s.message)
            }
        }
    }
}
