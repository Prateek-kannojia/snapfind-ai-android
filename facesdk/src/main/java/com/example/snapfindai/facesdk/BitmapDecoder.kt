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

    /**
     * Upper bound on decoded pixels, which exists to survive modern camera
     * outputs rather than to save memory in general.
     *
     * A 108MP photo decodes to 432MB as ARGB_8888, and the EXIF rotation
     * below briefly holds a second copy -- 864MB, which no phone survives.
     * 200MP sensors make that 1.6GB. One such photo anywhere in a ZIP would
     * take the whole job down.
     *
     * Set at roughly what an ordinary phone photo already is, deliberately.
     * This is NOT the blanket downsampling that measurably cost matching
     * accuracy: that failed because shrinking a normal photo shrinks the
     * already-small faces in it. A cap only ever brings an outlier *down to*
     * the resolution every successfully-matched photo already has -- a 108MP
     * frame reduced to 12MP has exactly the face pixel count a 12MP frame of
     * the same scene would. Photos at or below the cap take the identical
     * decode path they always did, with inSampleSize = 1.
     */
    const val MAX_PIXELS = 12_000_000

    fun decodeWithExifCorrection(file: File): Bitmap {
        val raw = decodeWithinPixelCap(file)
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

    /**
     * Reads the dimensions first with `inJustDecodeBounds`, which allocates
     * nothing, so an oversized image is known about before anything tries to
     * hold it in memory.
     */
    private fun decodeWithinPixelCap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /**
     * Smallest power-of-two subsampling that brings the image under
     * [MAX_PIXELS]. Powers of two because BitmapFactory rounds anything else
     * down to one anyway, so asking for 3 would silently give 2.
     *
     * Returns 1 -- meaning "decode exactly as before" -- for any image
     * already within the cap, and for unreadable bounds, where guessing would
     * be worse than letting the real decode fail and be reported.
     */
    internal fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        while ((width.toLong() / sampleSize) * (height.toLong() / sampleSize) > MAX_PIXELS) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
