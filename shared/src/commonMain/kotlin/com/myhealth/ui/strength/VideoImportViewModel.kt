package com.myhealth.ui.strength

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.PlatformClock
import com.myhealth.domain.engine.strength.ExerciseLibrary
import com.myhealth.domain.engine.strength.video.VideoLink
import com.myhealth.domain.engine.strength.video.VideoWorkoutBuilder
import com.myhealth.domain.model.ExerciseAlias
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.repository.UserExerciseRepository
import com.myhealth.domain.repository.VideoTitleSource
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs [VideoImportScreen] (P26.4): step 1 "Video" (link, title, name, duration, the pasted list),
 * step 2 "Check exercises" (one row per move, matched against the library). [workoutId] re-checks
 * a saved video workout and opens on step 2; [sharedUrl] / [sharedTitle] come from a share.
 */
class VideoImportViewModel(
    private val strengthRepo: StrengthRepository,
    private val userExerciseRepo: UserExerciseRepository,
    private val titleSource: VideoTitleSource,
    private val clock: PlatformClock,
    private val workoutId: Long?,
    sharedUrl: String?,
    sharedTitle: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(VideoImportUiState(workoutId = workoutId))
    val state: StateFlow<VideoImportUiState> = _state.asStateFlow()

    var library: ExerciseLibrary = ExerciseLibrary.CATALOG_ONLY
        private set
    private var aliases: Map<String, String> = emptyMap()
    private var base: StrengthWorkout? = null
    private var previousBySourceLine: Map<String, String> = emptyMap()
    private var titleJob: Job? = null
    private var pendingCreateKey: String? = null

    private val _saved = Channel<Long>(Channel.BUFFERED)
    /** The saved workout's id; the screen then leaves. */
    val saved = _saved.receiveAsFlow()

    init {
        viewModelScope.launch {
            library = userExerciseRepo.getLibrary()
            aliases = userExerciseRepo.getAliases().associate { it.normalisedName to it.exerciseId }
            val workout = workoutId?.let { strengthRepo.getById(it) }
            base = workout
            if (workout != null) {
                previousBySourceLine = workout.exercises.mapNotNull { row -> row.sourceLine?.let { it to row.exerciseId } }.toMap()
                val text = workout.sourceText ?: workout.exercises.sortedBy { it.orderIndex }
                    .mapNotNull { library.byId(it.exerciseId)?.name }.joinToString("\n")
                _state.update {
                    it.copy(
                        loading = false,
                        url = workout.videoUrl.orEmpty(),
                        title = workout.videoTitle.orEmpty(),
                        name = workout.name,
                        nameEdited = true,
                        durationMin = workout.durationMin,
                        text = text,
                        kindOverride = workout.kind,
                    )
                }
                toCheck()
                // The saved kind is a manual choice only when it differs from the derived one.
                _state.update { s ->
                    if (s.kindOverride == VideoWorkoutBuilder.deriveKind(s.videoRows(library))) s.copy(kindOverride = null) else s
                }
            } else {
                val url = sharedUrl?.let(VideoLink::normalise).orEmpty()
                _state.update {
                    it.copy(loading = false, url = url, title = sharedTitle.orEmpty(), name = sharedTitle.orEmpty())
                }
                if (sharedTitle.isNullOrBlank() && url.isNotEmpty()) fetchTitle(url)
            }
        }
    }

    fun setUrl(url: String) {
        _state.update { it.copy(url = url) }
        titleJob?.cancel()
        val trimmed = url.trim()
        if (VideoLink.isYouTube(trimmed) && _state.value.title.isBlank()) {
            titleJob = viewModelScope.launch {
                delay(TITLE_DEBOUNCE_MILLIS)
                fetchTitle(trimmed)
            }
        }
    }

    fun refetchTitle() {
        val url = _state.value.url.trim()
        if (VideoLink.isYouTube(url)) viewModelScope.launch { fetchTitle(url, overwrite = true) }
    }

    private suspend fun fetchTitle(url: String, overwrite: Boolean = false) {
        _state.update { it.copy(titleLoading = true) }
        val title = titleSource.titleOf(url)
        _state.update { s ->
            if (title == null || (!overwrite && s.title.isNotBlank())) {
                s.copy(titleLoading = false)
            } else {
                s.copy(titleLoading = false, title = title, name = if (s.nameEdited && s.name.isNotBlank()) s.name else title)
            }
        }
    }

    fun setTitle(title: String) = _state.update { s ->
        s.copy(title = title, name = if (s.nameEdited) s.name else title)
    }

    fun setName(name: String) = _state.update { it.copy(name = name, nameEdited = true) }

    fun setDuration(minutes: Int?) = _state.update { it.copy(durationMin = minutes) }

    fun setText(text: String) = _state.update { it.copy(text = text) }

    fun next() {
        if (!_state.value.canContinue) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        toCheck()
    }

    private fun toCheck() {
        val s = _state.value
        val (lines, rows) = VideoImport.rows(s.text, s.includedSkips, library, aliases, previousBySourceLine, s.rows)
        _state.update { it.copy(step = VideoImportStep.CHECK, lines = lines, rows = rows, showErrors = false) }
    }

    fun back() = _state.update { it.copy(step = VideoImportStep.VIDEO) }

    fun setSkipIncluded(lineIndex: Int, included: Boolean) {
        _state.update { it.copy(includedSkips = if (included) it.includedSkips + lineIndex else it.includedSkips - lineIndex) }
        toCheck()
    }

    fun choose(key: String, exerciseId: String) {
        val exercise = library.byId(exerciseId) ?: return
        _state.update { it.copy(rows = VideoImport.choose(it.rows, key, exercise)) }
    }

    fun setExcluded(key: String, excluded: Boolean) =
        _state.update { it.copy(rows = VideoImport.setExcluded(it.rows, key, excluded)) }

    fun setAmounts(key: String, sets: Int, reps: Int?, seconds: Int?) =
        _state.update { it.copy(rows = VideoImport.setAmounts(it.rows, key, sets, reps, seconds)) }

    fun setKind(kind: StrengthWorkoutKind?) = _state.update { it.copy(kindOverride = kind) }

    /** The row whose "Create … as new exercise" opened the editor. */
    fun startCreate(key: String) {
        pendingCreateKey = key
    }

    /** The editor saved [exerciseId] (or picked an existing one) for the pending row. */
    fun onCreated(exerciseId: String) {
        val key = pendingCreateKey ?: return
        pendingCreateKey = null
        viewModelScope.launch {
            library = userExerciseRepo.getLibrary()
            choose(key, exerciseId)
        }
    }

    fun dismissUnmatched() = _state.update { it.copy(confirmUnmatched = false) }

    fun save(force: Boolean = false) {
        val s = _state.value
        if (s.saving) return
        if (!force && s.unmatchedCount > 0) {
            _state.update { it.copy(confirmUnmatched = true) }
            return
        }
        val rows = s.videoRows(library)
        if (rows.isEmpty()) {
            _state.update { it.copy(confirmUnmatched = false, saveFailed = true) }
            return
        }
        _state.update { it.copy(saving = true, confirmUnmatched = false, saveFailed = false) }
        viewModelScope.launch {
            val now = clock.millis()
            val workout = VideoWorkoutBuilder.build(
                base = base,
                name = s.name,
                videoUrl = s.url,
                videoTitle = s.title,
                durationMin = s.durationMin,
                sourceText = s.text,
                rows = rows,
                kind = s.kindOverride,
                nowMillis = now,
            )
            when (val result = strengthRepo.upsertWorkout(workout)) {
                is Outcome.Ok -> {
                    VideoImport.learnedAliases(s.rows).forEach { (name, id) ->
                        userExerciseRepo.putAlias(ExerciseAlias(name, id, now))
                    }
                    _saved.send(result.value)
                }
                is Outcome.Err -> _state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    private companion object {
        const val TITLE_DEBOUNCE_MILLIS = 600L
    }
}
