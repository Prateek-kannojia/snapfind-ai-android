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
)
