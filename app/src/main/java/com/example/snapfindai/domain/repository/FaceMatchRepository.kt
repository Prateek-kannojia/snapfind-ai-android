package com.example.snapfindai.domain.repository

import com.example.snapfindai.domain.model.EventPhoto
import com.example.snapfindai.domain.model.PhotoMatch
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
     * Scores exactly the [eventPhotos] it is given, in the order given, and
     * reads each one only when it reaches it. A caller resuming an
     * interrupted job passes only the photos still to do; this knows nothing
     * about resumption, and shouldn't -- where a job got to is the caller's
     * state, not the matcher's.
     *
     * Reports through [onScored] rather than returning a list, because a
     * match has to be dealt with the moment it is found: the caller writes it
     * down before the next photo is scored, so a process killed mid-job loses
     * nothing. A return value would be a second copy of the same facts,
     * available only to a run that finished -- which is the one case where
     * nothing can go wrong.
     *
     * [onScored] carries how many of *this* list have been scored and the
     * match that photo produced, or null if it didn't match. It suspends
     * because the caller's work is usually to write something durable, and
     * implementations must await it rather than launching it -- or a
     * checkpoint can be lost to the very kill it exists to survive.
     */
    suspend fun matchPhotos(
        selfie: PreparedSelfie,
        eventPhotos: List<EventPhoto>,
        threshold: Float,
        onScored: suspend (scored: Int, match: PhotoMatch?) -> Unit,
    )
}
