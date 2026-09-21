// app/src/main/java/com/dragonview/app/viewer/image/ImageDecoderHelper.kt
package com.dragonview.app.viewer.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.caverock.androidsvg.SVG
import com.dragonview.app.performance.BitmapPool
import com.dragonview.app.performance.TieredLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.IOException
import android.util.Log
import kotlin.math.max

/**
 * Result of loading an image into memory, ready for display.
 */
sealed class LoadedImageData {
    abstract val width: Int
    abstract val height: Int
    abstract val imageType: ImageType

    data class StaticImage(
        val bitmap: Bitmap,
        override val width: Int,
        override val height: Int,
        override val imageType: ImageType,
        val sampleSize: Int = 1
    ) : LoadedImageData()

    data class AnimatedGifImage(
        val drawable: Drawable,
        val fallbackBitmap: Bitmap?,
        override val width: Int,
        override val height: Int,
        override val imageType: ImageType = ImageType.GIF,
        val isLargeGif: Boolean = false
    ) : LoadedImageData()

    data class VectorSvgImage(
        val bitmap: Bitmap,
        override val width: Int,
        override val height: Int,
        override val imageType: ImageType = ImageType.SVG
    ) : LoadedImageData()

    fun recycle() {
        when (this) {
            is StaticImage -> {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            is AnimatedGifImage -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    (drawable as? AnimatedImageDrawable)?.stop()
                }
                fallbackBitmap?.let {
                    if (!it.isRecycled) it.recycle()
                }
            }
            is VectorSvgImage -> {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }
}

/**
 * Low-RAM, subsampling image decoder supporting:
 * JPEG, PNG, GIF, WEBP, BMP, TIFF, HEIC, SVG.
 */
object ImageDecoderHelper {

    private const val DEFAULT_MAX_DIMENSION_PX = 2048
    private const val LARGE_GIF_THRESHOLD_BYTES = 5 * 1024 * 1024L // 5 MB

    suspend fun decode(
        context: Context,
        uri: Uri,
        type: ImageType,
        fileSize: Long = -1L,
        maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX
    ): Result<LoadedImageData> = withContext(Dispatchers.IO) {
        try {
            when (type) {
                ImageType.SVG -> decodeSvg(context, uri, maxDimensionPx)
                ImageType.GIF -> decodeGif(context, uri, fileSize, maxDimensionPx)
                ImageType.HEIC -> decodeHeic(context, uri, maxDimensionPx)
                ImageType.TIFF -> decodeTiff(context, uri, maxDimensionPx)
                else -> decodeStandardRaster(context, uri, type, maxDimensionPx)
            }
        } catch (e: OutOfMemoryError) {
            System.gc()
            Log.e("ImageDecoderHelper", "OOM decoding image: $uri (type: $type)", e)
            Result.failure(
                IllegalArgumentException("Out of memory decoding image: ${e.message}", e)
            )
        } catch (e: Exception) {
            Log.e("ImageDecoderHelper", "Exception decoding image: $uri (type: $type): ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun openInputStreamSafe(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            if (uri.scheme == "file" && uri.path != null) {
                try {
                    java.io.FileInputStream(java.io.File(uri.path!!))
                } catch (fe: Exception) {
                    Log.e("ImageDecoderHelper", "Failed to open file stream for $uri: ${fe.message}", fe)
                    null
                }
            } else {
                Log.e("ImageDecoderHelper", "Failed to open input stream for $uri: ${e.message}", e)
                null
            }
        }
    }

    private fun decodeSvg(
        context: Context,
        uri: Uri,
        maxDimensionPx: Int
    ): Result<LoadedImageData> {
        val stream: InputStream = openInputStreamSafe(context, uri)
            ?: return Result.failure(IllegalArgumentException("Unable to open input stream for SVG: $uri"))

        return stream.use { isStream ->
            try {
                val svg = SVG.getFromInputStream(isStream)
                    ?: return Result.failure(IllegalArgumentException("Failed to parse SVG structure from $uri"))

                var docW = svg.documentWidth
                var docH = svg.documentHeight

                if (docW <= 0f || docH <= 0f) {
                    val viewBox = svg.documentViewBox
                    if (viewBox != null && viewBox.width() > 0 && viewBox.height() > 0) {
                        docW = viewBox.width()
                        docH = viewBox.height()
                    } else {
                        docW = 1080f
                        docH = 1080f
                    }
                }

                val scale = if (docW > maxDimensionPx || docH > maxDimensionPx) {
                    (maxDimensionPx.toFloat() / max(docW, docH)).coerceAtMost(1f)
                } else {
                    1f
                }

                val targetW = (docW * scale).toInt().coerceIn(1, maxDimensionPx)
                val targetH = (docH * scale).toInt().coerceIn(1, maxDimensionPx)

                val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.scale(targetW / docW, targetH / docH)
                svg.renderToCanvas(canvas)

                Result.success(
                    LoadedImageData.VectorSvgImage(
                        bitmap = bitmap,
                        width = targetW,
                        height = targetH
                    )
                )
            } catch (e: Exception) {
                Log.e("ImageDecoderHelper", "SVG parsing failed for $uri: ${e.message}", e)
                Result.failure(IllegalArgumentException("SVG parsing failed: ${e.message}", e))
            }
        }
    }

    private fun decodeGif(
        context: Context,
        uri: Uri,
        fileSize: Long,
        maxDimensionPx: Int
    ): Result<LoadedImageData> {
        val isLarge = fileSize > LARGE_GIF_THRESHOLD_BYTES

        // On API 28+, Android provides AnimatedImageDrawable via ImageDecoder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = if (uri.scheme == "file" && uri.path != null) {
                    ImageDecoder.createSource(java.io.File(uri.path!!))
                } else {
                    ImageDecoder.createSource(context.contentResolver, uri)
                }
                var width = 0
                var height = 0
                val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    width = info.size.width
                    height = info.size.height
                    val sampleSize = calculateInSampleSize(width, height, maxDimensionPx, maxDimensionPx)
                    if (sampleSize > 1) {
                        decoder.setTargetSampleSize(sampleSize)
                    }
                }

                if (drawable is AnimatedImageDrawable) {
                    return Result.success(
                        LoadedImageData.AnimatedGifImage(
                            drawable = drawable,
                            fallbackBitmap = null,
                            width = if (width > 0) width else 500,
                            height = if (height > 0) height else 500,
                            isLargeGif = isLarge
                        )
                    )
                }
            } catch (e: Throwable) {
                Log.w("ImageDecoderHelper", "AnimatedImageDrawable decode failed for $uri: ${e.message}", e)
                // Fall back to static raster below
            }
        }

        // Fallback for API < 28 or if AnimatedImageDrawable fails
        val staticResult = decodeStandardRaster(context, uri, ImageType.GIF, maxDimensionPx)
        return if (staticResult.isSuccess) {
            val staticData = staticResult.getOrThrow()
            if (staticData is LoadedImageData.StaticImage) {
                Result.success(
                    LoadedImageData.AnimatedGifImage(
                        drawable = android.graphics.drawable.BitmapDrawable(context.resources, staticData.bitmap),
                        fallbackBitmap = staticData.bitmap,
                        width = staticData.width,
                        height = staticData.height,
                        isLargeGif = isLarge
                    )
                )
            } else {
                staticResult
            }
        } else {
            staticResult
        }
    }

    private fun decodeHeic(
        context: Context,
        uri: Uri,
        maxDimensionPx: Int
    ): Result<LoadedImageData> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Result.failure(
                UnsupportedOperationException("HEIC images require Android 9+ (API 28). Current device is API ${Build.VERSION.SDK_INT}.")
            )
        }

        return try {
            val source = if (uri.scheme == "file" && uri.path != null) {
                ImageDecoder.createSource(java.io.File(uri.path!!))
            } else {
                ImageDecoder.createSource(context.contentResolver, uri)
            }
            var origW = 0
            var origH = 0
            var sample = 1

            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                origW = info.size.width
                origH = info.size.height
                sample = calculateInSampleSize(origW, origH, maxDimensionPx, maxDimensionPx)
                if (sample > 1) {
                    decoder.setTargetSampleSize(sample)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

            Result.success(
                LoadedImageData.StaticImage(
                    bitmap = bitmap,
                    width = if (origW > 0) origW else bitmap.width,
                    height = if (origH > 0) origH else bitmap.height,
                    imageType = ImageType.HEIC,
                    sampleSize = sample
                )
            )
        } catch (e: Exception) {
            Log.e("ImageDecoderHelper", "Failed to decode HEIC from $uri: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun decodeTiff(
        context: Context,
        uri: Uri,
        maxDimensionPx: Int
    ): Result<LoadedImageData> {
        return try {
            // First attempt with ImageDecoder on API 28+ if platform codec supports TIFF
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val source = if (uri.scheme == "file" && uri.path != null) {
                        ImageDecoder.createSource(java.io.File(uri.path!!))
                    } else {
                        ImageDecoder.createSource(context.contentResolver, uri)
                    }
                    var origW = 0
                    var origH = 0
                    val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        origW = info.size.width
                        origH = info.size.height
                        val sample = calculateInSampleSize(origW, origH, maxDimensionPx, maxDimensionPx)
                        if (sample > 1) decoder.setTargetSampleSize(sample)
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    return Result.success(
                        LoadedImageData.StaticImage(
                            bitmap = bmp,
                            width = if (origW > 0) origW else bmp.width,
                            height = if (origH > 0) origH else bmp.height,
                            imageType = ImageType.TIFF
                        )
                    )
                } catch (e: Throwable) {
                    Log.i("ImageDecoderHelper", "ImageDecoder TIFF decode skipped/failed (${e.message}), falling back to built-in TiffDecoder")
                }
            }

            // Fallback to built-in pure-Kotlin TiffDecoder
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(IllegalArgumentException("Could not read TIFF data from $uri"))

            val bitmap = TiffDecoder.decode(bytes, maxDimensionPx)
            Result.success(
                LoadedImageData.StaticImage(
                    bitmap = bitmap,
                    width = bitmap.width,
                    height = bitmap.height,
                    imageType = ImageType.TIFF
                )
            )
        } catch (e: Exception) {
            Log.e("ImageDecoderHelper", "TIFF decode error for $uri: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Techniques 3 & 7: Shared pool & LRU cache for images
    val bitmapPool = BitmapPool(maxEntries = 4)
    val imageTileCache = TieredLruCache(maxSize = 4, bitmapPool = bitmapPool)

    fun decodeRegionTile(
        context: Context,
        uri: Uri,
        rect: Rect,
        sampleSize: Int
    ): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                @Suppress("DEPRECATION")
                val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BitmapRegionDecoder.newInstance(stream)
                } else {
                    BitmapRegionDecoder.newInstance(stream, false)
                }
                val reqW = (rect.width() / sampleSize).coerceAtLeast(1)
                val reqH = (rect.height() / sampleSize).coerceAtLeast(1)
                val reusable = bitmapPool.acquire(reqW, reqH, Bitmap.Config.RGB_565)

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inMutable = true
                    if (reusable != null) inBitmap = reusable
                }

                try {
                    decoder?.decodeRegion(rect, options)
                } catch (_: IllegalArgumentException) {
                    options.inBitmap = null
                    bitmapPool.release(reusable)
                    decoder?.decodeRegion(rect, options)
                } finally {
                    decoder?.recycle()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeStandardRaster(
        context: Context,
        uri: Uri,
        imageType: ImageType,
        maxDimensionPx: Int
    ): Result<LoadedImageData> {
        // Step 1: Subsampling check with inJustDecodeBounds
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        val openBoundsResult = runCatching {
            openInputStreamSafe(context, uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
        }
        if (openBoundsResult.isFailure) {
            val ex = openBoundsResult.exceptionOrNull()
            Log.e("ImageDecoderHelper", "Failed to open stream for bounds check: $uri", ex)
            return Result.failure(ex ?: IOException("Could not open input stream for $uri"))
        }

        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight

        if (origW <= 0 || origH <= 0) {
            val errMsg = "BitmapFactory failed to decode dimensions for $uri (outWidth=$origW, outHeight=$origH, mime=${boundsOptions.outMimeType})"
            Log.e("ImageDecoderHelper", errMsg)
            return Result.failure(IllegalArgumentException("Failed to decode image dimensions for $uri ($imageType)"))
        }

        val sampleSize = calculateInSampleSize(origW, origH, maxDimensionPx, maxDimensionPx)
        val targetW = (origW / sampleSize).coerceAtLeast(1)
        val targetH = (origH / sampleSize).coerceAtLeast(1)

        val preferredConfig = when (imageType) {
            ImageType.PNG, ImageType.WEBP -> Bitmap.Config.ARGB_8888
            else -> Bitmap.Config.RGB_565 // Low-RAM friendly RGB_565 (50% memory saving)
        }

        // Technique 7: inBitmap reuse from pool
        val reusableBitmap = bitmapPool.acquire(targetW, targetH, preferredConfig)

        // Step 2: Decode scaled bitmap with memory bounds
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = preferredConfig
            inMutable = true
            if (reusableBitmap != null) {
                inBitmap = reusableBitmap
            }
        }

        var bitmap: Bitmap? = try {
            openInputStreamSafe(context, uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (e: Throwable) {
            Log.w("ImageDecoderHelper", "First decode attempt failed with reusable inBitmap: ${e.message}")
            null
        }

        // Retry without inBitmap and with ARGB_8888 fallback if needed
        if (bitmap == null) {
            decodeOptions.inBitmap = null
            bitmapPool.release(reusableBitmap)
            decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888
            bitmap = try {
                openInputStreamSafe(context, uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }
            } catch (e: Throwable) {
                Log.e("ImageDecoderHelper", "Fallback decode attempt failed for $uri: ${e.message}", e)
                null
            }
        }

        if (bitmap == null) {
            val errMsg = "BitmapFactory failed to decode image data for $uri (format: $imageType, size: ${origW}x${origH})"
            Log.e("ImageDecoderHelper", errMsg)
            return Result.failure(IllegalArgumentException(errMsg))
        }

        return Result.success(
            LoadedImageData.StaticImage(
                bitmap = bitmap,
                width = origW,
                height = origH,
                imageType = imageType,
                sampleSize = sampleSize
            )
        )
    }

    fun calculateInSampleSize(
        actualWidth: Int,
        actualHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (actualHeight > reqHeight || actualWidth > reqWidth) {
            val halfHeight = actualHeight / 2
            val halfWidth = actualWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}
