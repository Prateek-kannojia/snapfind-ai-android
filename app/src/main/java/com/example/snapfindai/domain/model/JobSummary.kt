package com.example.snapfindai.domain.model

import java.io.File

/** Lightweight per-job info for a history grid card -- deliberately not [SavedJob]'s full match list, so listing past jobs doesn't mean loading every match of every job. */
data class JobSummary(
    val id: Long,
    val timestamp: Long,
    val matchCount: Int,
    val previewPhoto: File?,
    /**
     * Bytes this job's stored photos occupy. Surfaced because the cost was
     * otherwise invisible: a job keeps a full-resolution copy of every match
     * forever, and without a number on screen nobody has any reason to delete
     * one. The first place a user would have noticed is the system settings
     * screen, as a total with no explanation.
     */
    val sizeBytes: Long,
)
