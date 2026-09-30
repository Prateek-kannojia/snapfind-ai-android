package com.example.snapfindai.domain.model

import java.io.File

/** Lightweight per-job info for a history grid card -- deliberately not [SavedJob]'s full match list, so listing past jobs doesn't mean loading every match of every job. */
data class JobSummary(
    val id: Long,
    val timestamp: Long,
    val matchCount: Int,
    val previewPhoto: File?,
)
