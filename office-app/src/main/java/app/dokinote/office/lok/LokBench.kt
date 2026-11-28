package app.dokinote.office.lok

import android.content.Context
import android.content.res.AssetManager
import app.dokinote.office.BuildInfo
import android.graphics.Bitmap
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import org.libreoffice.kit.DirectBufferAllocator
import org.libreoffice.kit.Document
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * P0 spike: measures LibreOfficeKit on the device. Runs ONLY in the `:lok` process, and every LOK call is made from
 * the one worker thread that calls [runBench] / [runCold] (LOK's README: JNI calls must come from a single thread).
 *
 * Crash safety: before every step the partial result is written to disk, so if the native code kills the process the
 * next run records "CRASH at step X" for that document and continues with the next one.
 */
class LokBench(
    private val ctx: Context,
    private val inDir: File,
    private val outDir: File,
    private val salLogging: Boolean,
    private val serviceCreatedAt: Long,
    private val log: (String) -> Unit,
) {
    private val dm = ctx.resources.displayMetrics
    private val screenW = minOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(320)
    private val screenH = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(480)

    private val resultsFile = File(outDir, "results.jsonl")
    private val initsFile = File(outDir, "inits.jsonl")
    private val coldFile = File(outDir, "cold.jsonl")
    private val inProgress = File(outDir, "inprogress.txt")
    private val partialFile = File(outDir, "partial.json")
    private val pngDir = File(outDir, "png")
    private val convDir = File(outDir, "conv")

    private var office: Office? = null
    private val callbackCounts = ConcurrentHashMap<Int, AtomicInteger>()
    private val tileBuf: ByteBuffer = DirectBufferAllocator.allocate(256 * 256 * 4)
    private var bigBuf: ByteBuffer = DirectBufferAllocator.allocate(screenW * screenH * 4)

    private val officeCb = Office.MessageCallback { signal, _ -> count(signal) }
    private val docCb = Document.MessageCallback { signal, _ -> count(signal) }

    private fun count(signal: Int) {
        callbackCounts.getOrPut(signal) { AtomicInteger() }.incrementAndGet()
    }

    // ---------------------------------------------------------------- helpers

    private fun initStep(s: String) {
        try { File(outDir, "initstep.txt").writeText(s) } catch (e: Exception) { /* ignore */ }
    }

    private fun now() = System.nanoTime()
    private fun msSince(t0: Long): Double = ((System.nanoTime() - t0) / 10_000L) / 100.0 // 0.01 ms resolution

    private fun pssKb(): Int {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return mi.totalPss
    }

    private fun stats(v: List<Double>): JSONObject {
        val o = JSONObject()
        if (v.isEmpty()) return o
        val s = v.sorted()
        o.put("n", s.size)
        o.put("p50", round1(s[s.size / 2]))
        o.put("p90", round1(s[((s.size * 0.9).toInt()).coerceAtMost(s.size - 1)]))
        o.put("max", round1(s.last()))
        o.put("sum", round1(s.sum()))
        return o
    }

    private fun round1(d: Double) = (d * 10.0).roundToInt() / 10.0

    private fun ensureBig(bytes: Int) {
        if (bigBuf.capacity() < bytes) bigBuf = DirectBufferAllocator.allocate(bytes)
        bigBuf.clear()
    }

    private fun savePng(buf: ByteBuffer, w: Int, h: Int, file: File) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    // ---------------------------------------------------------------- engine init

    /**
     * The Viewer copies everything below assets/unpack (UNO type registries udkapi, offapi and oovbaapi .rdb, sofficerc, fonts.conf, bundled
     * fonts) into the app data dir BEFORE starting LibreOfficeKit (LibreOfficeMainActivity.updatePreferences in the
     * LibreOffice source). unorc points UNO_TYPES at the .rdb files in APP_DATA_DIR/program; without them init aborts with a
     * DeploymentException (first spike run, 2026-11-28). Re-done whenever the app version changes.
     */
    private fun unpackAssets(o: JSONObject) {
        val dataDir = ctx.applicationInfo.dataDir
        val marker = File(ctx.filesDir, "spike/unpacked_for.txt")
        val want = BuildInfo.VERSION
        if (marker.exists() && marker.readText() == want && File(dataDir, "program/udkapi.rdb").exists()) {
            o.put("unpack_skipped", true)
            return
        }
        initStep("unpack assets/unpack into $dataDir")
        val t = now()
        val counter = longArrayOf(0L, 0L)
        val ok = copyTree(ctx.assets, "unpack", dataDir, counter)
        o.put("unpack_ms", msSince(t))
        o.put("unpack_files", counter[0])
        o.put("unpack_bytes", counter[1])
        o.put("unpack_ok", ok)
        if (!ok) throw IllegalStateException("asset unpack failed")
        marker.parentFile?.mkdirs()
        marker.writeText(want)
    }

    private fun copyTree(am: AssetManager, from: String, to: String, counter: LongArray): Boolean {
        val names = am.list(from) ?: return false
        var ok = true
        for (n in names) {
            val sub = "$from/$n"
            val children = am.list(sub)
            if (children == null || children.isEmpty()) {
                File(to).mkdirs()
                am.open(sub).use { input ->
                    FileOutputStream(File(to, n)).use { out -> counter[1] += input.copyTo(out) }
                }
                counter[0]++
            } else {
                ok = copyTree(am, sub, "$to/$n", counter) && ok
            }
        }
        return ok
    }

    /** @return true when the engine is ready. Writes one line to inits.jsonl per process. */
    private fun initLok(): Boolean {
        outDir.mkdirs()
        val firstMarker = File(ctx.filesDir, "spike/first_init_done")
        val first = !firstMarker.exists()
        inProgress.writeText("<init>")
        val o = JSONObject()
        o.put("process_to_service_ms", serviceCreatedAt - Process.getStartElapsedRealtime())
        o.put("first_init_ever", first)
        o.put("sal_logging", salLogging)
        o.put("screen", "${screenW}x$screenH")
        o.put("xdpi", dm.xdpi.toDouble())
        o.put("pss_start_kb", pssKb())
        try {
            unpackAssets(o)
            val t0 = now()
            initStep("putenv (loads the 14 native libraries)")
            LibreOfficeKit.putenv("SAL_LOG=" + if (salLogging) "+WARN+INFO" else "-INFO-WARN")
            LibreOfficeKit.putenv("SAL_LOK_OPTIONS=compact_fonts")
            val tLoad = msSince(t0)
            initStep("LibreOfficeKit.init = initializeNative (asset unpack + LOK start)")
            LibreOfficeKit.init(ctx)
            o.put("libload_ms", tLoad)
            o.put("init_total_ms", msSince(t0))
            val t1 = now()
            initStep("Office create")
            val off = Office(LibreOfficeKit.getLibreOfficeKitHandle())
            off.setMessageCallback(officeCb)
            off.setOptionalFeatures(Document.LOK_FEATURE_DOCUMENT_PASSWORD)
            o.put("office_create_ms", msSince(t1))
            office = off
            o.put("pss_after_init_kb", pssKb())
            o.put("ok", true)
            firstMarker.parentFile?.mkdirs()
            firstMarker.writeText("1")
        } catch (t: Throwable) {
            o.put("ok", false)
            o.put("error", t.toString())
            log("INIT FAILED: $t")
        }
        initsFile.appendText(o.toString() + "\n")
        inProgress.delete()
        if (o.optBoolean("ok")) {
            log("engine up: libload ${o.optDouble("libload_ms")} ms, init total ${o.optDouble("init_total_ms")} ms, first_ever=$first")
        }
        return o.optBoolean("ok")
    }

    // ---------------------------------------------------------------- cold-start mode

    /** One cold start: init + load + first screen. The caller kills the process afterwards. */
    fun runCold(docName: String?): Boolean {
        val t0 = now()
        if (!initLok()) return false
        val files = (inDir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name.lowercase() }
        val f = files.firstOrNull { it.name == docName } ?: files.firstOrNull { it.name.endsWith(".docx") } ?: files.firstOrNull()
        val o = JSONObject()
        o.put("since_service_create_ms", SystemClock.elapsedRealtime() - serviceCreatedAt)
        if (f == null) {
            log("cold: no document in input folder")
            return false
        }
        o.put("doc", f.name)
        val tl = now()
        val doc = office!!.documentLoad(f.absolutePath)
        o.put("load_ms", msSince(tl))
        if (doc == null) {
            o.put("error", office!!.getError())
            coldFile.appendText(o.toString() + "\n")
            return false
        }
        doc.setMessageCallback(docCb)
        doc.initializeForRendering()
        val docW = doc.getDocumentWidth()
        val docH = doc.getDocumentHeight()
        val tt = now()
        paintScreen(doc, docW, docH)
        o.put("first_screen_ms", msSince(tt))
        o.put("engine_ready_to_first_screen_ms", msSince(t0))
        o.put("pss_kb", pssKb())
        coldFile.appendText(o.toString() + "\n")
        log("cold: " + o.toString())
        return true
    }

    // ---------------------------------------------------------------- full benchmark

    fun runBench(): Boolean {
        outDir.mkdirs(); pngDir.mkdirs(); convDir.mkdirs()
        // Crash recovery: a leftover marker means the previous process died inside that document.
        if (inProgress.exists()) {
            val name = inProgress.readText().trim()
            inProgress.delete()
            if (name == "<init>") {
                log("previous process crashed during engine init; see logcat. Aborting.")
                return false
            }
            val p = try { JSONObject(partialFile.readText()) } catch (e: Exception) { JSONObject() }
            p.put("doc", name)
            val wd = File(outDir, "watchdog.txt")
            if (wd.exists()) {
                p.put("status", "HANG")
                p.put("watchdog", wd.readText().trim())
                wd.delete()
            } else {
                p.put("status", "CRASH")
            }
            resultsFile.appendText(p.toString() + "\n")
            log("recovered: $name -> ${p.optString("status")} at step '${p.optString("lastStep")}'")
        }
        val done = HashSet<String>()
        if (resultsFile.exists()) {
            for (line in resultsFile.readLines()) {
                try { done.add(JSONObject(line).getString("doc")) } catch (e: Exception) { /* ignore */ }
            }
        }
        val docs = (inDir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name.lowercase() }
        val todo = docs.filter { it.name !in done }
        if (todo.isEmpty()) {
            log("nothing left to do (${docs.size} documents, all have results)")
            return true
        }
        if (!initLok()) return false
        var n = 0
        for (f in todo) {
            n++
            log("[$n/${todo.size}] ${f.name}")
            benchDoc(f)
        }
        return true
    }

    private fun benchDoc(f: File) {
        val name = f.name
        val r = JSONObject()
        r.put("doc", name)
        r.put("bytes", f.length())
        inProgress.writeText(name)
        callbackCounts.clear()

        fun step(s: String) {
            r.put("lastStep", s)
            partialFile.writeText(r.toString())
        }

        try {
            val off = office!!
            step("load")
            val tLoad = now()
            val doc = off.documentLoad(f.absolutePath)
            r.put("load_ms", msSince(tLoad))
            if (doc == null) {
                r.put("status", "LOAD_FAIL")
                r.put("error", off.getError())
                finishDoc(r)
                return
            }
            doc.setMessageCallback(docCb)
            step("initForRendering")
            val tIr = now()
            doc.initializeForRendering()
            r.put("initRender_ms", msSince(tIr))
            step("setPart0")
            doc.setPart(0)
            val type = doc.getDocumentType()
            r.put("type", type)
            r.put("pss_load_kb", pssKb())

            if (type == Document.DOCTYPE_PRESENTATION) {
                benchSlides(doc, r, ::step)
            } else {
                benchPages(doc, r, ::step)
            }

            // Export round-trips (time + size only; outputs are deleted).
            val exports = JSONObject()
            exportOne(doc, name, "pdf", exports, ::step)
            if (type == Document.DOCTYPE_TEXT) exportOne(doc, name, "docx", exports, ::step)
            if (type == Document.DOCTYPE_PRESENTATION) exportOne(doc, name, "pptx", exports, ::step)
            r.put("export", exports)
            r.put("pss_after_export_kb", pssKb())

            if (type == Document.DOCTYPE_TEXT) webLayoutProbe(doc, name, r, ::step)

            step("destroy")
            val tD = now()
            doc.destroy()
            r.put("destroy_ms", msSince(tD))
            r.put("pss_closed_kb", pssKb())
            r.put("status", "OK")
        } catch (t: Throwable) {
            r.put("status", "EXCEPTION")
            r.put("error", t.toString())
            log("exception in $name: $t")
        }
        finishDoc(r)
    }

    private fun finishDoc(r: JSONObject) {
        val cb = JSONObject()
        for ((k, v) in callbackCounts) cb.put(k.toString(), v.get())
        r.put("callbacks", cb)
        r.remove("lastStep")
        resultsFile.appendText(r.toString() + "\n")
        inProgress.delete()
        partialFile.delete()
        log("   -> ${r.optString("status")} load ${r.optDouble("load_ms")} ms")
    }

    // ---------------------------------------------------------------- Writer-like documents (tiles)

    /** Paints one screen at fit-width zoom with a single paintTile call; used for first-screen timing and PNG output. */
    private fun paintScreen(doc: Document, docW: Long, docH: Long): Pair<Int, Int> {
        val twW = max(1L, docW).toInt()
        var twH = max(1L, (docW.toDouble() * screenH / screenW).toLong())
        if (docH in 1 until twH) twH = docH
        val pxH = max(1, (screenW.toDouble() * twH / twW).roundToInt())
        ensureBig(screenW * pxH * 4)
        doc.paintTile(bigBuf, screenW, pxH, 0, 0, twW, twH.toInt())
        return Pair(screenW, pxH)
    }

    private fun tilePass(doc: Document, docW: Long, startY: Long, zoomMul: Double): List<Double> {
        val tw = max(1, (256.0 * docW / screenW / zoomMul).toInt())
        val cols = ceil(screenW / 256.0).toInt()
        val rows = ceil(screenH / 256.0).toInt()
        val out = ArrayList<Double>(cols * rows)
        for (rw in 0 until rows) {
            for (c in 0 until cols) {
                tileBuf.clear()
                val t = now()
                doc.paintTile(tileBuf, 256, 256, c * tw, (startY + rw.toLong() * tw).toInt(), tw, tw)
                out.add(msSince(t))
            }
        }
        return out
    }

    private fun oneTile(doc: Document, docW: Long, y: Long): Double {
        val tw = max(1, (256.0 * docW / screenW).toInt())
        tileBuf.clear()
        val t = now()
        doc.paintTile(tileBuf, 256, 256, 0, y.toInt(), tw, tw)
        return msSince(t)
    }

    private fun benchPages(doc: Document, r: JSONObject, step: (String) -> Unit) {
        val docW = doc.getDocumentWidth()
        val docH0 = doc.getDocumentHeight()
        r.put("docW_twip", docW)
        r.put("docH_twip", docH0)
        r.put("parts", doc.getParts())
        if (docW <= 0 || docH0 <= 0) {
            r.put("note", "document size is zero; skipping tile tests")
            return
        }
        val tw = max(1, (256.0 * docW / screenW).toInt())

        step("firstScreen")
        val tFs = now()
        val dim = paintScreen(doc, docW, docH0)
        r.put("firstScreen_ms", msSince(tFs))
        savePng(bigBuf, dim.first, dim.second, File(pngDir, safeBase(r.getString("doc")) + ".print.png"))

        step("tilePass1")
        val p1 = tilePass(doc, docW, 0, 1.0)
        r.put("tiles_fit_pass1", stats(p1))
        r.put("tile_first_ms", round1(p1.firstOrNull() ?: 0.0))
        r.put("pss_view_kb", pssKb())

        step("tilePass2")
        r.put("tiles_fit_pass2", stats(tilePass(doc, docW, 0, 1.0)))

        step("tilePassZoom2")
        r.put("tiles_zoom2x", stats(tilePass(doc, docW, 0, 2.0)))

        step("midTile")
        r.put("tile_mid_ms", round1(oneTile(doc, docW, docH0 / 2)))

        step("endTile")
        val docHBeforeEnd = doc.getDocumentHeight()
        r.put("tile_end_ms", round1(oneTile(doc, docW, max(0L, docH0 - tw))))
        val docH1 = doc.getDocumentHeight()
        r.put("docH_twip_after_end_jump", docH1)
        if (docH1 != docHBeforeEnd) r.put("note_layout", "height changed after jumping to the end (lazy layout)")
        if (docH1 > docH0) {
            step("endTile2")
            r.put("tile_end2_ms", round1(oneTile(doc, docW, max(0L, docH1 - tw))))
        }
        step("backToTop")
        r.put("tile_back_to_top_ms", round1(oneTile(doc, docW, 0)))
        r.put("pss_after_end_kb", pssKb())
    }

    private fun webLayoutProbe(doc: Document, name: String, r: JSONObject, step: (String) -> Unit) {
        val w = JSONObject()
        try {
            step("webProbe")
            w.put("before", "${doc.getDocumentWidth()}x${doc.getDocumentHeight()}")
            val t = now()
            doc.postUnoCommand(".uno:Web", "", false)
            w.put("uno_ms", msSince(t))
            w.put("after_immediate", "${doc.getDocumentWidth()}x${doc.getDocumentHeight()}")
            val dim0W = doc.getDocumentWidth()
            val dim0H = doc.getDocumentHeight()
            if (dim0W > 0 && dim0H > 0) {
                val dim = paintScreen(doc, dim0W, dim0H)
                savePng(bigBuf, dim.first, dim.second, File(pngDir, safeBase(name) + ".web.png"))
            }
            Thread.sleep(300)
            w.put("after_300ms", "${doc.getDocumentWidth()}x${doc.getDocumentHeight()}")
            doc.postUnoCommand(".uno:PrintLayout", "", false)
            w.put("back", "${doc.getDocumentWidth()}x${doc.getDocumentHeight()}")
        } catch (t: Throwable) {
            w.put("error", t.toString())
        }
        r.put("web_layout", w)
    }

    // ---------------------------------------------------------------- Impress-like documents (slides)

    private fun benchSlides(doc: Document, r: JSONObject, step: (String) -> Unit) {
        val parts = doc.getParts()
        r.put("parts", parts)
        val paint = ArrayList<Double>()
        val setPart = ArrayList<Double>()
        var firstSlideMs = 0.0
        for (i in 0 until parts) {
            step("slide$i")
            val ts = now()
            doc.setPart(i)
            setPart.add(msSince(ts))
            val w = doc.getDocumentWidth()
            val h = doc.getDocumentHeight()
            if (w <= 0 || h <= 0) continue
            val pxW = screenW
            val pxH = max(1, (pxW.toDouble() * h / w).roundToInt())
            ensureBig(pxW * pxH * 4)
            val tp = now()
            doc.paintTile(bigBuf, pxW, pxH, 0, 0, w.toInt(), h.toInt())
            val ms = msSince(tp)
            paint.add(ms)
            if (i == 0) {
                firstSlideMs = ms
                r.put("slide_size_twip", "${w}x$h")
                savePng(bigBuf, pxW, pxH, File(pngDir, safeBase(r.getString("doc")) + ".slide1.png"))
            }
            if (i == parts / 2 && parts > 2) {
                savePng(bigBuf, pxW, pxH, File(pngDir, safeBase(r.getString("doc")) + ".slide${i + 1}.png"))
            }
        }
        r.put("slide_first_ms", round1(firstSlideMs))
        r.put("slides_paint", stats(paint))
        r.put("slides_setPart", stats(setPart))
        // Second sweep: how fast is a revisit?
        step("slidesSweep2")
        val paint2 = ArrayList<Double>()
        for (i in 0 until parts) {
            doc.setPart(i)
            val w = doc.getDocumentWidth()
            val h = doc.getDocumentHeight()
            if (w <= 0 || h <= 0) continue
            val pxW = screenW
            val pxH = max(1, (pxW.toDouble() * h / w).roundToInt())
            ensureBig(pxW * pxH * 4)
            val tp = now()
            doc.paintTile(bigBuf, pxW, pxH, 0, 0, w.toInt(), h.toInt())
            paint2.add(msSince(tp))
        }
        r.put("slides_paint_sweep2", stats(paint2))
        r.put("pss_view_kb", pssKb())
    }

    // ---------------------------------------------------------------- export

    private fun exportOne(doc: Document, name: String, fmt: String, into: JSONObject, step: (String) -> Unit) {
        val o = JSONObject()
        try {
            step("export-$fmt")
            val out = File(convDir, safeBase(name) + ".rt." + fmt)
            out.delete()
            val t = now()
            doc.saveAs("file://" + out.absolutePath, fmt, "")
            o.put("ms", msSince(t))
            o.put("bytes", if (out.exists()) out.length() else -1L)
            val err = office!!.getError()
            if (err.isNotEmpty()) o.put("error", err)
            out.delete()
        } catch (t: Throwable) {
            o.put("exception", t.toString())
        }
        into.put(fmt, o)
    }

    private fun safeBase(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
