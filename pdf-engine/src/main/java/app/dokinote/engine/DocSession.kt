package app.dokinote.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.File
import java.io.FileOutputStream

class PasswordRequired : Exception("Password required")

data class OutlineItem(val title: String, val page: Int, val level: Int)

/** An open document: page geometry, a pool of render threads, outline access. */
class DocSession private constructor(
    val file: File,
    val displayName: String,
    private val password: String?,
    val pageCount: Int,
    val isPdf: Boolean,
    val reflowable: Boolean,
) {
    /** Page sizes in points; 0 until known (filled in the background for PDFs). */
    val pageWidths = FloatArray(pageCount)
    val pageHeights = FloatArray(pageCount)

    /** Called on the main thread when more page sizes became known. */
    @Volatile var onSizesChanged: (() -> Unit)? = null

    val renderer: TileRenderer
    @Volatile private var closed = false
    private val main = Handler(Looper.getMainLooper())

    init {
        val cores = Runtime.getRuntime().availableProcessors()
        renderer = TileRenderer(file.absolutePath, password, (cores / 2).coerceIn(2, 4))
    }

    private fun openLocal(): Document {
        val d = Document.openDocument(file.absolutePath)
        if (d.needsPassword() && !d.authenticatePassword(password ?: "")) { d.destroy(); throw PasswordRequired() }
        if (d.isReflowable()) d.layout(ReflowLayout.W, ReflowLayout.H, ReflowLayout.EM)
        return d
    }

    private fun startMetrics() {
        if (reflowable) {
            pageWidths.fill(ReflowLayout.W)
            pageHeights.fill(ReflowLayout.H)
            return
        }
        Thread({
            var d: Document? = null
            try {
                val doc = openLocal()
                d = doc
                for (i in 0 until pageCount) {
                    if (closed) break
                    val p = doc.loadPage(i)
                    val b = p.getBounds()
                    pageWidths[i] = b.x1 - b.x0
                    pageHeights[i] = b.y1 - b.y0
                    p.destroy()
                    if (i % 64 == 63 || i == pageCount - 1) main.post { onSizesChanged?.invoke() }
                }
            } catch (_: Throwable) {
            } finally {
                try { d?.destroy() } catch (_: Throwable) {}
            }
        }, "page-sizes").apply { isDaemon = true; start() }
    }

    fun loadOutline(): List<OutlineItem> {
        val d = openLocal()
        try {
            val top = d.loadOutline() ?: return emptyList()
            val res = ArrayList<OutlineItem>()
            fun walk(items: Array<Outline>, level: Int) {
                for (o in items) {
                    val page = try { d.pageNumberFromLocation(d.resolveLink(o)) } catch (_: Throwable) { -1 }
                    res.add(OutlineItem(o.title ?: "", page, level))
                    o.down?.let { walk(it, level + 1) }
                }
            }
            walk(top, 0)
            return res
        } finally {
            d.destroy()
        }
    }

    fun close() {
        closed = true
        onSizesChanged = null
        renderer.close()
    }

    companion object {
        /** Blocking. Throws [PasswordRequired] when the document needs a (new) password. */
        fun open(file: File, displayName: String, password: String?): DocSession {
            val d = Document.openDocument(file.absolutePath)
            try {
                if (d.needsPassword() && (password == null || !d.authenticatePassword(password))) throw PasswordRequired()
                val reflow = d.isReflowable()
                if (reflow) d.layout(ReflowLayout.W, ReflowLayout.H, ReflowLayout.EM)
                val n = d.countPages()
                require(n > 0) { "The document has no pages" }
                val s = DocSession(file, displayName, password, n, d.isPDF(), reflow)
                val p0 = d.loadPage(0)
                val b = p0.getBounds()
                s.pageWidths[0] = b.x1 - b.x0
                s.pageHeights[0] = b.y1 - b.y0
                p0.destroy()
                s.startMetrics()
                return s
            } finally {
                d.destroy()
            }
        }
    }
}

object Thumbs {
    fun file(ctx: Context, uri: Uri): File {
        val dir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
        return File(dir, Hash.sha1Hex(uri.toString()).take(16) + ".jpg")
    }

    /** Renders page 1 to a small JPEG once (used by the recent-files list). */
    fun ensure(ctx: Context, uri: Uri, doc: File, password: String?) {
        val out = file(ctx, uri)
        if (out.exists()) return
        val d = Document.openDocument(doc.absolutePath)
        try {
            if (d.needsPassword() && !d.authenticatePassword(password ?: "")) return
            if (d.isReflowable()) d.layout(ReflowLayout.W, ReflowLayout.H, ReflowLayout.EM)
            val p = d.loadPage(0)
            val bmp: Bitmap = AndroidDrawDevice.drawPageFitWidth(p, 240)
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bmp.recycle()
            p.destroy()
        } finally {
            d.destroy()
        }
    }
}
