package com.example.snapfindai.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A match found by a job that hasn't finished yet.
 *
 * Separate from [MatchedPhotoEntity] because the two mean different things. A
 * matched photo is a *result*: it lives in stable storage, it has a gallery
 * name derived from its contents, and the user can see it. One of these is a
 * note to a future attempt -- "this photo scored a match, don't score it
 * again" -- pointing at a file in the job's extraction directory that will be
 * deleted once the job ends either way.
 *
 * Folding them into one table was the alternative, and it would have meant a
 * nullable `galleryName` on a result that is supposed to always have one, plus
 * rows in the results table for jobs with no results. The cost of a second
 * table is one entity; the cost of the merge is an invariant.
 *
 * Rows are cleared in the same transaction that writes the real results, and
 * CASCADE takes them when a job is abandoned or deleted -- so nothing is left
 * pointing at an extraction directory that no longer exists.
 */
@Entity(
    tableName = "pending_matches",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("jobId"),
        // The checkpoint deliberately records a match *before* advancing the
        // scored cursor, so a process killed between the two re-scores that
        // one photo and writes it again. This index is what makes that
        // re-write a no-op instead of a duplicate.
        Index(value = ["jobId", "photoPath"], unique = true),
    ],
)
data class PendingMatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    /** Absolute path inside the job's extraction directory -- stable across attempts, because the directory is named by the work request. */
    val photoPath: String,
    val distance: Float,
)
