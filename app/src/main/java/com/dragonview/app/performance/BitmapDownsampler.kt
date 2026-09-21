// app/src/main/java/com/dragonview/app/performance/BitmapDownsampler.kt
package com.dragonview.app.performance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Techniques 6 & 7: BITMAP DOWNSAMPLING via inSampleSize & BITMAP POOLING via inBitmap.
 *
 * Always calculates exact powers-of-two inSampleSize to match target view dimensions,
 * never decoding native resolution upfront on 3-4GB devices.
 * Integrates directly with BitmapPool to reuse existing memory buffers.
 */
object BitmapDownsampler {

    fun calculateInSampleSize(
        origWidth: Int,
        origHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (origHeight > reqHeight || origWidth > reqWidth) {
            val halfHeight = origHeight / 2
            val halfWidth = origWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    suspend fun decodeSampledBitmap(
        bytes: ByteArray,
        reqWidth: Int,
        reqHeight: Int,
        preferredConfig: Bitmap.Config = Bitmap.Config.RGB_565,
        pool: BitmapPool? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        try {
            // Step 1: Decode bounds only
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            val origW = boundsOptions.outWidth
            val origH = boundsOptions.outHeight
            if (origW <= 0 || origH <= 0) return@withContext null

            val sampleSize = calculateInSampleSize(origW, origH, reqWidth, reqHeight)
            val targetW = (origW / sampleSize).coerceAtLeast(1)
            val targetH = (origH / sampleSize).coerceAtLeast(1)

            // Step 2: Acquire reusable bitmap if possible
            val reusableBitmap = pool?.acquire(targetW, targetH, preferredConfig)

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = preferredConfig
                inMutable = true
                if (reusableBitmap != null) {
                    inBitmap = reusableBitmap
                }
            }

            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            } catch (_: IllegalArgumentException) {
                // If inBitmap was incompatible, retry without inBitmap
                decodeOptions.inBitmap = null
                pool?.release(reusableBitmap)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int,
        preferredConfig: Bitmap.Config = Bitmap.Config.RGB_565,
        pool: BitmapPool? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Step 1: Read bounds
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return@withContext null

            val origW = boundsOptions.outWidth
            val origH = boundsOptions.outHeight
            if (origW <= 0 || origH <= 0) return@withContext null

            val sampleSize = calculateInSampleSize(origW, origH, reqWidth, reqHeight)
            val targetW = (origW / sampleSize).coerceAtLeast(1)
            val targetH = (origH / sampleSize).coerceAtLeast(1)

            val reusableBitmap = pool?.acquire(targetW, targetH, preferredConfig)

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = preferredConfig
                inMutable = true
                if (reusableBitmap != null) {
                    inBitmap = reusableBitmap
                }
            }

            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }
            } catch (_: IllegalArgumentException) {
                decodeOptions.inBitmap = null
                pool?.release(reusableBitmap)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
