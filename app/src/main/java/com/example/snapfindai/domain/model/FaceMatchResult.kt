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
 *
 * [galleryName] is the filename this photo takes in the user's gallery,
 * derived from its *contents* when the job was persisted. Content-addressed
 * rather than path-derived on purpose: a path is not a stable identity, since
 * clearing app data restarts job ids and makes paths repeat -- a different
 * photo would then resolve to a name already in the gallery and be silently
 * reported as already saved.
 *
 * Null only before persistence, on a match the scorer has just produced: the
 * name cannot exist until the photo has been relocated and read. Anything
 * loaded from storage always has one.
 */
data class FaceMatchResult(
    val photo: File,
    val distance: Float,
    val savedAt: Long? = null,
    val galleryName: String? = null,
)
