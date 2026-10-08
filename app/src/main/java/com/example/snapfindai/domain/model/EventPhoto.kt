package com.example.snapfindai.domain.model

/**
 * One photo a job has to score, read only when its turn comes.
 *
 * This replaced a plain `List<File>`, and the reason is the whole point of
 * the change it belongs to: the job used to unpack the entire archive to
 * disk before scoring anything, which for a 500MB event folder meant writing
 * 500MB that existed only to be read once and deleted. Nothing needs all the
 * photos at once -- scoring looks at exactly one at a time -- so the list
 * describes where each photo *will come from* rather than where it has
 * already been put.
 *
 * [name] is the stable identity. It orders the list, and the scored cursor
 * is a count into that order, so it must be the same on every attempt at the
 * same job.
 */
interface EventPhoto {
    val name: String

    /**
     * The photo's bytes. Blocking work, called once per photo when it is
     * scored -- and again for the few that match, since those are the only
     * ones written anywhere.
     */
    fun readBytes(): ByteArray
}

/** A photo that scored close enough to the selfie to keep, and how close. */
data class PhotoMatch(val photo: EventPhoto, val distance: Float)
