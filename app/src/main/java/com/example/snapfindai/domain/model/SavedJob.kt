package com.example.snapfindai.domain.model

/** The most recently completed job, as persisted across app restarts. */
data class SavedJob(val timestamp: Long, val matches: List<FaceMatchResult>)
