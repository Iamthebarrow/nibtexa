package io.github.iamthebarrow.nibtexa.sync

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.iamthebarrow.nibtexa.NibTeXaApplication
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.ExportFile
import io.github.iamthebarrow.nibtexa.core.export.ExportResult
import io.github.iamthebarrow.nibtexa.core.export.LatexExporter
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.serialization.BookletJson
import io.github.iamthebarrow.nibtexa.export.BookletPdf
import kotlinx.coroutines.CancellationException
import io.github.iamthebarrow.nibtexa.core.sync.CommitResult
import io.github.iamthebarrow.nibtexa.core.sync.Credentials
import io.github.iamthebarrow.nibtexa.core.sync.FileChange
import io.github.iamthebarrow.nibtexa.core.sync.GitSync
import io.github.iamthebarrow.nibtexa.core.sync.PendingCommit
import io.github.iamthebarrow.nibtexa.core.sync.PushResult
import io.github.iamthebarrow.nibtexa.core.sync.Resolution
import io.github.iamthebarrow.nibtexa.core.sync.SettingsProblem
import io.github.iamthebarrow.nibtexa.core.sync.SettingsValidator
import io.github.iamthebarrow.nibtexa.core.sync.SyncError
import io.github.iamthebarrow.nibtexa.core.sync.SyncException
import io.github.iamthebarrow.nibtexa.core.sync.SyncPlan
import io.github.iamthebarrow.nibtexa.core.sync.SyncSettings
import io.github.iamthebarrow.nibtexa.export.ExportMessages
import io.github.iamthebarrow.nibtexa.export.InkFigureRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the sync is doing right now. */
enum class SyncStep { CHECKING, PREPARING, COMMITTING, PUSHING, REBASING, DISCARDING }

data class SyncUiState(
    val bookletId: String = "",
    val bookletTitle: String = "",
    val settings: SyncSettings = SyncSettings(),
    val hasToken: Boolean = false,
    val settingsProblems: List<SettingsProblem> = emptyList(),
    val step: SyncStep? = null,
    /** A commit that hasn't reached the remote yet. */
    val pending: PendingCommit? = null,
    /** After a rejected push: whether the pending commit may be rebased. Null otherwise. */
    val canRebase: Boolean? = null,
    val plan: SyncPlan? = null,
    /** Conflicts keep the outside version unless the user picks otherwise. */
    val resolution: Resolution = Resolution.KEEP,
    val releaseKept: Boolean = false,
    val changes: List<FileChange> = emptyList(),
    val diffPath: String? = null,
    val commitMessage: String = "",
    val status: String? = null,
)

/**
 * Git sync for one booklet: settings, review, commit and push. Every Git step runs off the
 * main thread and can be canceled; nothing touches the network until the user asks.
 */
class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NibTeXaApplication
    private val sync = app.sync
    private val figures = InkFigureRenderer((application as NibTeXaApplication).pictures)
    private var job: Job? = null

    @Volatile
    private var active: GitSync? = null

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    fun open(bookletId: String) {
        if (_state.value.bookletId == bookletId) return
        val settings = sync.settings.load()
        _state.update { SyncUiState(bookletId = bookletId, settings = settings, hasToken = sync.tokens.hasToken) }
        viewModelScope.launch {
            val title = withContext(Dispatchers.IO) { app.services.repository.loadBooklet(bookletId)?.title }.orEmpty()
            _state.update { it.copy(bookletTitle = title) }
        }
        if (SettingsValidator.problems(settings, sync.policy).isEmpty()) launchStep(SyncStep.CHECKING) { git -> _state.update { it.copy(pending = git.pending()) } }
    }

    // ---- Settings -------------------------------------------------------------------------

    /** Saves [settings], and [token] if it isn't blank (a blank field keeps the saved token). */
    fun saveSettings(settings: SyncSettings, token: String) {
        val problems = SettingsValidator.problems(settings, sync.policy)
        sync.settings.save(settings)
        if (token.isNotBlank()) {
            try {
                sync.tokens.save(token.trim())
            } catch (e: Exception) {
                Log.e(TAG, "Storing the token failed (${e.javaClass.simpleName})") // never the token itself
                _state.update { it.copy(status = text(R.string.sync_token_failed)) }
            }
        }
        _state.update {
            it.copy(
                settings = sync.settings.load(),
                hasToken = sync.tokens.hasToken,
                settingsProblems = problems,
                plan = null,
                changes = emptyList(),
                status = if (problems.isEmpty()) text(R.string.sync_settings_saved) else null,
            )
        }
    }

    fun forgetToken() {
        sync.tokens.clear()
        _state.update { it.copy(hasToken = false, status = text(R.string.sync_token_forgotten)) }
    }

    // ---- Review ---------------------------------------------------------------------------

    /** Exports the booklet and compares it with the remote branch. Nothing is committed. */
    fun prepare() = launchStep(SyncStep.PREPARING) { git ->
        val files = exportFiles() ?: return@launchStep
        val plan = git.prepare(_state.value.bookletId, files, app.services.repository.folderNameOf(_state.value.bookletId))
        _state.update {
            val resolution = Resolution.KEEP
            val changes = plan.changes(resolution)
            it.copy(
                plan = plan,
                resolution = resolution,
                releaseKept = false,
                changes = changes,
                diffPath = null,
                canRebase = null,
                commitMessage = it.commitMessage.ifBlank { text(R.string.sync_default_message, it.bookletTitle) },
                status = text(if (changes.isEmpty()) R.string.sync_up_to_date else R.string.sync_ready, changes.size),
            )
        }
    }

    fun setResolution(resolution: Resolution) = _state.update {
        it.copy(resolution = resolution, changes = it.plan?.changes(resolution, it.releaseKept).orEmpty())
    }

    fun setReleaseKept(release: Boolean) = _state.update {
        it.copy(releaseKept = release, changes = it.plan?.changes(it.resolution, release).orEmpty())
    }

    fun showDiff(path: String?) = _state.update { it.copy(diffPath = path) }

    fun setCommitMessage(message: String) = _state.update { it.copy(commitMessage = message) }

    // ---- Commit and push ------------------------------------------------------------------

    fun commitAndPush() {
        val current = _state.value
        val plan = current.plan ?: return
        if (current.commitMessage.isBlank()) return
        launchStep(SyncStep.COMMITTING) { git ->
            when (val result = git.commit(plan, current.resolution, current.commitMessage, current.releaseKept)) {
                CommitResult.NothingToCommit -> _state.update { it.copy(plan = null, changes = emptyList(), status = text(R.string.sync_up_to_date, 0)) }
                is CommitResult.Committed -> {
                    _state.update { it.copy(pending = result.commit, plan = null, changes = emptyList(), diffPath = null) }
                    _state.update { it.copy(step = SyncStep.PUSHING) }
                    report(git.push())
                }
            }
        }
    }

    /** Pushes a commit left pending by a failure or a restart. */
    fun push() = launchStep(SyncStep.PUSHING) { git -> report(git.push()) }

    fun rebaseAndPush() = launchStep(SyncStep.REBASING) { git -> report(git.rebaseAndPush()) }

    fun discardPending() = launchStep(SyncStep.DISCARDING) { git ->
        git.discardPending()
        _state.update { it.copy(pending = null, canRebase = null, status = text(R.string.sync_discarded)) }
    }

    fun cancel() {
        active?.cancel()
    }

    private fun report(result: PushResult) = when (result) {
        is PushResult.Pushed -> _state.update {
            it.copy(pending = null, canRebase = null, commitMessage = "", status = text(R.string.sync_pushed, result.commitId.take(7), it.settings.branch))
        }
        is PushResult.RemoteAdvanced -> _state.update {
            it.copy(canRebase = result.canRebase, status = text(if (result.canRebase) R.string.sync_remote_advanced_rebase else R.string.sync_remote_advanced_blocked))
        }
    }

    // ---- Plumbing -------------------------------------------------------------------------

    /**
     * Typesets the booklet's PDF (Phase 12). It needs an activity's window ([BookletPdf]), so
     * [SyncActivity] sets it while it exists. Runs on the main thread.
     */
    var pdfRenderer: (suspend (Booklet) -> BookletPdf.Result)? = null

    /**
     * The booklet's export (LaTeX, figures, and the PDF as [PDF_FILE]), plus the editable
     * snapshot if enabled; null if it can't export yet.
     */
    private suspend fun exportFiles(): List<ExportFile>? {
        val booklet = app.services.repository.loadBooklet(_state.value.bookletId)
        if (booklet == null) {
            _state.update { it.copy(status = text(R.string.sync_no_booklet)) }
            return null
        }
        val files = when (val result = withContext(Dispatchers.Default) { LatexExporter.export(booklet, figures) }) {
            is ExportResult.Invalid -> {
                _state.update { it.copy(status = text(R.string.export_blocked, ExportMessages.describe(app, booklet, result.problems))) }
                return null
            }
            is ExportResult.Success -> result.files
        }
        // Without the PDF the plan would delete the one already pushed, so stop instead.
        val render = pdfRenderer ?: throw SyncException(SyncError.Failed(text(R.string.sync_pdf_failed, "no window")))
        val rendered = try {
            withContext(Dispatchers.Main) { render(booklet) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "PDF for sync failed", e)
            throw SyncException(SyncError.Failed(text(R.string.sync_pdf_failed, e.message ?: e.javaClass.simpleName)), e)
        }
        val pdf = when (val result = rendered) {
            is BookletPdf.Result.Written -> ExportFile(PDF_FILE, result.bytes)
            is BookletPdf.Result.Invalid -> {
                _state.update { it.copy(status = text(R.string.export_blocked, ExportMessages.describe(app, booklet, result.problems))) }
                return null
            }
        }
        return files + pdf + listOfNotNull(BookletJson.encode(booklet).takeIf { _state.value.settings.includeSource }?.let { ExportFile(SOURCE_FILE, it) })
    }

    private fun launchStep(step: SyncStep, block: suspend (GitSync) -> Unit) {
        if (job?.isActive == true) return
        val settings = sync.settings.load()
        val problems = SettingsValidator.problems(settings, sync.policy)
        if (problems.isNotEmpty()) {
            _state.update { it.copy(settingsProblems = problems) }
            return
        }
        _state.update { it.copy(step = step, status = null, settingsProblems = emptyList()) }
        job = viewModelScope.launch(Dispatchers.IO) {
            val token = sync.tokens.load()
            val credentials = token?.let { Credentials(settings.username.ifBlank { DEFAULT_USERNAME }, it) }
            val git = GitSync(sync.workDir, settings, credentials, sync.policy)
            active = git
            try {
                block(git)
            } catch (e: SyncException) {
                // The error carries no token (GitSync redacts it); the cause is not logged.
                Log.w(TAG, "Sync step $step failed: ${e.error}")
                _state.update { it.copy(status = describe(e.error), pending = runCatching { git.pending() }.getOrNull() ?: it.pending) }
            } finally {
                active = null
                _state.update { it.copy(step = null) }
            }
        }
    }

    private fun describe(error: SyncError): String = when (error) {
        is SyncError.InvalidSettings -> text(R.string.sync_error_settings)
        SyncError.AuthFailed -> text(R.string.sync_error_auth)
        is SyncError.Unreachable -> text(R.string.sync_error_unreachable, error.detail)
        is SyncError.Rejected -> text(R.string.sync_error_rejected, error.detail)
        SyncError.PendingCommit -> text(R.string.sync_error_pending)
        SyncError.CorruptRecord -> text(R.string.sync_error_record)
        SyncError.Canceled -> text(R.string.sync_canceled)
        is SyncError.Failed -> text(R.string.sync_error_failed, error.detail)
    }

    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private companion object {
        const val TAG = "SyncViewModel"
        const val SOURCE_FILE = "source/booklet.json"

        /** The on-device PDF (Phase 12), next to main.tex. */
        const val PDF_FILE = "main.pdf"

        /** Hosts that ignore the username with a token still need a non-empty one. */
        const val DEFAULT_USERNAME = "git"
    }
}
