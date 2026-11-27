package com.myhealth.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.domain.repository.ImportResult
import com.myhealth.domain.repository.LibrarySnapshot
import com.myhealth.domain.repository.ShareRepository
import com.myhealth.domain.repository.ShareSetup
import com.myhealth.domain.share.RemoteStatus
import com.myhealth.domain.share.ShareCode
import com.myhealth.domain.share.ShareConnection
import com.myhealth.domain.share.ShareKind
import com.myhealth.domain.share.ShareLink
import com.myhealth.domain.share.SharePlanner
import com.myhealth.domain.share.ShareRules
import com.myhealth.domain.share.SharedItem
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

/** Which part of the not-connected screen is open. */
enum class ShareMode { CHOOSE, CREATE, JOIN }

/** One library row with its status as seen from this device. */
data class LibraryRow(val item: SharedItem, val status: RemoteStatus)

/** What the screen says after an action. */
sealed interface ShareMessage {
    data class Failed(val error: AppError) : ShareMessage
    data class Imported(val result: ImportResult) : ShareMessage
    data class Uploaded(val count: Int) : ShareMessage
    data object InvalidCode : ShareMessage
    data object InvalidProjectId : ShareMessage
}

data class ShareUiState(
    val setup: ShareSetup? = null,
    val loaded: Boolean = false,
    val mode: ShareMode = ShareMode.CHOOSE,
    val name: String = "",
    val codeText: String = "",
    val projectId: String = "",
    val apiKey: String = "",
    /** Generated when "Create" opens; it is in the rules and in the code. */
    val joinCode: String = "",
    val working: Boolean = false,
    val message: ShareMessage? = null,
    val library: LibrarySnapshot? = null,
    val links: List<ShareLink> = emptyList(),
    val filter: ShareKind? = null,
    /** Only items by this author uid; null = everyone. */
    val author: String? = null,
    val query: String = "",
    val changedCount: Int = 0,
) {
    val rules: String get() = if (joinCode.isEmpty()) "" else ShareRules.text(joinCode)

    /** Every library item with its status, unfiltered. */
    val allRows: List<LibraryRow>
        get() {
            val items = library?.items ?: return emptyList()
            val byShare = links.filter { it.shareId != null }.associateBy { it.shareId!! }
            return items
                .sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))
                .map { LibraryRow(it, SharePlanner.status(it, byShare, setup?.uid)) }
        }

    /** The creator filter, only while that creator is still listed and there is a choice (chips shown). */
    val activeAuthor: String?
        get() = author?.takeIf { uid -> authors.size > 1 && authors.any { it.first == uid } }

    val rows: List<LibraryRow>
        get() = allRows.filter { row ->
            val by = activeAuthor
            (filter == null || row.item.kind == filter) &&
                (by == null || row.item.ownerUid == by) &&
                (query.isBlank() || row.item.name.contains(query.trim(), ignoreCase = true))
        }

    /** Who has items in the library, by name: (uid, name), me first. */
    val authors: List<Pair<String, String>>
        get() = library?.items.orEmpty()
            .groupBy { it.ownerUid }
            .map { (uid, items) -> uid to items.maxBy { it.updatedAtMillis }.authorName }
            .sortedWith(compareBy({ it.first != setup?.uid }, { it.second.lowercase() }))

    val updatableCount: Int get() = allRows.count { it.status == RemoteStatus.UPDATE_AVAILABLE }
}

/** Backs [ShareLibraryScreen]: set up or join a library, browse it, import, update, upload. */
class ShareLibraryViewModel(private val repo: ShareRepository) : ViewModel() {

    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state.asStateFlow()

    init {
        combine(repo.setup, repo.links, repo.library) { setup, links, library -> Triple(setup, links, library) }
            .onEach { (setup, links, library) ->
                val first = !_state.value.loaded
                _state.update {
                    it.copy(setup = setup, links = links, library = library, loaded = true, name = if (first) setup?.displayName ?: it.name else it.name)
                }
                if (first && setup != null) refresh()
            }
            .launchIn(viewModelScope)
    }

    fun setMode(mode: ShareMode) = _state.update {
        it.copy(mode = mode, message = null, joinCode = if (mode == ShareMode.CREATE && it.joinCode.isEmpty()) ShareCode.newJoinCode() else it.joinCode)
    }

    fun setName(v: String) = _state.update { it.copy(name = v.take(MAX_NAME)) }
    fun setCodeText(v: String) = _state.update { it.copy(codeText = v, message = null) }
    fun setProjectId(v: String) = _state.update { it.copy(projectId = v.trim().lowercase(), message = null) }
    fun setApiKey(v: String) = _state.update { it.copy(apiKey = v.trim(), message = null) }
    fun setFilter(kind: ShareKind?) = _state.update { it.copy(filter = kind) }
    fun setAuthor(uid: String?) = _state.update { it.copy(author = uid) }
    fun setQuery(v: String) = _state.update { it.copy(query = v) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun join() {
        val connection = ShareCode.decode(_state.value.codeText)
            ?: return _state.update { it.copy(message = ShareMessage.InvalidCode) }
        connect(connection)
    }

    fun create() {
        val s = _state.value
        if (!ShareCode.validProjectId(s.projectId)) return _state.update { it.copy(message = ShareMessage.InvalidProjectId) }
        connect(ShareConnection(s.projectId, s.apiKey, s.joinCode))
    }

    private fun connect(connection: ShareConnection) = run {
        when (val r = repo.connect(connection, _state.value.name)) {
            is Outcome.Err -> r
            is Outcome.Ok -> {
                _state.update { it.copy(mode = ShareMode.CHOOSE, codeText = "", apiKey = "") }
                refreshNow()
                r
            }
        }
    }

    fun rename() = run { repo.rename(_state.value.name) }

    fun leave() = run {
        repo.leave().also { _state.update { it.copy(filter = null, author = null, query = "") } }
    }

    fun refresh() = run { refreshNow() }

    private suspend fun refreshNow(): Outcome<*> {
        val r = repo.refresh()
        _state.update { it.copy(changedCount = repo.changedMine().size) }
        return r
    }

    fun import(shareId: String) = run {
        when (val r = repo.import(listOf(shareId))) {
            is Outcome.Err -> r
            is Outcome.Ok -> r.also { _state.update { s -> s.copy(message = ShareMessage.Imported(r.value)) } }
        }
    }

    fun updateAll() = run {
        when (val r = repo.updateAll()) {
            is Outcome.Err -> r
            is Outcome.Ok -> r.also { _state.update { s -> s.copy(message = ShareMessage.Imported(r.value)) } }
        }
    }

    fun uploadChanges() = run {
        when (val r = repo.pushChanges()) {
            is Outcome.Err -> r
            is Outcome.Ok -> {
                refreshNow()
                r.also { _state.update { s -> s.copy(message = ShareMessage.Uploaded(r.value)) } }
            }
        }
    }

    fun removeFromLibrary(shareId: String) = run {
        when (val r = repo.deleteRemote(shareId)) {
            is Outcome.Err -> r
            is Outcome.Ok -> refreshNow()
        }
    }

    /** Runs one library action at a time; an error becomes the message. */
    private fun run(block: suspend () -> Outcome<*>) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            val r = try {
                block()
            } finally {
                _state.update { it.copy(working = false) }
            }
            if (r is Outcome.Err) _state.update { it.copy(message = ShareMessage.Failed(r.error)) }
        }
    }

    private companion object {
        const val MAX_NAME = 40
    }
}
