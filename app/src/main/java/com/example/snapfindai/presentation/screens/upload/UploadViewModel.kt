package com.example.snapfindai.presentation.screens.upload

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.domain.usecase.DeleteJobUseCase
import com.example.snapfindai.domain.usecase.GetJobHistoryUseCase
import com.example.snapfindai.utils.FileHelper
import com.example.snapfindai.work.MatchPhotosWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Starts matching jobs and reflects their state. It no longer *runs* them:
 * the job lives in [MatchPhotosWorker] so it survives this screen being left,
 * which on a memory-constrained device is the difference between a
 * five-minute job finishing and vanishing.
 *
 * That makes WorkManager the source of truth for "is a job running and how
 * far along". This class maps that into [UploadUiState] rather than tracking
 * it itself, so the screen shows the real state of the job even if the
 * Activity it was started from is long gone.
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    private val getJobHistory: GetJobHistoryUseCase,
    private val deleteJob: DeleteJobUseCase,
    private val workManager: WorkManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    // Every past job, newest first, shown as grid cards on this screen --
    // opening one navigates straight to its Results, bypassing uiState
    // entirely (see MainActivity's onOpenJob wiring).
    private val _jobHistory = MutableStateFlow<List<JobSummary>>(emptyList())
    val jobHistory: StateFlow<List<JobSummary>> = _jobHistory.asStateFlow()

    // Held here rather than in the composable, for two reasons. The screen's
    // own `remember` was lost on a configuration change, so rotating the
    // phone silently threw away what the user had picked. And nothing cleared
    // it when a job ended, so after cancelling one the step buttons still read
    // "Selected" for files the user had just abandoned.
    private val _selfieUri = MutableStateFlow<Uri?>(null)
    val selfieUri: StateFlow<Uri?> = _selfieUri.asStateFlow()

    private val _zipUri = MutableStateFlow<Uri?>(null)
    val zipUri: StateFlow<Uri?> = _zipUri.asStateFlow()

    fun onSelfiePicked(uri: Uri?) { _selfieUri.value = uri }

    fun onZipPicked(uri: Uri?) { _zipUri.value = uri }

    // One-shot: navigating off of `uiState == Success` breaks the moment
    // Results is left via the system back gesture instead of its in-app back
    // button -- that path pops straight through Compose Navigation's own back
    // handler, never touching resetState(), so uiState is still Success when
    // Upload reappears and immediately re-navigates forward. A one-shot event
    // fires exactly once and is never re-derived from state afterward.
    private val _navigateToResults = MutableSharedFlow<Unit>()
    val navigateToResults: SharedFlow<Unit> = _navigateToResults.asSharedFlow()

    /**
     * A finished job's `WorkInfo` keeps being replayed to every new observer
     * until it's pruned, so without remembering which one has already been
     * acted on, reopening the app would re-navigate (or re-show an error) for
     * a job dealt with long ago.
     */
    private var handledWorkId: UUID? = null

    init {
        refreshHistory()
        observeMatchingJob()
    }

    private fun observeMatchingJob() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(MatchPhotosWorker.WORK_NAME).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED,
                    -> {
                        // -1 rather than 0 as the absent marker: zero photos
                        // scored is a real value the job reports at the start.
                        _uiState.value = UploadUiState.Processing(
                            scored = info.progress.getInt(MatchPhotosWorker.KEY_SCORED, -1).takeIf { it >= 0 },
                            total = info.progress.getInt(MatchPhotosWorker.KEY_TOTAL, -1).takeIf { it >= 0 },
                        )
                    }

                    WorkInfo.State.SUCCEEDED -> onceFor(info.id) {
                        val matchCount = info.outputData.getInt(MatchPhotosWorker.KEY_MATCH_COUNT, 0)
                        // That job is over, so the picks that produced it are
                        // no longer a setup for anything.
                        clearPickedFiles()
                        refreshHistory()
                        if (matchCount == 0) {
                            _uiState.value = UploadUiState.Error("No matches found. Try a clearer selfie.")
                        } else {
                            _uiState.value = UploadUiState.Success
                            _navigateToResults.emit(Unit)
                        }
                    }

                    // Picks are deliberately kept on failure: the user most
                    // likely wants to swap one of them, and clearing both
                    // would make them start over to change one.
                    WorkInfo.State.FAILED -> onceFor(info.id) {
                        _uiState.value = UploadUiState.Error(
                            info.outputData.getString(MatchPhotosWorker.KEY_ERROR)
                                ?: "An unknown error occurred."
                        )
                    }

                    // Cancelling already put the screen back to Idle. Handled
                    // here too so a cancel from anywhere else -- a future
                    // notification action, say -- lands in the same place.
                    WorkInfo.State.CANCELLED -> onceFor(info.id) {
                        _uiState.value = UploadUiState.Idle
                    }
                }
            }
        }
    }

    /** Runs [block] the first time a terminal state is seen for [workId], then forgets the job so it can't be replayed. */
    private suspend fun onceFor(workId: UUID, block: suspend () -> Unit) {
        if (handledWorkId == workId) return
        handledWorkId = workId
        block()
        // Clears the finished record, so a later process doesn't observe this
        // same terminal state as if it were news.
        workManager.pruneWork()
    }

    fun submitJob(context: Context) {
        val selfieUri = _selfieUri.value ?: return
        val zipUri = _zipUri.value ?: return
        _uiState.value = UploadUiState.Processing()

        viewModelScope.launch {
            // Built first purely for its id, which names the directory this
            // run owns. Nothing else is touched: a previous run's files live
            // under its own request id, so a worker still unwinding from a
            // cancel can neither have its inputs overwritten nor delete
            // these. That was a real race while every run shared one
            // temp_selfie.jpg -- cancelling a work request only records the
            // cancellation, it doesn't wait for the worker to stop.
            val request = OneTimeWorkRequestBuilder<MatchPhotosWorker>().build()

            // Copied here rather than in the worker, deliberately: a Uri from
            // the photo picker carries a read grant scoped to the Activity
            // that received it, which may be gone by the time a worker runs.
            // The worker derives this same directory from its own id, so no
            // path needs passing.
            val copied = withContext(Dispatchers.IO) {
                val dir = FileHelper.requestWorkDir(context, request.id.toString())
                val selfie = FileHelper.uriToFile(context, selfieUri, File(dir, MatchPhotosWorker.SELFIE_FILE_NAME))
                val zip = FileHelper.uriToFile(context, zipUri, File(dir, MatchPhotosWorker.ZIP_FILE_NAME))
                selfie != null && zip != null
            }
            if (!copied) {
                _uiState.value = UploadUiState.Error("Failed to read the selected files.")
                return@launch
            }

            // No job row is created here. The worker records one keyed by this
            // request on its first run, so a request that never gets enqueued
            // -- or a crash anywhere above -- leaves nothing behind but a
            // directory the startup sweep will reclaim.
            handledWorkId = null
            workManager.enqueueUniqueWork(
                MatchPhotosWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    /**
     * Stops the running job. The worker's own cleanup still runs -- the job
     * row is abandoned and the half-extracted folder removed -- because
     * cancellation reaches it as a normal coroutine cancellation.
     */
    fun cancelJob() {
        _uiState.value = UploadUiState.Idle
        clearPickedFiles()
        workManager.cancelUniqueWork(MatchPhotosWorker.WORK_NAME)
    }

    private fun clearPickedFiles() {
        _selfieUri.value = null
        _zipUri.value = null
    }

    fun resetState() {
        _uiState.value = UploadUiState.Idle
        refreshHistory() // picks up newly-saved/removed jobs when returning from Results
    }

    /**
     * Deletes a past job and the photos this app was storing for it. The
     * user's gallery is untouched -- see [DeleteJobUseCase].
     */
    fun deleteJob(jobId: Long) {
        viewModelScope.launch {
            deleteJob.invoke(jobId)
            refreshHistory()
        }
    }

    private fun refreshHistory() {
        viewModelScope.launch { _jobHistory.value = getJobHistory() }
    }
}
