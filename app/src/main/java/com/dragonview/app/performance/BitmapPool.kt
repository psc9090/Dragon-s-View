// app/src/main/java/com/dragonview/app/performance/BitmapPool.kt
package com.dragonview.app.performance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.Collections

/**
 * Technique 7: BITMAP POOLING via inBitmap reuse.
 *
 * Provides a bounded pool (max 4 bitmaps) of reusable mutable Bitmaps to pass to
 * BitmapFactory.Options.inBitmap. This completely eliminates GC pressure and allocation
 * stutters when decoding pages, slides, or zoom tiles on low-RAM devices (Android 9-11, 3-4GB).
 */
class BitmapPool(private val maxEntries: Int = 4) {

    private val pool = Collections.synchronizedList(ArrayList<Bitmap>())

    /**
     * Finds a reusable Bitmap in the pool matching or exceeding the requested size,
     * or returns null if none available.
     */
    fun acquire(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
        val requiredBytes = width * height * bytesPerPixel(config)
        synchronized(pool) {
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.isRecycled) {
                    iterator.remove()
                    continue
                }
                // On Android 4.4+ (API 19+), inBitmap works as long as allocationByteCount >= candidate requirement
                if (candidate.isMutable && candidate.allocationByteCount >= requiredBytes) {
                    iterator.remove()
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * Returns a bitmap to the pool for reuse if space permits, or recycles it.
     */
    fun release(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || !bitmap.isMutable) return

        synchronized(pool) {
            if (pool.size < maxEntries && !pool.contains(bitmap)) {
                pool.add(bitmap)
                return
            }
        }
        // If pool is full, recycle bitmap immediately
        bitmap.recycle()
    }

    fun clear() {
        synchronized(pool) {
            for (bmp in pool) {
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
            pool.clear()
        }
    }

    private fun bytesPerPixel(config: Bitmap.Config): Int {
        return when (config) {
            Bitmap.Config.ARGB_8888 -> 4
            Bitmap.Config.RGB_565 -> 2
            Bitmap.Config.ALPHA_8 -> 1
            Bitmap.Config.ARGB_4444 -> 2
            Bitmap.Config.RGBA_F16 -> 8
            else -> 4
        }
    }
}
