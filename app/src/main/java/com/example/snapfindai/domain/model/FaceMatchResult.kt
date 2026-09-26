package com.example.snapfindai.domain.model

import java.io.File

/**
 * One event photo that matched the selfie, decided entirely on-device.
 * A plain domain type (not a backend DTO) -- see JobRepository for the
 * server-based shape this deliberately does not reuse.
 */
data class FaceMatchResult(val photo: File, val distance: Float)
