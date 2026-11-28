package app.dokinote.office

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns the JSON-lines files written by the engine process into one compact text report that can be pasted into chat. */
object ReportBuilder {
    private fun lines(f: File): List<JSONObject> {
        if (!f.exists()) return emptyList()
        val out = ArrayList<JSONObject>()
        for (l in f.readLines()) {
            try { out.add(JSONObject(l)) } catch (e: Exception) { /* skip */ }
        }
        return out
    }

    private fun num(o: JSONObject?, k: String): String {
        if (o == null || !o.has(k)) return "-"
        val d = o.optDouble(k, Double.NaN)
        if (d.isNaN()) return o.optString(k, "-")
        return if (d == Math.rint(d) && Math.abs(d) < 1e9) d.toLong().toString() else "%.1f".format(d)
    }

    private fun mb(o: JSONObject, k: String): String = if (o.has(k)) (o.optInt(k) / 1024).toString() else "-"

    private fun st(o: JSONObject?): String {
        if (o == null || !o.has("n")) return "-"
        return "n${o.optInt("n")} p50 ${num(o, "p50")} p90 ${num(o, "p90")} max ${num(o, "max")} sum ${num(o, "sum")}"
    }

    private val exitNames = mapOf(
        0 to "UNKNOWN", 1 to "EXIT_SELF", 2 to "SIGNALED", 3 to "LOW_MEMORY", 4 to "CRASH", 5 to "CRASH_NATIVE",
        6 to "ANR", 7 to "INITIALIZATION_FAILURE", 8 to "PERMISSION_CHANGE", 9 to "EXCESSIVE_RESOURCE_USAGE",
        10 to "USER_REQUESTED", 11 to "USER_STOPPED", 12 to "DEPENDENCY_DIED", 13 to "OTHER", 14 to "FREEZER",
        15 to "PACKAGE_STATE_CHANGE", 16 to "PACKAGE_UPDATED",
    )

    private fun tail(f: File, n: Int): String =
        if (!f.exists()) "(none)\n" else f.readLines().takeLast(n).joinToString("\n") + "\n"

    private fun readCapped(ins: InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < cap) {
            val n = ins.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun printableRuns(b: ByteArray): List<String> {
        val out = LinkedHashSet<String>()
        val cur = StringBuilder()
        for (x in b) {
            val c = x.toInt() and 0xff
            if (c in 32..126) {
                cur.append(c.toChar())
            } else {
                if (cur.length >= 6) out.add(cur.toString())
                cur.setLength(0)
            }
        }
        if (cur.length >= 6) out.add(cur.toString())
        return out.toList()
    }

    /** The tombstone is a protobuf; without a parser we list its printable strings (signal, abort message, frames, libraries). */
    private fun summarizeTombstone(b: ByteArray): String {
        val runs = printableRuns(b)
        val keys = listOf("SIG", "abort", "Abort", "Fatal", "assert", "Assert", "terminate", "xception", "lo-native", "liblo", "Java_", "std::", "sal_", "osl_", "lok_")
        val hot = runs.filter { r -> keys.any { r.contains(it) } }.map { it.take(180) }.distinct().take(45)
        val rest = runs.filter { it.length >= 10 && (it.contains(".so") || it.contains("::")) }.map { it.take(150) }.filter { it !in hot }.distinct().take(25)
        val sb = StringBuilder()
        sb.append("  tombstone ${b.size} bytes; key strings:\n")
        for (h in hot) sb.append("    ").append(h).append('\n')
        if (rest.isNotEmpty()) {
            sb.append("  other symbols/libs:\n")
            for (r in rest) sb.append("    ").append(r).append('\n')
        }
        return sb.toString()
    }

    private fun exits(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 30) return "(needs Android 11+)\n"
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val list: List<ApplicationExitInfo> = try {
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
        } catch (e: Exception) {
            return "(unavailable: $e)\n"
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        var traces = 0
        for (i in list) {
            sb.append("${fmt.format(Date(i.timestamp))} ${i.processName} ${exitNames[i.reason] ?: i.reason.toString()} ")
            sb.append("status=${i.status} pss=${i.pss}KB rss=${i.rss}KB desc=${i.description}\n")
            if (i.reason == 5 && traces < 2) {
                traces++
                try {
                    val ins = i.traceInputStream
                    if (ins != null) {
                        val bytes = ins.use { readCapped(it, 800_000) }
                        sb.append(summarizeTombstone(bytes))
                    } else {
                        sb.append("  (no trace stream)\n")
                    }
                } catch (e: Exception) {
                    sb.append("  (trace unreadable: $e)\n")
                }
            }
        }
        if (sb.isEmpty()) sb.append("(none recorded)\n")
        return sb.toString()
    }

    /** Own-uid logcat: warnings/errors/fatals plus anything from LibreOffice or this app. */
    private fun logcat(): String {
        return try {
            val p = ProcessBuilder("logcat", "-d", "-t", "800").redirectErrorStream(true).start()
            val lines = p.inputStream.bufferedReader().readLines()
            p.destroy()
            val prio = Regex("^\\d\\d-\\d\\d\\s+[\\d:.]+\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s")
            val keep = lines.filter { l ->
                val pr = prio.find(l)?.groupValues?.get(1) ?: ""
                pr == "W" || pr == "E" || pr == "F" || l.contains("LibreOffice") || l.contains("dokinote", true) || l.contains("lo-bootstrap")
            }.map { it.take(220) }.takeLast(120)
            val text = keep.joinToString("\n")
            (if (text.length > 14_000) text.takeLast(14_000) else text) + "\n"
        } catch (e: Exception) {
            "(logcat not available: $e)\n"
        }
    }

    fun build(ctx: Context, outDir: File, inDir: File): String {
        val sb = StringBuilder()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val dm = ctx.resources.displayMetrics
        sb.append("DOKINOTE OFFICE P0 SPIKE REPORT\n")
        sb.append("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ")
        sb.append("abi ${Build.SUPPORTED_ABIS.joinToString(",")}, cores ${Runtime.getRuntime().availableProcessors()}, ")
        sb.append("ram ${mi.totalMem / 1048576} MB, page ${Os.sysconf(OsConstants._SC_PAGESIZE)} B\n")
        sb.append("screen ${dm.widthPixels}x${dm.heightPixels} px, xdpi ${"%.0f".format(dm.xdpi)}\n")
        sb.append("engine: LibreOffice Viewer 26.2.7.3 (versionCode 135) libs, own process :lok, build ${BuildInfo.VERSION}\n")
        sb.append("inputs: ${inDir.listFiles()?.count { it.isFile } ?: 0} documents\n")
        val ip = File(outDir, "inprogress.txt")
        val stp = File(outDir, "initstep.txt")
        val part = File(outDir, "partial.json")
        sb.append("state: inprogress marker = ${if (ip.exists()) ip.readText().trim() else "none"}; last init step = ${if (stp.exists()) stp.readText().trim() else "-"}")
        if (part.exists()) sb.append("; partial lastStep = ${try { JSONObject(part.readText()).optString("lastStep") } catch (e: Exception) { "?" }}")
        sb.append("\n\n== APP LOG (UI side, last 40 lines) ==\n")
        sb.append(tail(File(outDir, "log.txt"), 40))
        sb.append("\n")

        // ---- engine starts
        val inits = lines(File(outDir, "inits.jsonl"))
        sb.append("== ENGINE STARTS (one per :lok process) ==\n")
        if (inits.isEmpty()) sb.append("(none)\n")
        for ((i, o) in inits.withIndex()) {
            sb.append("#${i + 1} ok=${o.optBoolean("ok")} first_ever=${o.optBoolean("first_init_ever")} sal=${o.optBoolean("sal_logging")} ")
            sb.append(if (o.optBoolean("unpack_skipped")) "unpack skipped (already done), " else "unpack ${num(o, "unpack_ms")} ms (${o.optLong("unpack_files")} files, ${o.optLong("unpack_bytes") / 1024} KB), ")
            sb.append("proc->service ${num(o, "process_to_service_ms")} ms, libload ${num(o, "libload_ms")}, init total ${num(o, "init_total_ms")}, ")
            sb.append("office create ${num(o, "office_create_ms")}, pss start/after-init ${mb(o, "pss_start_kb")}/${mb(o, "pss_after_init_kb")} MB")
            if (o.has("error")) sb.append("  ERROR ${o.optString("error")}")
            sb.append("\n")
        }

        // ---- cold starts
        val cold = lines(File(outDir, "cold.jsonl"))
        val e2e = File(outDir, "cold_e2e.txt").let { if (it.exists()) it.readLines() else emptyList() }
        sb.append("\n== COLD START TEST (kill process, restart, load 1 doc, paint first screen) ==\n")
        if (cold.isEmpty()) sb.append("(not run)\n")
        for ((i, o) in cold.withIndex()) {
            sb.append("run ${i + 1}: doc ${o.optString("doc")} load ${num(o, "load_ms")} ms, first screen ${num(o, "first_screen_ms")} ms, ")
            sb.append("service->first screen ${num(o, "engine_ready_to_first_screen_ms")} ms (incl. init), pss ${mb(o, "pss_kb")} MB")
            if (o.has("error")) sb.append("  ERROR ${o.optString("error")}")
            sb.append("\n")
        }
        for (l in e2e) sb.append("  ui-side: $l\n")

        // ---- documents
        val res = lines(File(outDir, "results.jsonl"))
        val okN = res.count { it.optString("status") == "OK" }
        val crashN = res.count { it.optString("status") == "CRASH" }
        val hangN = res.count { it.optString("status") == "HANG" }
        sb.append("\n== DOCUMENTS: ${res.size} results, $okN ok, $crashN crashed the engine, $hangN hung (watchdog ${SpikeController.STEP_TIMEOUT_S}s per step), ${res.size - okN - crashN - hangN} other failures ==\n")
        sb.append("units: ms unless noted; tile passes cover one screen at fit-width zoom (256 px tiles); 'zoom2x' = twice that zoom.\n")
        for (o in res) {
            val status = o.optString("status")
            val kb = o.optLong("bytes") / 1024
            val type = when (o.optInt("type", -1)) { 0 -> "text"; 1 -> "sheet"; 2 -> "slides"; 3 -> "draw"; else -> "?" }
            sb.append("\n[$status] ${o.optString("doc")}  ($type, $kb KB)\n")
            if (status == "CRASH" || status == "HANG") {
                sb.append("  engine ${if (status == "HANG") "was killed by the watchdog" else "died"} at step '${o.optString("lastStep")}'; known so far: load ${num(o, "load_ms")} initRender ${num(o, "initRender_ms")} firstScreen ${num(o, "firstScreen_ms")}")
                if (o.has("watchdog")) sb.append(" (${o.optString("watchdog")})")
                sb.append("\n")
                continue
            }
            if (status == "LOAD_FAIL" || status == "EXCEPTION") {
                sb.append("  ${o.optString("error")}\n")
                continue
            }
            sb.append("  load ${num(o, "load_ms")} | initRender ${num(o, "initRender_ms")} | parts ${num(o, "parts")}")
            if (o.has("firstScreen_ms")) sb.append(" | first screen ${num(o, "firstScreen_ms")} | first tile ${num(o, "tile_first_ms")}")
            sb.append("\n")
            if (o.has("tiles_fit_pass1")) {
                sb.append("  tiles pass1: ${st(o.optJSONObject("tiles_fit_pass1"))}\n")
                sb.append("  tiles pass2: ${st(o.optJSONObject("tiles_fit_pass2"))}\n")
                sb.append("  tiles zoom2x: ${st(o.optJSONObject("tiles_zoom2x"))}\n")
                sb.append("  mid ${num(o, "tile_mid_ms")} | end ${num(o, "tile_end_ms")} | end2 ${num(o, "tile_end2_ms")} | back-to-top ${num(o, "tile_back_to_top_ms")}")
                sb.append(" | docH twip ${num(o, "docH_twip")} -> ${num(o, "docH_twip_after_end_jump")} (W ${num(o, "docW_twip")})\n")
            }
            if (o.has("slides_paint")) {
                sb.append("  slide size ${o.optString("slide_size_twip")} | first slide ${num(o, "slide_first_ms")}\n")
                sb.append("  slides sweep1 paint: ${st(o.optJSONObject("slides_paint"))}\n")
                sb.append("  slides sweep2 paint: ${st(o.optJSONObject("slides_paint_sweep2"))}\n")
                sb.append("  setPart: ${st(o.optJSONObject("slides_setPart"))}\n")
            }
            sb.append("  pss MB: load ${mb(o, "pss_load_kb")} view ${mb(o, "pss_view_kb")} end ${mb(o, "pss_after_end_kb")} export ${mb(o, "pss_after_export_kb")} closed ${mb(o, "pss_closed_kb")} | destroy ${num(o, "destroy_ms")} ms\n")
            val ex = o.optJSONObject("export")
            if (ex != null) {
                sb.append("  export:")
                for (fmt in listOf("pdf", "docx", "pptx")) {
                    val e = ex.optJSONObject(fmt) ?: continue
                    sb.append(" $fmt ${num(e, "ms")} ms ${e.optLong("bytes") / 1024} KB")
                    if (e.has("error")) sb.append(" ERR(${e.optString("error")})")
                    if (e.has("exception")) sb.append(" EXC(${e.optString("exception")})")
                    sb.append(" |")
                }
                sb.append("\n")
            }
            val w = o.optJSONObject("web_layout")
            if (w != null) {
                sb.append("  web layout (.uno:Web): before ${w.optString("before")} -> now ${w.optString("after_immediate")} -> 300ms ${w.optString("after_300ms")} -> back ${w.optString("back")} (uno ${num(w, "uno_ms")} ms)")
                if (w.has("error")) sb.append(" ERR ${w.optString("error")}")
                sb.append("\n")
            }
            val cb = o.optJSONObject("callbacks")
            if (cb != null && cb.length() > 0) sb.append("  callbacks (type:count) $cb\n")
        }
        sb.append("\n== PROCESS EXITS (Android's own record of how each process of this app ended) ==\n")
        sb.append(exits(ctx))
        sb.append("\n== LOGCAT (own uid: warnings, errors, LibreOffice and app lines) ==\n")
        sb.append(logcat())
        return sb.toString()
    }
}
