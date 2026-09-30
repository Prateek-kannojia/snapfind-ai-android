package com.example.snapfindai.domain.model

/** One completed matching job, as persisted across app restarts -- past jobs are kept, not just the latest. */
data class SavedJob(val id: Long, val timestamp: Long, val matches: List<FaceMatchResult>)
