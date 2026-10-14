package com.quantumweavers.snapfindai.presentation.screens.upload

import android.net.Uri
import com.quantumweavers.snapfindai.domain.model.JobSummary

/**
 * Where the matching job stands.
 *
 * The *job*, not the screen. It is one field of [UploadScreenState], which is
 * everything the screen draws -- a distinction the old name `UploadUiState`
 * blurred, since it described only this part while sitting beside eight other
 * flows that described the rest.
 */
sealed interface MatchingState {
    object Idle : MatchingState

    /** [scored]/[total] are null until the first photo finishes scoring (unzip + selfie embedding happen first, with no per-photo count yet) -- an indeterminate progress ring until then, not a fabricated number. */
    data class Processing(val scored: Int? = null, val total: Int? = null) : MatchingState

    /**
     * Carries no matches: the job now runs in a worker that outlives this
     * screen, and the results are read from the database by whoever displays
     * them. This state exists only as the moment navigation is triggered from
     * -- see UploadViewModel.navigateToResults.
     */
    object Success : MatchingState

    data class Error(val message: String) : MatchingState
}

/**
 * An archive the user has picked, and what has been learned about it so far.
 *
 * One value rather than three parallel flows, which is the whole point. The
 * uri, filename and size used to be separate `StateFlow`s written in
 * sequence, so a recomposition landing between two of those writes could
 * render a *new* archive's uri beside the *previous* one's filename. The type
 * can no longer express that combination.
 *
 * [name] and [sizeBytes] stay nullable, because they come from a
 * content-resolver query that completes a moment after the pick and the step
 * row has to acknowledge the tap before then. The difference is that they are
 * now nullable *together*, as one replacement of one value.
 */
data class PickedArchive(
    val uri: Uri,
    val name: String? = null,
    val sizeBytes: Long? = null,
)

/**
 * A batch running over the selected jobs, and how far through it is.
 *
 * [action] rather than one shared "in progress" shape, because the two
 * batches count different things: a download walks the *photos* inside every
 * selected job, while a delete walks the jobs themselves. A label that said
 * "3 of 12" without saying of what would be wrong half the time.
 *
 * [completed] and [total] are nullable for the same reason
 * [MatchingState.Processing]'s are: a download has to read every selected
 * job's matches out of the database before it knows how many photos it is
 * about to write, and the honest thing to show in that window is an
 * indeterminate bar rather than a fabricated number. A delete knows its total
 * from the start, so it never has nulls.
 */
data class JobBatchProgress(
    val action: Action,
    val completed: Int? = null,
    val total: Int? = null,
) {
    enum class Action { Saving, Deleting }

    /** Null while the total isn't known yet -- which the bar draws as indeterminate. */
    val fraction: Float?
        get() = if (completed != null && total != null && total > 0) completed / total.toFloat() else null
}

/**
 * Everything the upload screen draws, as one value.
 *
 * This was nine separate `StateFlow`s on the ViewModel, collected
 * independently by the composable. Two things were wrong with that, and only
 * the first is the obvious one: nine collectors are nine recomposition
 * triggers for one screen. The one that actually produced a visible bug is
 * that independent flows make inconsistent *combinations* representable --
 * see [PickedArchive].
 *
 * `ResultsUiState.Loaded` already worked this way, including deriving
 * [selectionMode] rather than storing a flag that could contradict the set it
 * describes. This brings the other screen into line with it.
 */
data class UploadScreenState(
    val matching: MatchingState = MatchingState.Idle,
    val selfieUri: Uri? = null,
    val archive: PickedArchive? = null,
    /**
     * Every past job, newest first, shown as grid cards. Opening one
     * navigates straight to its Results, bypassing [matching] entirely (see
     * MainActivity's onOpenJob wiring).
     */
    val history: List<JobSummary> = emptyList(),
    val selectedJobs: Set<Long> = emptySet(),
    /**
     * True while a batch download or delete is running, which disables both.
     * They touch the same files -- deleting a job removes the very photos
     * another batch may be mid-copy into the gallery -- so the dangerous
     * combination isn't two downloads, it's a delete landing during one.
     */
    val operationInFlight: Boolean = false,
    /**
     * The batch currently running over the selected jobs, null when none is.
     *
     * This was a `Pair<Int, Int>?` that the ViewModel maintained and nothing
     * drew: downloading or deleting several jobs disabled the app bar's
     * actions and the grid, and then said nothing at all for however long the
     * work took -- which on a few hundred full-resolution photos is long
     * enough to look like the app had simply stopped responding. Deleting had
     * no progress state to render in the first place.
     */
    val batchProgress: JobBatchProgress? = null,
) {
    /** Derived, not stored: an empty selection *is* "not selecting", so the two cannot disagree. */
    val selectionMode: Boolean get() = selectedJobs.isNotEmpty()

    /**
     * How many photos the selected jobs hold between them -- what a Save
     * would actually write to the gallery.
     *
     * Distinct from `selectedJobs.size`, which is what the app bar's title
     * counts, because the user selects *jobs* and the action works on the
     * *photos* inside them. The bar says both: "3 selected" over "Save 17".
     */
    val selectedPhotoCount: Int
        get() = history.filter { it.id in selectedJobs }.sumOf { it.matchCount }

    /** Both inputs are present, so the job can actually be started. */
    val armed: Boolean get() = selfieUri != null && archive != null
}

/**
 * One-shot events the Upload screen reacts to once (snackbar, notification),
 * kept out of [UploadScreenState] so they don't re-fire on recomposition.
 *
 * The same shape as the results screen's own event, because it reports the
 * same batch: see `SaveMatchedPhotosUseCase`, which both screens run.
 */
sealed interface UploadEvent {
    data class BatchSaveCompleted(
        val savedCount: Int,
        val skippedCount: Int,
        val failedCount: Int,
        val lastSavedUri: android.net.Uri?,
    ) : UploadEvent

    /**
     * [count] of the selected jobs couldn't be deleted.
     *
     * A delete that fails part-way used to say nothing at all: the selection
     * cleared, the grid refreshed, and the jobs that survived were simply
     * still there, which reads as the app having ignored the tap. The jobs
     * left on screen are the accurate outcome -- this is the sentence that
     * explains why they are.
     */
    data class JobsDeleteFailed(val count: Int) : UploadEvent
}
