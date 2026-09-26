package com.example.snapfindai.spike

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Step 1 of the on-device plan (see Face_recognition/benchmarks/README.md): a throwaway
 * per-photo timing spike. Its only job is answering "is a phone CPU fast enough to run
 * det_500m + w600k_mbf per photo", before any pipeline code gets built around them.
 *
 * Scope, deliberately narrow:
 *  - Times bitmap decode, preprocessing, and the two ONNX forward passes.
 *  - Does NOT implement SCRFD's anchor decode / NMS postprocessing (scrfd.py:162-353) —
 *    that's pure CPU math over the model's raw output, not model latency, and out of
 *    scope for a latency-only spike.
 *  - Recognizer input is a center crop, not a detected+aligned face — correctness isn't
 *    being measured here, only forward-pass cost on a real 112x112 tensor.
 * Delete this file + TimingSpikeActivity + the manifest entry once step 1 is answered.
 */
data class PhotoTiming(
    val fileName: String,
    val widthPx: Int,
    val heightPx: Int,
    val decodeMs: Double,
    val detPreprocessMs: Double,
    val detInferMs: Double,
    val recPreprocessMs: Double,
    val recInferMs: Double,
    val totalMs: Double,
)

class TimingSpikeRunner(private val context: Context) {

    companion object {
        private const val TAG = "TimingSpike"
        // Matches Face_recognition/core/settings.py FACE_DETECTOR_SIZE default (800).
        private const val DET_INPUT_SIZE = 800
        private const val REC_INPUT_SIZE = 112
        private const val DET_INPUT_NAME = "input.1"
        private const val REC_INPUT_NAME = "input.1"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private lateinit var detSession: OrtSession
    private lateinit var recSession: OrtSession

    fun loadModels() {
        val opts = OrtSession.SessionOptions()
        context.assets.open("models/det_500m.onnx").use { input ->
            detSession = env.createSession(input.readBytes(), opts)
        }
        context.assets.open("models/w600k_mbf.onnx").use { input ->
            recSession = env.createSession(input.readBytes(), opts)
        }
    }

    /** Push real photos here before running: no runtime permission needed, app-private.
     *  adb push path-to-photos "$(adb shell 'echo $EXTERNAL_STORAGE')"/Android/data/com.example.snapfindai/files/spike_photos/
     */
    fun photosDir(): File = File(context.getExternalFilesDir(null), "spike_photos").apply { mkdirs() }

    fun resultsCsv(): File = File(context.getExternalFilesDir(null), "spike_results.csv")

    fun run(onProgress: (done: Int, total: Int, current: String) -> Unit): List<PhotoTiming> {
        // Corpus is nested (spike_photos/sample_test_data/jobNN_Name/*.jpg), so walk recursively.
        val photos = photosDir().walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            .sortedBy { it.relativeTo(photosDir()).path }
            .toList()

        val results = mutableListOf<PhotoTiming>()
        photos.forEachIndexed { index, file ->
            val label = file.relativeTo(photosDir()).path
            try {
                results += timeOnePhoto(file).copy(fileName = label)
            } catch (e: Exception) {
                Log.e(TAG, "Skipping $label: ${e.message}", e)
            }
            onProgress(index + 1, photos.size, label)
        }
        writeCsv(results)
        return results
    }

    /**
     * Phase A of the on-device plan: real SCRFD detection (forward pass +
     * ScrfdDecoder's anchor decode/NMS), not just the timing spike's raw
     * forward pass. Writes one CSV row per detected face so it can be
     * compared photo-for-photo against the real Python/insightface detector
     * on the same images — the actual correctness check, not just latency.
     */
    /**
     * Real job folders are named by UUID; synthetic ones are "jobNN_Name".
     * The known failures are all real photos and the synthetic set already
     * validated near-perfectly (IoU ~0.98, 162/173), so debugging loops only
     * need the real subset — 15 files instead of 178, much faster to iterate.
     */
    private fun isRealJobPhoto(relativePath: String): Boolean {
        val topFolder = relativePath.substringBefore('/')
        return !topFolder.startsWith("job")
    }

    fun runDetectionValidation(
        realPhotosOnly: Boolean = true,
        onProgress: (done: Int, total: Int, current: String) -> Unit,
    ): File {
        val photos = photosDir().walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            .map { it.relativeTo(photosDir()).path to it }
            .filter { (rel, _) -> !realPhotosOnly || isRealJobPhoto(rel.removePrefix("sample_test_data/")) }
            .sortedBy { it.first }
            .map { it.second }
            .toList()

        val out = File(context.getExternalFilesDir(null), "detection_validation.csv")
        val diag = File(context.getExternalFilesDir(null), "detection_diag.csv")
        out.bufferedWriter().use { w ->
            diag.bufferedWriter().use { d ->
                w.write("file,x1,y1,x2,y2,score,kp0x,kp0y,kp1x,kp1y,kp2x,kp2y,kp3x,kp3y,kp4x,kp4y\n")
                // decoded_w/h: what BitmapFactory actually decoded (checks for
                // silent EXIF auto-rotation). max_raw_score: the best score
                // seen BEFORE the 0.5 det_thresh filter, so a below-threshold
                // near-miss can be told apart from a near-zero score.
                d.write("file,decoded_w,decoded_h,max_raw_score,num_faces_found\n")
                photos.forEachIndexed { index, file ->
                    val label = file.relativeTo(photosDir()).path
                    try {
                        val r = detectOnePhoto(file)
                        for (f in r.faces) {
                            val kp = f.kps.joinToString(",") { "${it[0]},${it[1]}" }
                            w.write("$label,${f.box[0]},${f.box[1]},${f.box[2]},${f.box[3]},${f.score},$kp\n")
                        }
                        d.write("$label,${r.decodedW},${r.decodedH},${r.maxRawScore},${r.faces.size}\n")
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
     * cv2.imread() (what production actually uses) auto-rotates JPEGs by
     * EXIF orientation; BitmapFactory.decodeFile() does not. Measured root
     * cause of Phase A's real-photo failures: 5 photos with orientation=6
     * decoded as 4096x1842 (raw sensor frame) on Android vs 1842x4096
     * (rotated) on the server — not a subtly different image, a completely
     * different orientation, which SCRFD was never going to handle the same
     * way. Verified empirically against cv2.imread, pixel-for-pixel (see
     * benchmarks/ scratch investigation, 2026-09-25): orientation=6 is a
     * 90-degree CLOCKWISE rotation, bit-identical to cv2's output and to
     * PIL's ImageOps.exif_transpose reference implementation. The other six
     * standard EXIF values are the same table every compliant reader uses
     * (matches Android's own ExifInterface.ORIENTATION_* semantics) —
     * implemented in full so any future test photo is handled correctly,
     * not just the 3 values this corpus happens to exercise.
     */
    private fun decodeBitmapCorrected(file: File): Bitmap {
        val raw = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalStateException("Could not decode ${file.name}")
        val orientation = try {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> return raw
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
            else -> return raw
        }
        val corrected = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (corrected !== raw) raw.recycle()
        return corrected
    }

    private class DetectionResult(
        val faces: List<DetectedFace>,
        val decodedW: Int,
        val decodedH: Int,
        val maxRawScore: Float,
    )

    /** Runs det_500m + ScrfdDecoder on an already-decoded bitmap. Does not
     * recycle it -- Phase A's caller does that immediately; Phase B's needs
     * the bitmap to stay alive afterward, to crop the aligned face from it. */
    private fun runDetectionOn(bitmap: Bitmap): Pair<List<DetectedFace>, Float> {
        val detInput = preprocessForDetector(bitmap)
        val outputs = mutableListOf<FloatArray>()
        detSession.run(mapOf(DET_INPUT_NAME to detInput.tensor)).use { result ->
            for (entry in result) {
                val tensor = entry.value as OnnxTensor
                val buf = tensor.floatBuffer
                val arr = FloatArray(buf.remaining())
                buf.get(arr)
                outputs += arr
            }
        }
        detInput.tensor.close()

        // outputs[0..2] are the three score tensors (strides 8/16/32) — see ScrfdDecoder.
        val maxRawScore = (0..2).maxOf { idx -> outputs[idx].maxOrNull() ?: 0f }
        val faces = ScrfdDecoder.decode(outputs, detInput.inputSize, detInput.inputSize, detInput.detScale)
        return faces to maxRawScore
    }

    private fun detectOnePhoto(file: File): DetectionResult {
        val bitmap = decodeBitmapCorrected(file)
        val decodedW = bitmap.width
        val decodedH = bitmap.height
        val (faces, maxRawScore) = runDetectionOn(bitmap)
        bitmap.recycle()
        return DetectionResult(faces, decodedW, decodedH, maxRawScore)
    }

    /**
     * Phase B of the on-device plan: does alignment actually produce the
     * right crop, not just detect the right box? For every detected real
     * face: align it (FaceAligner, insightface's face_align.norm_crop
     * ported), embed the 112x112 aligned crop with w600k_mbf, and export the
     * embedding. Compared against the server's own detect+align+embed
     * output for the same photos -- if alignment is correct, the two
     * embeddings for the same face should land close together (small cosine
     * distance), the same kind of check used to validate the ONNX port
     * itself in benchmarks/compare_embedders.py.
     */
    fun runAlignmentValidation(onProgress: (done: Int, total: Int, current: String) -> Unit): File {
        val photos = photosDir().walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            .map { it.relativeTo(photosDir()).path to it }
            .filter { (rel, _) -> isRealJobPhoto(rel.removePrefix("sample_test_data/")) }
            .sortedBy { it.first }
            .map { it.second }
            .toList()

        val out = File(context.getExternalFilesDir(null), "alignment_validation.csv")
        out.bufferedWriter().use { w ->
            w.write("file,face_index,score," + (0 until 512).joinToString(",") { "e$it" } + "\n")
            photos.forEachIndexed { index, file ->
                val label = file.relativeTo(photosDir()).path
                try {
                    val bitmap = decodeBitmapCorrected(file)
                    val (faces, _) = runDetectionOn(bitmap)
                    for ((faceIdx, face) in faces.withIndex()) {
                        val embedding = embedFace(bitmap, face.kps)
                        w.write("$label,$faceIdx,${face.score},${embedding.joinToString(",")}\n")
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

    /** Align one detected face against `source` and embed it with w600k_mbf. */
    private fun embedFace(source: Bitmap, kps: Array<FloatArray>): FloatArray {
        val aligned = FaceAligner.alignFace(source, kps)
        val embTensor = preprocessForRecognizer(aligned)
        var embedding = FloatArray(0)
        recSession.run(mapOf(REC_INPUT_NAME to embTensor)).use { result ->
            for (entry in result) {
                val tensor = entry.value as OnnxTensor
                val buf = tensor.floatBuffer
                embedding = FloatArray(buf.remaining())
                buf.get(embedding)
            }
        }
        embTensor.close()
        aligned.recycle()
        return embedding
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 1f
        return 1f - dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    /**
     * Phase C of the on-device plan: the actual end-to-end match decision,
     * not two separate exports joined by a script. For each real job:
     * detect the selfie, embed its largest face (same rule as the server's
     * SCRFD selfie mode -- _selfie_embedding_scrfd() in face_matcher.py),
     * then for every event photo, detect+align+embed every face and keep
     * the closest one (same rule as _embed_and_score_event_photo: "a photo
     * matches if anyone in it matches"). Exports job,file,d -- the exact
     * contract _common.py's load_device_distances()/records_from_distances()
     * already read, so this scores through the identical path as every
     * other number in this project, no new scoring code needed.
     */
    fun runJobMatching(onProgress: (done: Int, total: Int, current: String) -> Unit): File {
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
                try {
                    val selfieFile = File(jobDir, "selfie")
                        .listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                        ?.firstOrNull() ?: throw IllegalStateException("no selfie file")
                    val selfieBitmap = decodeBitmapCorrected(selfieFile)
                    val (selfieFaces, _) = runDetectionOn(selfieBitmap)
                    if (selfieFaces.isEmpty()) throw IllegalStateException("no face in selfie")
                    val largest = selfieFaces.maxByOrNull { (it.box[2] - it.box[0]) * (it.box[3] - it.box[1]) }!!
                    val selfieEmbedding = embedFace(selfieBitmap, largest.kps)
                    selfieBitmap.recycle()

                    val eventPhotos = File(jobDir, "event_photos")
                        .listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                        ?.sortedBy { it.name } ?: emptyList()
                    for (photo in eventPhotos) {
                        try {
                            val bitmap = decodeBitmapCorrected(photo)
                            val (faces, _) = runDetectionOn(bitmap)
                            val d = if (faces.isEmpty()) {
                                null
                            } else {
                                faces.minOf { face -> cosineDistance(selfieEmbedding, embedFace(bitmap, face.kps)) }
                            }
                            bitmap.recycle()
                            w.write("$jobName,${photo.name},${d ?: ""}\n")
                        } catch (e: Exception) {
                            Log.e(TAG, "Skipping ${photo.name}: ${e.message}", e)
                            w.write("$jobName,${photo.name},\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Skipping job ${jobDir.name}: ${e.message}", e)
                }
                onProgress(index + 1, realJobDirs.size, jobName)
            }
        }
        return out
    }

    private fun timeOnePhoto(file: File): PhotoTiming {
        var t0 = System.nanoTime()
        val bitmap = decodeBitmapCorrected(file)
        val decodeMs = elapsedMs(t0)

        t0 = System.nanoTime()
        val detInput = preprocessForDetector(bitmap)
        val detPreprocessMs = elapsedMs(t0)

        t0 = System.nanoTime()
        detSession.run(mapOf(DET_INPUT_NAME to detInput.tensor)).use { }
        val detInferMs = elapsedMs(t0)
        detInput.tensor.close()

        t0 = System.nanoTime()
        val recTensor = preprocessForRecognizer(bitmap)
        val recPreprocessMs = elapsedMs(t0)

        t0 = System.nanoTime()
        recSession.run(mapOf(REC_INPUT_NAME to recTensor)).use { }
        val recInferMs = elapsedMs(t0)
        recTensor.close()

        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()

        val total = decodeMs + detPreprocessMs + detInferMs + recPreprocessMs + recInferMs
        return PhotoTiming(
            fileName = file.name,
            widthPx = width,
            heightPx = height,
            decodeMs = decodeMs,
            detPreprocessMs = detPreprocessMs,
            detInferMs = detInferMs,
            recPreprocessMs = recPreprocessMs,
            recInferMs = recInferMs,
            totalMs = total,
        )
    }

    private fun elapsedMs(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    /**
     * insightface's SCRFD preprocessing (scrfd.py:162-163): letterbox into a square
     * [DET_INPUT_SIZE] canvas preserving aspect ratio, (pixel - 127.5) / 128.0,
     * BGR->RGB swap, NCHW.
     */
    /** detScale matches insightface's own det_scale (scrfd.py:286): for a
     * square input size this is just newSize/max(width,height) — the ratio
     * needed to map decoded boxes/landmarks back into original image pixels. */
    class DetectorInput(val tensor: OnnxTensor, val detScale: Float, val inputSize: Int)

    private fun preprocessForDetector(bitmap: Bitmap): DetectorInput {
        val size = DET_INPUT_SIZE
        val ratio = size.toFloat() / max(bitmap.width, bitmap.height)
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

        val pixels = IntArray(newW * newH)
        scaled.getPixels(pixels, 0, newW, 0, 0, newW, newH)

        val plane = size * size
        val padVal = (0f - 127.5f) / 128.0f
        val rArr = FloatArray(plane) { padVal }
        val gArr = FloatArray(plane) { padVal }
        val bArr = FloatArray(plane) { padVal }

        for (y in 0 until newH) {
            for (x in 0 until newW) {
                val p = pixels[y * newW + x]
                val idx = y * size + x
                rArr[idx] = (((p shr 16) and 0xFF) - 127.5f) / 128.0f
                gArr[idx] = (((p shr 8) and 0xFF) - 127.5f) / 128.0f
                bArr[idx] = ((p and 0xFF) - 127.5f) / 128.0f
            }
        }
        val buffer = FloatBuffer.allocate(3 * plane)
        buffer.put(rArr); buffer.put(gArr); buffer.put(bArr)
        buffer.rewind()

        if (scaled !== bitmap) scaled.recycle()

        val tensor = OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, size.toLong(), size.toLong()))
        return DetectorInput(tensor, ratio, size)
    }

    /**
     * w600k_mbf preprocessing, validated bit-identical against insightface's
     * ArcFaceONNX.get_feat() in Face_recognition/benchmarks/compare_embedders.py:34-42:
     * (x - 127.5) / 127.5, BGR->RGB swap, 112x112 NCHW.
     */
    private fun preprocessForRecognizer(bitmap: Bitmap): OnnxTensor {
        val size = REC_INPUT_SIZE
        val cropDim = minOf(bitmap.width, bitmap.height)
        val cropX = (bitmap.width - cropDim) / 2
        val cropY = (bitmap.height - cropDim) / 2
        val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropDim, cropDim)
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)

        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)

        val plane = size * size
        val rArr = FloatArray(plane)
        val gArr = FloatArray(plane)
        val bArr = FloatArray(plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            rArr[i] = (((p shr 16) and 0xFF) - 127.5f) / 127.5f
            gArr[i] = (((p shr 8) and 0xFF) - 127.5f) / 127.5f
            bArr[i] = ((p and 0xFF) - 127.5f) / 127.5f
        }
        val buffer = FloatBuffer.allocate(3 * plane)
        buffer.put(rArr); buffer.put(gArr); buffer.put(bArr)
        buffer.rewind()

        if (cropped !== bitmap) cropped.recycle()
        if (scaled !== cropped) scaled.recycle()

        return OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, size.toLong(), size.toLong()))
    }

    private fun writeCsv(results: List<PhotoTiming>) {
        resultsCsv().bufferedWriter().use { w ->
            w.write("file,width,height,decode_ms,det_preprocess_ms,det_infer_ms,rec_preprocess_ms,rec_infer_ms,total_ms\n")
            for (r in results) {
                w.write(
                    "${r.fileName},${r.widthPx},${r.heightPx}," +
                        "${"%.2f".format(r.decodeMs)},${"%.2f".format(r.detPreprocessMs)}," +
                        "${"%.2f".format(r.detInferMs)},${"%.2f".format(r.recPreprocessMs)}," +
                        "${"%.2f".format(r.recInferMs)},${"%.2f".format(r.totalMs)}\n"
                )
            }
        }
    }

    fun close() {
        if (::detSession.isInitialized) detSession.close()
        if (::recSession.isInitialized) recSession.close()
    }
}
