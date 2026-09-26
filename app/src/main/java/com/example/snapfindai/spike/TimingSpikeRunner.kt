package com.example.snapfindai.spike

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.snapfindai.facesdk.BitmapDecoder
import com.example.snapfindai.facesdk.FaceAligner
import com.example.snapfindai.facesdk.FaceMatchEngine
import com.example.snapfindai.facesdk.model.EventPhotoInput
import java.io.File

/**
 * Validation harness for the facesdk module: exercises it against real
 * photos and exports CSVs that get pulled off the device and compared
 * against the server's own detect/align/embed/match output for the same
 * images — the actual correctness check for the on-device SDK, not just a
 * code review. See Face_recognition/benchmarks/README.md for the on-device
 * contract these CSVs satisfy.
 */
class TimingSpikeRunner(private val context: Context) {

    companion object {
        private const val TAG = "TimingSpike"
    }

    private lateinit var engine: FaceMatchEngine

    suspend fun loadModels() {
        engine = FaceMatchEngine.create(context)
    }

    fun close() = engine.close()

    /** Push real photos here before running: no runtime permission needed, app-private.
     *  adb push path-to-photos "$(adb shell 'echo $EXTERNAL_STORAGE')"/Android/data/com.example.snapfindai/files/spike_photos/
     */
    fun photosDir(): File = File(context.getExternalFilesDir(null), "spike_photos").apply { mkdirs() }

    /**
     * Real job folders are named by UUID; synthetic ones are "jobNN_Name".
     * The known failures are all real photos and the synthetic set already
     * validated near-perfectly (IoU ~0.98, 162/173), so debugging loops only
     * need the real subset — much faster to iterate.
     */
    private fun isRealJobPhoto(relativePath: String): Boolean {
        val topFolder = relativePath.substringBefore('/')
        return !topFolder.startsWith("job")
    }

    private fun allPhotos(realOnly: Boolean): List<File> = photosDir().walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
        .map { it.relativeTo(photosDir()).path to it }
        .filter { (rel, _) -> !realOnly || isRealJobPhoto(rel.removePrefix("sample_test_data/")) }
        .sortedBy { it.first }
        .map { it.second }
        .toList()

    /**
     * Phase A check: real SCRFD detection through facesdk's FaceDetector.
     * Writes one CSV row per detected face so it can be compared photo-for-
     * photo against the real Python/insightface detector on the same images.
     */
    suspend fun runDetectionValidation(
        realPhotosOnly: Boolean = true,
        onProgress: (done: Int, total: Int, current: String) -> Unit,
    ): File {
        val photos = allPhotos(realPhotosOnly)
        val out = File(context.getExternalFilesDir(null), "detection_validation.csv")
        val diag = File(context.getExternalFilesDir(null), "detection_diag.csv")
        out.bufferedWriter().use { w ->
            diag.bufferedWriter().use { d ->
                w.write("file,x1,y1,x2,y2,score,kp0x,kp0y,kp1x,kp1y,kp2x,kp2y,kp3x,kp3y,kp4x,kp4y\n")
                d.write("file,decoded_w,decoded_h,num_faces_found\n")
                photos.forEachIndexed { index, file ->
                    val label = file.relativeTo(photosDir()).path
                    try {
                        val bitmap = BitmapDecoder.decodeWithExifCorrection(file)
                        val faces = engine.detector.detect(bitmap)
                        for (f in faces) {
                            val kp = f.landmarks.points.joinToString(",") { "${it.x},${it.y}" }
                            w.write("$label,${f.box.left},${f.box.top},${f.box.right},${f.box.bottom},${f.score},$kp\n")
                        }
                        d.write("$label,${bitmap.width},${bitmap.height},${faces.size}\n")
                        bitmap.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "Skipping $label: ${e.message}", e)
                    }
                    onProgress(index + 1, photos.size, label)
                }
            }
        }
        return out
    }

    /**
     * Phase B check: does alignment actually produce the right crop, not
     * just detect the right box? For every detected real face: align it
     * (FaceAligner) and embed the crop (FaceEmbedder), and export the
     * embedding. Compared against the server's own detect+align+embed
     * output for the same photos — if alignment is correct, the two
     * embeddings for the same face should land close together.
     */
    suspend fun runAlignmentValidation(onProgress: (done: Int, total: Int, current: String) -> Unit): File {
        val photos = allPhotos(realOnly = true)
        val out = File(context.getExternalFilesDir(null), "alignment_validation.csv")
        out.bufferedWriter().use { w ->
            w.write("file,face_index,score," + (0 until 512).joinToString(",") { "e$it" } + "\n")
            photos.forEachIndexed { index, file ->
                val label = file.relativeTo(photosDir()).path
                try {
                    val bitmap = BitmapDecoder.decodeWithExifCorrection(file)
                    val faces = engine.detector.detect(bitmap)
                    for ((faceIdx, face) in faces.withIndex()) {
                        val aligned = FaceAligner.align(bitmap, face.landmarks)
                        val embedding = engine.embedder.embed(aligned)
                        aligned.recycle()
                        w.write("$label,$faceIdx,${face.score},${embedding.values.joinToString(",")}\n")
                    }
                    bitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Skipping $label: ${e.message}", e)
                }
                onProgress(index + 1, photos.size, label)
            }
        }
        return out
    }

    /**
     * Phase C check: the actual end-to-end match decision, through
     * facesdk's FaceMatchEngine facade — not the primitives called by hand.
     * Exports job,file,d — the exact contract _common.py's
     * load_device_distances()/records_from_distances() already read, so
     * this scores through the identical path as every other number in this
     * project, no new scoring code needed.
     */
    suspend fun runJobMatching(onProgress: (done: Int, total: Int, current: String) -> Unit): File {
        val realJobDirs = File(photosDir(), "sample_test_data")
            .listFiles { f -> f.isDirectory && !f.name.startsWith("job") }
            ?.sortedBy { it.name } ?: emptyList()

        val out = File(context.getExternalFilesDir(null), "job_matching.csv")
        out.bufferedWriter().use { w ->
            w.write("job,file,d\n")
            realJobDirs.forEachIndexed { index, jobDir ->
                // UUID-style folder (the 3 original real jobs) -> match _common.py's
                // job_id[:8] convention exactly. Anything else (e.g. gokarnaNN) ->
                // use the full name; an 8-char truncation would collide distinct
                // jobs together (gokarna01..09 all becoming "gokarna0", etc).
                val isUuidStyle = jobDir.name.length >= 8 && jobDir.name[8] == '-'
                val jobName = "real_" + if (isUuidStyle) jobDir.name.take(8) else jobDir.name
                var selfieBitmap: Bitmap? = null
                val eventBitmaps = mutableListOf<Bitmap>()
                try {
                    val selfieFile = File(jobDir, "selfie")
                        .listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                        ?.firstOrNull() ?: throw IllegalStateException("no selfie file")
                    selfieBitmap = BitmapDecoder.decodeWithExifCorrection(selfieFile)

                    val eventPhotos = File(jobDir, "event_photos")
                        .listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                        ?.sortedBy { it.name } ?: emptyList()
                    val inputs = mutableListOf<EventPhotoInput>()
                    val undecodable = mutableListOf<String>()
                    for (photo in eventPhotos) {
                        try {
                            val bitmap = BitmapDecoder.decodeWithExifCorrection(photo)
                            eventBitmaps += bitmap
                            inputs += EventPhotoInput(id = photo.name, bitmap = bitmap)
                        } catch (e: Exception) {
                            Log.e(TAG, "Skipping ${photo.name}: ${e.message}", e)
                            undecodable += photo.name
                        }
                    }

                    val results = engine.matchJob(selfieBitmap, inputs)
                    for (result in results) {
                        w.write("$jobName,${result.id},${result.distance ?: ""}\n")
                    }
                    for (name in undecodable) {
                        w.write("$jobName,$name,\n")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Skipping job ${jobDir.name}: ${e.message}", e)
                } finally {
                    selfieBitmap?.recycle()
                    eventBitmaps.forEach { it.recycle() }
                }
                onProgress(index + 1, realJobDirs.size, jobName)
            }
        }
        return out
    }
}
