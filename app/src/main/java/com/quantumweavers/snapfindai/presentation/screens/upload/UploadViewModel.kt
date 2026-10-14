package com.quantumweavers.snapfindai.presentation.screens.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.quantumweavers.snapfindai.domain.usecase.DeleteJobUseCase
import com.quantumweavers.snapfindai.domain.usecase.GetJobUseCase
import com.quantumweavers.snapfindai.domain.usecase.ObserveJobHistoryUseCase
import com.quantumweavers.snapfindai.domain.usecase.PhotoSaveEvent
import com.quantumweavers.snapfindai.domain.usecase.SaveMatchedPhotosUseCase
import com.quantumweavers.snapfindai.utils.FileHelper
import com.quantumweavers.snapfindai.work.MatchPhotosWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Starts matching jobs and reflects their state. It no longer *runs* them:
 * the job lives in [MatchPhotosWorker] so it survives this screen being left,
 * which on a memory-constrained device is the difference between a
 * five-minute job finishing and vanishing.
 *
 * That makes WorkManager the source of truth for "is a job running and how
 * far along". This class maps that into [UploadScreenState] rather than
 * tracking it itself, so the screen shows the real state of the job even if
 * the Activity it was started from is long gone.
 *
 * One `StateFlow` of one state, not nine of their parts -- see
 * [UploadScreenState] for what that was costing. Every mutation goes through
 * `update {}`, which is a compare-and-set loop rather than a read followed by
 * a write, so two updates can't interleave and lose one of their changes.
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    observeJobHistory: ObserveJobHistoryUseCase,
    private val deleteJob: DeleteJobUseCase,
    private val getJob: GetJobUseCase,
    private val saveMatchedPhotos: SaveMatchedPhotosUseCase,
    private val workManager: WorkManager,
) : ViewModel() {

    private val _state = MutableStateFlow(UploadScreenState())
    val state: StateFlow<UploadScreenState> = _state.asStateFlow()

    // One-shot, rather than something the screen re-derives from
    // `matching == Success`. Reading it off state broke whenever Results was
    // left by the system back gesture instead of its in-app back button: that
    // path pops straight through Compose Navigation's own back handler, so
    // nothing reset the state, it was still Success when Upload reappeared,
    // and the screen immediately navigated forward again. An event fires
    // exactly once and is never re-derived afterwards.
    //
    // A Channel specifically, because this one has to arrive. On a bare
    // SharedFlow `emit` suspends until something is collecting, so a job
    // finishing while the user was on another screen parked that coroutine
    // for as long as they stayed away. A buffered channel takes the event
    // immediately and hands it over when the screen is next shown -- the
    // user started this job, and being taken to its results on their return
    // is the point of the event.
    private val _navigateToResults = Channel<Unit>(Channel.BUFFERED)
    val navigateToResults: Flow<Unit> = _navigateToResults.receiveAsFlow()

    // The opposite choice, for the opposite kind of event. These say what a
    // batch did, and a message is only true at the moment it is produced --
    // replaying "17 saved" at whoever happens to compose next, minutes
    // later, is worse than saying nothing. DROP_OLDEST with a buffer of one
    // means emit never suspends and nothing is kept for a listener who
    // wasn't there.
    private val _events = MutableSharedFlow<UploadEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<UploadEvent> = _events.asSharedFlow()

    /**
     * A finished job's `WorkInfo` keeps being replayed to every new observer
     * until it's pruned, so without remembering which one has already been
     * acted on, reopening the app would re-navigate (or re-show an error) for
     * a job dealt with long ago.
     *
     * A plain `var` among a class of flows, which is deliberate and rests on
     * an invariant worth stating: it is confined to the main thread. It is
     * read and written only inside [onceFor] -- which runs in the `WorkInfo`
     * collector -- and reset in [submitJob], and `viewModelScope` dispatches
     * on `Main.immediate`, so the check and the claim can't interleave.
     * Moving either off the main dispatcher would turn this into a race.
     *
     * Not persisted, also deliberately. A process death between [onceFor]
     * calling its block and `pruneWork()` clearing the record means a restart
     * sees the same SUCCEEDED job and navigates to its results again -- which
     * is the right outcome rather than a leak, since those results are exactly
     * what the user was last doing.
     */
    private var handledWorkId: UUID? = null

    init {
        collectJobHistory(observeJobHistory)
        observeMatchingJob()
    }

    /**
     * The grid's contents, now a stream off the database rather than a value
     * this class re-read by hand.
     *
     * It used to be a one-shot read with five `refreshHistory()` call sites --
     * startup, job completion, delete, batch save, and screen resume -- each
     * of which had to remember to fire. The sixth was always going to be the
     * one somebody forgot, and the symptom would have been a grid quietly
     * disagreeing with the database. All five are now gone; a write updates
     * the grid because it was a write.
     */
    private fun collectJobHistory(observeJobHistory: ObserveJobHistoryUseCase) {
        viewModelScope.launch {
            observeJobHistory()
                // Retried before giving up, because a throw *terminates* a
                // flow. The one-shot read this replaced got a fresh attempt
                // every time something called it, so without this the stream
                // would be the less robust of the two: one transient failure
                // and the grid stops updating for as long as the ViewModel
                // lives, silently.
                .retry(HISTORY_RETRIES) { e ->
                    Log.w(TAG, "Couldn't read the job history, retrying", e)
                    delay(HISTORY_RETRY_DELAY_MS)
                    true
                }
                // Past that, the grid keeps its last good value rather than
                // emptying -- the same policy as before, for the same reason:
                // "the database couldn't be read" and "you have no jobs" look
                // identical on screen, and only one of them is true.
                .catch { e -> Log.w(TAG, "Gave up observing the job history", e) }
                .collect { history -> _state.update { it.copy(history = history) } }
        }
    }

    fun onSelfiePicked(uri: Uri?) {
        // The pick and the error it answers clear together, in one update --
        // two updates would emit an intermediate state holding the new selfie
        // beside the old error, which is the kind of combination this whole
        // state object exists to make unrepresentable.
        _state.update { it.withoutStaleError().copy(selfieUri = uri) }
    }

    fun onZipPicked(context: Context, uri: Uri?) {
        // The whole archive replaced in one write, metadata included, so there
        // is no instant where this uri is paired with the last pick's name.
        _state.update {
            it.withoutStaleError().copy(archive = uri?.let { picked -> PickedArchive(picked) })
        }
        if (uri == null) return
        viewModelScope.launch {
            val (name, size) = withContext(Dispatchers.IO) { queryZipInfo(context, uri) }
            // Matched on the uri before applying: the query is asynchronous,
            // and a user who picks a second archive while the first one's
            // query is still running would otherwise have the first one's
            // filename land on the second one's pick.
            _state.update { current ->
                if (current.archive?.uri != uri) current
                else current.copy(archive = current.archive.copy(name = name, sizeBytes = size))
            }
        }
    }

    private fun queryZipInfo(context: Context, uri: Uri): Pair<String?, Long?> {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null to null
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = nameIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }
            val size = sizeIdx.takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) }
            name to size
        } ?: (null to null)
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
                        setMatching(
                            MatchingState.Processing(
                                scored = info.progress.getInt(MatchPhotosWorker.KEY_SCORED, -1).takeIf { it >= 0 },
                                total = info.progress.getInt(MatchPhotosWorker.KEY_TOTAL, -1).takeIf { it >= 0 },
                            )
                        )
                    }

                    WorkInfo.State.SUCCEEDED -> onceFor(info.id) {
                        val matchCount = info.outputData.getInt(MatchPhotosWorker.KEY_MATCH_COUNT, 0)
                        // That job is over, so the picks that produced it are
                        // no longer a setup for anything. The grid needs no
                        // nudge -- the worker's own writes drive it now.
                        if (matchCount == 0) {
                            _state.update {
                                it.withoutPicks().copy(
                                    matching = MatchingState.Error("No matches found. Try a clearer selfie."),
                                )
                            }
                        } else {
                            _state.update { it.withoutPicks().copy(matching = MatchingState.Success) }
                            _navigateToResults.send(Unit)
                        }
                    }

                    // The selfie goes, the archive stays. Clearing both
                    // would make the user re-pick a 500MB file to change the
                    // one thing the error is usually about; clearing neither
                    // left both buttons reading "Selected" under a message
                    // telling them to choose a different selfie, with no sign
                    // of which file to change. Dropping just the selfie says
                    // in the UI what the message says in words.
                    WorkInfo.State.FAILED -> onceFor(info.id) {
                        _state.update {
                            it.copy(
                                selfieUri = null,
                                matching = MatchingState.Error(
                                    info.outputData.getString(MatchPhotosWorker.KEY_ERROR)
                                        ?: "An unknown error occurred."
                                ),
                            )
                        }
                    }

                    // The screen's own Cancel already did both of these.
                    // Repeated here because this branch is what runs when the
                    // cancel came from somewhere else -- a notification
                    // action, a replacing job, a cancel while the app was
                    // closed -- and clearing only the state left the step
                    // buttons still reading "Selected" for a job that no
                    // longer exists.
                    WorkInfo.State.CANCELLED -> onceFor(info.id) {
                        _state.update { it.withoutPicks().copy(matching = MatchingState.Idle) }
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
        val selfieUri = _state.value.selfieUri ?: return
        val zipUri = _state.value.archive?.uri ?: return
        setMatching(MatchingState.Processing())

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
                setMatching(MatchingState.Error("Failed to read the selected files."))
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
        _state.update { it.withoutPicks().copy(matching = MatchingState.Idle) }
        workManager.cancelUniqueWork(MatchPhotosWorker.WORK_NAME)
    }

    private fun setMatching(matching: MatchingState) {
        _state.update { it.copy(matching = matching) }
    }

    /**
     * Called when the Upload screen comes back to the foreground, to forget
     * the outcome of a job the user has already moved on from.
     *
     * It used to refresh the job history too, which is what it mostly existed
     * for; the history is a stream now and keeps itself current. And it used
     * to be driven from the *other* side: a `DisposableEffect` on the Results
     * destination ran it from `onDispose`, on the reasoning that disposal is
     * the one thing every way of leaving that screen has in common. It is,
     * but it is also common to things that aren't leaving at all -- a rotation
     * disposes the composable, and so does navigating deeper into the photo
     * viewer.
     */
    fun onScreenResumed() {
        // Processing is left alone, and the guard matters precisely because
        // this moved to resume. WorkManager's flow is the source of truth for
        // a running job, and it doesn't re-emit just because something else
        // overwrote the state it produced -- so blanking a Processing state
        // here would replace a running job's progress screen with the form,
        // every time the user came back to the app, until whenever the next
        // progress update happened to land. The old placement never saw a
        // running job, because it only fired as Results was disposed.
        _state.update {
            if (it.matching is MatchingState.Processing) it else it.copy(matching = MatchingState.Idle)
        }
    }

    // --- Selecting jobs ---

    fun toggleJobSelected(jobId: Long) {
        _state.update {
            it.copy(selectedJobs = if (jobId in it.selectedJobs) it.selectedJobs - jobId else it.selectedJobs + jobId)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedJobs = emptySet()) }
    }

    /**
     * Deletes the selected jobs and the photos this app was storing for them.
     * The user's gallery is untouched -- see [DeleteJobUseCase].
     */
    fun deleteSelectedJobs() = deleteJobs(_state.value.selectedJobs)

    private fun deleteJobs(jobIds: Set<Long>) {
        if (jobIds.isEmpty() || _state.value.operationInFlight) return
        // Determinate from the first frame, unlike the download below: the
        // number of jobs to delete is the size of the selection, known before
        // any work starts.
        _state.update {
            it.copy(
                operationInFlight = true,
                batchProgress = JobBatchProgress(JobBatchProgress.Action.Deleting, 0, jobIds.size),
            )
        }
        viewModelScope.launch {
            var failedCount = 0
            try {
                jobIds.forEachIndexed { index, jobId ->
                    // Caught per job, not around the loop: one undeletable row
                    // shouldn't strand every other job the user selected. The
                    // same rule the batch save follows.
                    try {
                        deleteJob.invoke(jobId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failedCount++
                        Log.w(TAG, "Couldn't delete job $jobId", e)
                    }
                    // Counted after the attempt whether or not it succeeded:
                    // the bar measures how far through the selection we are,
                    // not how much of it worked. What failed is reported
                    // separately, below.
                    _state.update {
                        it.copy(
                            batchProgress = JobBatchProgress(
                                JobBatchProgress.Action.Deleting, index + 1, jobIds.size,
                            ),
                        )
                    }
                }
            } finally {
                // finally, so a failure part-way can't leave the screen
                // permanently unable to delete or download anything.
                _state.update {
                    it.copy(operationInFlight = false, selectedJobs = emptySet(), batchProgress = null)
                }
            }
            // No refresh here any more: deleting the rows is what updates the
            // grid, because the grid observes them. This used to be a manual
            // re-read sitting after a try/finally that didn't catch, so a
            // throw skipped it and left the deleted jobs on screen.
            if (failedCount > 0) _events.emit(UploadEvent.JobsDeleteFailed(failedCount))
        }
    }

    /**
     * Saves every matched photo of every selected job to the gallery.
     *
     * Deliberately not filtered to photos that aren't saved yet: saving is
     * idempotent, so "download these jobs" does exactly what it says, and
     * anything already there is counted as skipped rather than written twice.
     */
    fun downloadSelectedJobs() {
        val jobIds = _state.value.selectedJobs
        if (jobIds.isEmpty() || _state.value.operationInFlight) return
        // Set before the reads below, with no counts yet, so the bar appears
        // the moment the user taps rather than once the first photo lands.
        // Those reads open every selected job, which on a large selection is
        // itself long enough to need saying something.
        _state.update {
            it.copy(
                operationInFlight = true,
                batchProgress = JobBatchProgress(JobBatchProgress.Action.Saving),
            )
        }
        viewModelScope.launch {
            // Read in the grid's own order, so a progress count moves through
            // the jobs in the order the user sees them.
            val matches = _state.value.history
                .filter { it.id in jobIds }
                .flatMap { getJob(it.id)?.matches.orEmpty() }

            val outcome = try {
                saveMatchedPhotos(matches) { event ->
                    if (event is PhotoSaveEvent.Progress) {
                        _state.update {
                            it.copy(
                                batchProgress = JobBatchProgress(
                                    JobBatchProgress.Action.Saving, event.completed, event.total,
                                ),
                            )
                        }
                    }
                }
            } finally {
                _state.update {
                    it.copy(batchProgress = null, operationInFlight = false, selectedJobs = emptySet())
                }
            }

            // The saved ticks inside those jobs have moved, and the grid
            // picks that up on its own -- markSavedToGallery writes to
            // matched_photos, which the history stream observes.
            _events.emit(
                UploadEvent.BatchSaveCompleted(
                    savedCount = outcome.savedCount,
                    skippedCount = outcome.skippedCount,
                    failedCount = outcome.failedCount,
                    lastSavedUri = outcome.lastSavedUri,
                )
            )
        }
    }

    private companion object {
        const val TAG = "UploadViewModel"
        /** Enough to ride out a locked database, few enough to stop if it's actually broken. */
        const val HISTORY_RETRIES = 3L
        const val HISTORY_RETRY_DELAY_MS = 500L
    }
}

/** Both inputs dropped, for the paths where the job they belonged to has ended. */
private fun UploadScreenState.withoutPicks() = copy(selfieUri = null, archive = null)

/**
 * Drops an error message that the user has just acted on by picking a
 * different file.
 *
 * An error describes the attempt that produced it, and choosing a new selfie
 * or archive is the user answering it -- so it stops being true at that
 * moment. It used to survive: "No matches found. Try a clearer selfie."
 * stayed on screen while they picked the new selfie and sat directly above an
 * enabled Find My Photos, clearing only when the next job actually started.
 *
 * Error only. Processing and Success belong to a job rather than to this
 * form, and picking a file can't invalidate either.
 */
private fun UploadScreenState.withoutStaleError() =
    if (matching is MatchingState.Error) copy(matching = MatchingState.Idle) else this
