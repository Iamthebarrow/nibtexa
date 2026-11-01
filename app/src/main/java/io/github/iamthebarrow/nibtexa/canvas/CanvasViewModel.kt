package io.github.iamthebarrow.nibtexa.canvas

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkEditor
import io.github.iamthebarrow.nibtexa.core.model.corpus.CorpusPrompt
import io.github.iamthebarrow.nibtexa.core.model.corpus.CorpusPrompts
import io.github.iamthebarrow.nibtexa.core.model.corpus.CorpusSamples
import io.github.iamthebarrow.nibtexa.corpus.CorpusFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class CanvasUiState(
    val document: InkDocument = InkDocument.EMPTY,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val fingerDraws: Boolean = false,
    val prompts: List<CorpusPrompt> = emptyList(),
    val promptIndex: Int = 0,
    val saving: Boolean = false,
    val savedCount: Int = 0,
    val message: String? = null,
) {
    val prompt: CorpusPrompt? get() = prompts.getOrNull(promptIndex)
    val canSave: Boolean get() = prompt != null && document.strokes.isNotEmpty() && !saving
}

/**
 * State for corpus capture (CorpusCaptureActivity).
 *
 * The page being written survives configuration changes but not process death, by design:
 * it is a scratch page for one prompt. Saved samples are on disk.
 */
class CanvasViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {

    /** Math prompts by default; the text trial set (ADR-0007) when the activity asks for it. */
    private val textSet: Boolean = savedState.get<String>(PROMPT_SET) == PROMPT_SET_TEXT

    private var editor = InkEditor()
    private val store = CorpusFileStore(
        application.getExternalFilesDir(CORPUS_DIR) ?: File(application.filesDir, CORPUS_DIR),
    )

    private val _state = MutableStateFlow(CanvasUiState())
    val state: StateFlow<CanvasUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val prompts = withContext(Dispatchers.IO) { loadPrompts() }
            _state.update { it.copy(prompts = prompts) }
        }
    }

    fun onStrokeCaptured(stroke: CapturedStroke) {
        editor.addStroke(stroke)
        publish()
    }

    fun undo() {
        if (editor.undo()) publish()
    }

    fun redo() {
        if (editor.redo()) publish()
    }

    fun clear() {
        editor.clear()
        publish()
    }

    fun setFingerDraws(enabled: Boolean) {
        _state.update { it.copy(fingerDraws = enabled) }
    }

    fun nextPrompt() = movePrompt(+1)

    fun previousPrompt() = movePrompt(-1)

    fun saveSample() {
        val current = _state.value
        val prompt = current.prompt ?: return
        if (!current.canSave) return
        _state.update { it.copy(saving = true, message = null) }

        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                try {
                    val files = CorpusSamples.build(prompt, current.document, timestamp(), deviceDescription(), mathTruth = !textSet)
                    Result.success(store.save(files))
                } catch (e: IOException) {
                    Log.w(TAG, "Saving corpus sample failed", e)
                    Result.failure(e)
                }
            }
            result
                .onSuccess { files ->
                    editor = InkEditor()
                    _state.update {
                        it.copy(saving = false, savedCount = it.savedCount + 1, message = app.getString(R.string.sample_saved, files.first().nameWithoutExtension))
                    }
                    publish()
                    movePrompt(+1)
                }
                .onFailure { e ->
                    _state.update { it.copy(saving = false, message = app.getString(R.string.sample_save_failed, e.message.orEmpty())) }
                }
        }
    }

    private fun movePrompt(delta: Int) {
        _state.update { s ->
            if (s.prompts.isEmpty()) s else s.copy(promptIndex = (s.promptIndex + delta).mod(s.prompts.size))
        }
    }

    private fun publish() {
        _state.update { it.copy(document = editor.document, canUndo = editor.canUndo, canRedo = editor.canRedo) }
    }

    private fun loadPrompts(): List<CorpusPrompt> {
        val asset = if (textSet) TEXT_PROMPTS_ASSET else PROMPTS_ASSET
        return try {
            getApplication<Application>().assets.open(asset).bufferedReader().use { CorpusPrompts.parse(it.readText()) }
        } catch (e: IOException) {
            Log.e(TAG, "Cannot read $asset", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Malformed $asset", e)
            emptyList()
        }
    }

    private fun timestamp(): String = TIMESTAMP.format(Instant.now())

    private fun deviceDescription(): String = "${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}"

    companion object {
        private const val TAG = "CanvasViewModel"
        const val CORPUS_DIR = "corpus"
        private const val PROMPTS_ASSET = "corpus/prompts.tsv"
        private const val TEXT_PROMPTS_ASSET = "corpus/text-prompts.tsv"

        /** Intent extra choosing the prompt set; [PROMPT_SET_TEXT] for the text trial. */
        const val PROMPT_SET = "promptSet"
        const val PROMPT_SET_TEXT = "text"
        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}
