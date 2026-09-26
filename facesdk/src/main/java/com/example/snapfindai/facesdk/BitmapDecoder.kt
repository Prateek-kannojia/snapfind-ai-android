package com.example.snapfindai.facesdk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/**
 * `android.graphics.BitmapFactory.decodeFile()` does NOT apply EXIF
 * orientation, unlike most server-side decoders (OpenCV's cv2.imread,
 * PIL with exif_transpose) which auto-rotate. Decoding a photo without
 * this correction can hand a detector a completely different orientation
 * than the server sees for the same file — not a subtle difference, a
 * wrong one. Verified pixel-for-pixel against cv2.imread's output and
 * PIL's ImageOps.exif_transpose reference implementation across all 8
 * standard EXIF orientation values.
 */
object BitmapDecoder {
    fun decodeWithExifCorrection(file: File): Bitmap {
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
}
