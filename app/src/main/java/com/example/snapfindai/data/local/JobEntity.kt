package com.example.snapfindai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One matching job. The row is written when the job *starts*, not when it
 * finishes, which is what makes an interrupted job detectable afterwards --
 * see [JobStatus].
 *
 * [timestamp] is therefore the moment the job began, not the moment it
 * finished.
 */
@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val threshold: Float,
    val status: String = JobStatus.Complete.name,
    /**
     * The background work request carrying this job, so the two lifecycles
     * are tied together.
     *
     * Without it, the row was created per worker *run* rather than per work
     * *request* -- so every time the OS killed the process and the work was
     * rescheduled, a second row appeared for the same job and the first was
     * orphaned with nothing left to resume it. One id per request means a
     * restart reuses the same row, which is what resuming a job actually
     * means. It's also the only reliable way to tell an interrupted job
     * (request still live, something will resume it) from a dead one
     * (request finished or gone, nothing ever will).
     *
     * Null only in the window between a job being recorded and its request
     * being built, which reads correctly as "nothing is coming for this".
     */
    val workId: String? = null,
    /**
     * How many of this job's event photos have been scored, so a restarted
     * attempt can skip them.
     *
     * Meaningful only because the photo list is built in a deterministic
     * order (see `FindFacesInPhotosUseCase`) -- a count into a list whose
     * order varies between attempts points at a different photo each time,
     * which is worse than not resuming at all.
     *
     * Advanced *after* any match from that photo is recorded, so the pair is
     * only ever out of step in the safe direction: a photo re-scored, never a
     * match lost.
     */
    val scoredCount: Int = 0,
)
