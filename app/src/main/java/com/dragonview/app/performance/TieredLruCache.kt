// app/src/main/java/com/dragonview/app/performance/TieredLruCache.kt
package com.dragonview.app.performance

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Technique 3: LRU CACHING for rendered pages/slides/images.
 *
 * - Cache size: 3-5 items max (low RAM budget, Android 9-11 3-4GB).
 * - Cache key: page/slide/sheet index + resolution tier ("${index}_tier${tier}").
 * - Connects to BitmapPool: evicted Bitmaps are recycled into the pool for immediate inBitmap reuse.
 */
class TieredLruCache(
    maxSize: Int = 4,
    private val bitmapPool: BitmapPool? = null
) : LruCache<String, Bitmap>(maxSize) {

    companion object {
        const val TIER_LOW = 1
        const val TIER_MEDIUM = 2
        const val TIER_HIGH = 3

        fun makeKey(itemIndex: Int, tier: Int, subKey: String = ""): String {
            return if (subKey.isEmpty()) "$itemIndex@tier$tier" else "$itemIndex@tier$tier#$subKey"
        }
    }

    fun getTiered(itemIndex: Int, tier: Int, subKey: String = ""): Bitmap? {
        val key = makeKey(itemIndex, tier, subKey)
        val bmp = get(key)
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    fun putTiered(itemIndex: Int, tier: Int, bitmap: Bitmap, subKey: String = "") {
        if (!bitmap.isRecycled) {
            put(makeKey(itemIndex, tier, subKey), bitmap)
        }
    }

    override fun entryRemoved(
        evicted: Boolean,
        key: String,
        oldValue: Bitmap,
        newValue: Bitmap?
    ) {
        super.entryRemoved(evicted, key, oldValue, newValue)
        if (evicted && oldValue != newValue) {
            // Hand off to pool for inBitmap reuse or recycling
            bitmapPool?.release(oldValue)
        }
    }
}
