package com.example.snapfindai.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.repository.FaceMatchRepository
import com.example.snapfindai.facesdk.BitmapDecoder
import com.example.snapfindai.facesdk.FaceMatchEngine
import com.example.snapfindai.facesdk.FaceMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs matching entirely on-device via facesdk. Event photos are decoded
 * and scored ONE AT A TIME, not batch-decoded into a list first: a job's
 * photos can be dozens of full-resolution phone photos, and holding them
 * all as decoded Bitmaps simultaneously risks OOM on a real device. This
 * is exactly the case facesdk's lower-level embedSelfie()/scoreEventPhoto()
 * primitives exist for, instead of the matchJob() convenience batch call.
 */
@Singleton
class OnDeviceFaceMatchRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : FaceMatchRepository {

    private val engineMutex = Mutex()
    @Volatile private var engine: FaceMatchEngine? = null

    private suspend fun engine(): FaceMatchEngine =
        engine ?: engineMutex.withLock {
            engine ?: FaceMatchEngine.create(context).also { engine = it }
        }

    override suspend fun matchPhotos(
        selfie: File,
        eventPhotos: List<File>,
        threshold: Float,
    ): List<FaceMatchResult> {
        val engine = engine()
        val selfieBitmap = BitmapDecoder.decodeWithExifCorrection(selfie)
        val selfieEmbedding = try {
            engine.embedSelfie(selfieBitmap)
        } finally {
            selfieBitmap.recycle()
        }

        val matches = mutableListOf<FaceMatchResult>()
        for (photo in eventPhotos) {
            val bitmap: Bitmap = try {
                BitmapDecoder.decodeWithExifCorrection(photo)
            } catch (e: Exception) {
                continue // corrupt/unreadable file -> skip, same as "no face found"
            }
            val distance = try {
                engine.scoreEventPhoto(selfieEmbedding, bitmap)
            } catch (e: Exception) {
                null
            } finally {
                bitmap.recycle()
            }
            if (distance != null && FaceMatcher.isMatch(distance, threshold)) {
                matches += FaceMatchResult(photo = photo, distance = distance)
            }
        }
        return matches
    }
}
