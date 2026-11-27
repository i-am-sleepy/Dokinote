package app.dokinote.engine

import android.graphics.Bitmap
import android.util.LruCache

/** level = zoom level * 100 (0 means "whole-page low-res thumbnail"). */
data class TileKey(val page: Int, val level: Int, val col: Int, val row: Int)

class RenderJob(
    val key: TileKey,
    /** Size of the whole page at this level, in pixels. */
    val pageWpx: Int,
    val pageHpx: Int,
    /** Tile rectangle inside that page raster. */
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    /** Lower renders first. */
    val priority: Float,
)

/**
 * Bitmap LRU. Main thread only. Bitmaps are deliberately NOT recycled on eviction:
 * a hardware-accelerated canvas may still reference them; the GC frees them.
 */
class TileCache(maxBytes: Int) : LruCache<TileKey, Bitmap>(maxBytes) {
    override fun sizeOf(key: TileKey, value: Bitmap): Int = value.allocationByteCount
}
