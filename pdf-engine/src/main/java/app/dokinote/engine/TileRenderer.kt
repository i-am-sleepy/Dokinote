package app.dokinote.engine

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.artifex.mupdf.fitz.Context as MuContext

/**
 * Pool of render threads. Each thread owns its own MuPDF Document (documents are not thread-safe) and sets the
 * anti-alias level on its own thread (it is per-thread in MuPDF). Submitting a batch replaces the pending queue and
 * aborts running jobs that are no longer wanted, so fast scrolling never renders stale tiles.
 */
class TileRenderer(
    private val path: String,
    private val password: String?,
    workerCount: Int,
) {
    interface Listener { fun onTile(key: TileKey, bitmap: Bitmap) }

    @Volatile var listener: Listener? = null
    @Volatile var aaLevel: Int = 4

    private class Running(val cookie: Cookie) { @Volatile var aborted = false }

    private val lock = java.lang.Object()
    private var pending = ArrayList<RenderJob>()
    private val running = HashMap<TileKey, Running>()
    @Volatile private var alive = true
    private val main = Handler(Looper.getMainLooper())

    init {
        repeat(workerCount) { i ->
            Thread({ workerLoop() }, "tile-$i").apply {
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = true
                start()
            }
        }
    }

    fun submit(batch: List<RenderJob>) {
        synchronized(lock) {
            if (!alive) return
            val keys = HashSet<TileKey>(batch.size * 2)
            for (j in batch) keys.add(j.key)
            for ((k, r) in running) if (k !in keys) { r.aborted = true; r.cookie.abort() }
            val next = ArrayList<RenderJob>(batch.size)
            for (j in batch) if (!running.containsKey(j.key)) next.add(j)
            next.sortBy { it.priority }
            pending = next
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            alive = false
            for (r in running.values) { r.aborted = true; r.cookie.abort() }
            pending.clear()
            lock.notifyAll()
        }
        listener = null
    }

    private fun workerLoop() {
        val pages = LinkedHashMap<Int, Page>()
        var doc: Document? = null
        try {
            val d = Document.openDocument(path)
            doc = d
            if (d.needsPassword() && !d.authenticatePassword(password ?: "")) return
            if (d.isReflowable()) d.layout(ReflowLayout.W, ReflowLayout.H, ReflowLayout.EM)
            var curAa = -1
            while (alive) {
                var job: RenderJob? = null
                var run: Running? = null
                synchronized(lock) {
                    while (alive && pending.isEmpty()) lock.wait()
                    if (alive) {
                        val j = pending.removeAt(0)
                        val r = Running(Cookie())
                        running[j.key] = r
                        job = j
                        run = r
                    }
                }
                val j = job ?: break
                val r = run ?: break
                if (aaLevel != curAa) {
                    curAa = aaLevel
                    MuContext.setAntiAliasLevel(curAa)
                }
                var bmp: Bitmap? = null
                try {
                    var page = pages.remove(j.key.page)
                    if (page == null) {
                        page = d.loadPage(j.key.page)
                        while (pages.size >= 3) {
                            val eldest = pages.entries.iterator().next()
                            try { eldest.value.destroy() } catch (_: Throwable) {}
                            pages.remove(eldest.key)
                        }
                    }
                    pages[j.key.page] = page
                    bmp = renderTile(page, j, r.cookie)
                } catch (t: Throwable) {
                    Log.w(TAG, "tile ${j.key} failed: $t")
                } finally {
                    synchronized(lock) { running.remove(j.key) }
                    try { r.cookie.destroy() } catch (_: Throwable) {}
                }
                val out = bmp
                if (out != null && !r.aborted && alive) {
                    main.post { listener?.onTile(j.key, out) }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "worker died: $t")
        } finally {
            for (p in pages.values) try { p.destroy() } catch (_: Throwable) {}
            try { doc?.destroy() } catch (_: Throwable) {}
        }
    }

    private fun renderTile(page: Page, j: RenderJob, cookie: Cookie): Bitmap {
        val b = page.getBounds()
        val sx = j.pageWpx / (b.x1 - b.x0)
        val sy = j.pageHpx / (b.y1 - b.y0)
        val ctm = Matrix(sx, 0f, 0f, sy, -b.x0 * sx, -b.y0 * sy)
        val bmp = Bitmap.createBitmap(j.w, j.h, Bitmap.Config.ARGB_8888)
        val dev = AndroidDrawDevice(bmp, j.x, j.y)
        try {
            page.run(dev, ctm, cookie)
            dev.close()
        } finally {
            dev.destroy()
        }
        return bmp
    }

    private companion object { const val TAG = "TileRenderer" }
}
