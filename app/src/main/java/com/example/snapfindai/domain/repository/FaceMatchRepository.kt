package com.example.snapfindai.domain.repository

import com.example.snapfindai.domain.model.FaceMatchResult
import java.io.File

/**
 * Given a selfie and a set of event photos, decide which ones match.
 * Deliberately named like JobRepository rather than tied to "on-device" --
 * the interface just says "get me matches"; how that's decided (on-device
 * today, possibly a server-backed implementation later) is the
 * implementation's business, not the caller's. See the standing
 * server-vs-on-device routing question this sets up for, without forcing
 * that decision now.
 */
interface FaceMatchRepository {
    suspend fun matchPhotos(selfie: File, eventPhotos: List<File>, threshold: Float): List<FaceMatchResult>
}
