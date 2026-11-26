package app.dokinote

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.File
import kotlin.concurrent.thread
import com.artifex.mupdf.fitz.Context as MuContext

enum class Mode { NONE, BENCH, OFFICE, REDACT }

private fun ms(startNs: Long) = (System.nanoTime() - startNs) / 1e6
private fun fmt(x: Double) = "%.1f".format(x)
private fun pct(xs: List<Double>, p: Double): Double {
    val s = xs.sorted()
    return s[((s.size - 1) * p).toInt()]
}

fun runSpike(ctx: Context, uri: Uri, mode: Mode, needle: String, log: (String) -> Unit) {
    when (mode) {
        Mode.BENCH -> benchmark(ctx, uri, log)
        Mode.OFFICE -> openAndConvert(ctx, uri, log)
        Mode.REDACT -> redactionTest(ctx, uri, needle, log)
        Mode.NONE -> Unit
    }
    log("-- done --")
    log("")
}

fun envReport(ctx: Context, log: (String) -> Unit) {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mu = try { MuContext.getVersion().version } catch (t: Throwable) { "LOAD FAILED: $t" }
    log("== Environment")
    log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    log("ABIs: ${Build.SUPPORTED_ABIS.joinToString()} | cores: ${Runtime.getRuntime().availableProcessors()}")
    log("Page size: ${Os.sysconf(OsConstants._SC_PAGESIZE)} B | memoryClass ${am.memoryClass} MB, large ${am.largeMemoryClass} MB")
    log("MuPDF: $mu")
    log("")
}

private fun copyToCache(ctx: Context, uri: Uri): File {
    var name = "input"
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) name = c.getString(0) ?: name
    }
    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val f = File(ctx.cacheDir, "in_$safe") // keep the extension: MuPDF picks the handler from it
    ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
    return f
}

private fun samplePages(n: Int): List<Int> =
    if (n <= 24) (0 until n).toList() else (0 until 24).map { it * (n - 1) / 23 }

fun benchmark(ctx: Context, uri: Uri, log: (String) -> Unit) {
    val f = copyToCache(ctx, uri)
    log("== Benchmark: ${f.name} (${f.length() / 1024} KB)")
    val t0 = System.nanoTime()
    val doc = Document.openDocument(f.absolutePath)
    val openMs = ms(t0)
    if (doc.needsPassword()) { log("password protected: skipped"); doc.destroy(); return }
    if (doc.isReflowable()) doc.layout(595f, 842f, 11f)
    val n = doc.countPages()
    log("open ${fmt(openMs)} ms | pages $n | PDF=${doc.isPDF()} | reflowable=${doc.isReflowable()}")
    if (n == 0) { doc.destroy(); return }
    val picks = samplePages(n)

    MuContext.setAntiAliasLevel(8)
    val tc = System.nanoTime()
    run {
        val p = doc.loadPage(picks[0])
        AndroidDrawDevice.drawPageFitWidth(p, 1080).recycle()
        p.destroy()
    }
    log("first page cold (load+render @1080w): ${fmt(ms(tc))} ms")

    for (aa in listOf(8, 4, 0)) {
        MuContext.setAntiAliasLevel(aa)
        val times = ArrayList<Double>()
        for (i in picks) {
            val t = System.nanoTime()
            val page = doc.loadPage(i)
            val bmp = AndroidDrawDevice.drawPageFitWidth(page, 1080)
            times.add(ms(t))
            bmp.recycle()
            page.destroy()
        }
        log("render @1080w AA=$aa: median ${fmt(pct(times, 0.5))} ms | p90 ${fmt(pct(times, 0.9))} | max ${fmt(times.max())} (${times.size} pages)")
    }
    MuContext.setAntiAliasLevel(8)

    val tt = System.nanoTime()
    var chars = 0
    for (i in picks) {
        val p = doc.loadPage(i)
        val st = p.toStructuredText()
        chars += st.asText().length
        st.destroy()
        p.destroy()
    }
    log("text extraction ${picks.size} pages: ${fmt(ms(tt))} ms total, $chars chars")

    val cores = Runtime.getRuntime().availableProcessors()
    for (w in listOf(1, 2, 4).filter { it <= cores }) {
        val t = System.nanoTime()
        val ths = (0 until w).map { k ->
            thread(name = "par-$k") {
                try {
                    val d = Document.openDocument(f.absolutePath) // one Document per worker
                    if (d.isReflowable()) d.layout(595f, 842f, 11f)
                    for ((idx, i) in picks.withIndex()) {
                        if (idx % w == k) {
                            val p = d.loadPage(i)
                            AndroidDrawDevice.drawPageFitWidth(p, 1080).recycle()
                            p.destroy()
                        }
                    }
                    d.destroy()
                } catch (e: Throwable) {
                    log("!! worker $k failed: $e")
                }
            }
        }
        ths.forEach { it.join() }
        log("parallel x$w (own Document per worker): ${fmt(ms(t))} ms wall for ${picks.size} pages, includes opens")
    }
    log("native heap now: ${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)} MB")
    doc.destroy()
}

fun openAndConvert(ctx: Context, uri: Uri, log: (String) -> Unit) {
    val f = copyToCache(ctx, uri)
    log("== Open + convert: ${f.name} (${f.length() / 1024} KB)")
    val t0 = System.nanoTime()
    val doc = Document.openDocument(f.absolutePath)
    log("opened in ${fmt(ms(t0))} ms | PDF=${doc.isPDF()} | reflowable=${doc.isReflowable()} | password=${doc.needsPassword()}")
    if (doc.needsPassword()) { doc.destroy(); return }
    if (doc.isReflowable()) { doc.layout(595f, 842f, 11f); log("laid out as A4 @ 11pt") }
    val n = doc.countPages()
    log("pages: $n")
    if (n == 0) { log("FAIL: zero pages"); doc.destroy(); return }

    val p0 = doc.loadPage(0)
    val tr = System.nanoTime()
    val bmp = AndroidDrawDevice.drawPageFitWidth(p0, 1080)
    val renderMs = ms(tr)
    val px = IntArray(bmp.width * bmp.height)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    val nonWhite = px.count { (it and 0xFFFFFF) != 0xFFFFFF }
    val st = p0.toStructuredText()
    val chars = st.asText().length
    st.destroy()
    log("page 1: render ${fmt(renderMs)} ms | ${bmp.width}x${bmp.height} | non-white px ${"%.2f".format(100.0 * nonWhite / px.size)}% | text chars $chars")
    bmp.recycle()
    p0.destroy()

    val out = File(ctx.cacheDir, f.nameWithoutExtension + "_converted.pdf")
    val pages = minOf(n, 50)
    val tw = System.nanoTime()
    val w = DocumentWriter(out.absolutePath, "pdf", "")
    for (i in 0 until pages) {
        val p = doc.loadPage(i)
        val dev = w.beginPage(p.getBounds())
        p.run(dev, Matrix())
        w.endPage()
        p.destroy()
    }
    w.close()
    w.destroy()
    log("converted $pages page(s) -> ${out.length() / 1024} KB PDF in ${fmt(ms(tw))} ms")

    val d2 = Document.openDocument(out.absolutePath)
    val p2 = d2.loadPage(0)
    val st2 = p2.toStructuredText()
    val chars2 = st2.asText().length
    st2.destroy()
    p2.destroy()
    log("re-opened result: pages ${d2.countPages()} | PDF=${d2.isPDF()} | page 1 text chars $chars2 (original $chars)")
    d2.destroy()
    doc.destroy()
}

fun redactionTest(ctx: Context, uri: Uri, needle: String, log: (String) -> Unit) {
    if (needle.isBlank()) { log("Type the word to redact first (it never leaves the device)."); return }
    val f = copyToCache(ctx, uri)
    log("== Redaction: ${f.name} | word length ${needle.length}")
    val doc = Document.openDocument(f.absolutePath)
    if (!doc.isPDF()) { log("not a PDF: skipped"); doc.destroy(); return }
    val pdf = doc.asPDF()
    val total = pdf.countPages()
    var target = -1
    var before = 0
    for (i in 0 until minOf(total, 300)) {
        val p = pdf.loadPage(i)
        val hits = p.search(needle).size
        p.destroy()
        before += hits
        if (hits > 0 && target < 0) target = i
    }
    log("hits before (first ${minOf(total, 300)} pages): $before | first on page index $target")
    if (target < 0) { log("word not found: nothing to redact"); pdf.destroy(); return }

    val page = pdf.loadPage(target) as PDFPage
    val charsBefore = page.toStructuredText().let { val c = it.asText().length; it.destroy(); c }
    for (hit in page.search(needle)) {
        for (q in hit) {
            val a = page.createAnnotation(PDFAnnotation.TYPE_REDACT)
            a.setRect(q.toRect())
            a.update()
            a.destroy()
        }
    }
    val applied = page.applyRedactions(true, PDFPage.REDACT_IMAGE_PIXELS, PDFPage.REDACT_LINE_ART_NONE, PDFPage.REDACT_TEXT_REMOVE)
    page.update()
    page.destroy()
    val out = File(ctx.cacheDir, "redacted.pdf")
    pdf.save(out.absolutePath, "garbage=compact,compress")
    log("applyRedactions returned $applied | saved ${out.length() / 1024} KB")
    pdf.destroy()

    val d2 = Document.openDocument(out.absolutePath)
    var after = 0
    for (i in 0 until minOf(d2.countPages(), 300)) {
        val p = d2.loadPage(i)
        after += p.search(needle).size
        p.destroy()
    }
    val pg = d2.loadPage(target)
    val st = pg.toStructuredText()
    val text = st.asText()
    val stillInText = text.contains(needle, ignoreCase = true)
    st.destroy()
    pg.destroy()
    d2.destroy()
    log("hits after: $after | still in extracted text of that page: $stillInText")
    log("page text chars: $charsBefore -> ${text.length} (other text should remain)")
    log(if (after == 0 && !stillInText) "RESULT: PASS (word removed from text layer)" else "RESULT: FAIL (word still present)")
}
