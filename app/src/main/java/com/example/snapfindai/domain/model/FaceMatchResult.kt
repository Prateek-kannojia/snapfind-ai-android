package com.example.snapfindai.domain.model

import java.io.File

/**
 * One event photo that matched the selfie, decided entirely on-device.
 * A plain domain type (not a backend DTO) -- see JobRepository for the
 * server-based shape this deliberately does not reuse.
 *
 * [savedAt] is non-null once the user has saved this photo to their
 * device gallery -- persisted so the "already saved" state survives
 * leaving and returning to the Results screen, not just in-memory.
 */
data class FaceMatchResult(val photo: File, val distance: Float, val savedAt: Long? = null)
