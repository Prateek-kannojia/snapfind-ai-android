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
/**
 * A selfie that has already been read and had its face worked out, ready to
 * be matched against. Deliberately opaque: the caller takes one from
 * [FaceMatchRepository.prepareSelfie] and hands it to
 * [FaceMatchRepository.matchPhotos] without ever looking inside, so what it
 * holds stays the implementation's business.
 */
interface PreparedSelfie

interface FaceMatchRepository {
    /**
     * Reads [selfie] and works out the face in it, failing if it can't be
     * decoded (IOException) or has no usable face (NoFaceDetectedException).
     *
     * Split from [matchPhotos] for two reasons. Failure gets cheap: unzipping
     * an event folder takes minutes, and finding out afterwards that the
     * selfie was unusable wastes all of it. And the result is reusable, so
     * the work happens exactly once per job -- the whole point of handing
     * back a [PreparedSelfie] instead of just throwing or not throwing.
     */
    suspend fun prepareSelfie(selfie: File): PreparedSelfie

    /**
     * Takes the [PreparedSelfie] from [prepareSelfie] rather than a file, so
     * the selfie cannot be re-read per job and this cannot be called without
     * the check above having passed -- there's no way to obtain the argument
     * otherwise.
     *
     * [onProgress], if given, is called after each event photo is scored --
     * (photos scored so far, total). Best-effort UI feedback, not a contract
     * every implementation must honor precisely.
     */
    suspend fun matchPhotos(
        selfie: PreparedSelfie,
        eventPhotos: List<File>,
        threshold: Float,
        onProgress: ((scored: Int, total: Int) -> Unit)? = null,
    ): List<FaceMatchResult>
}
