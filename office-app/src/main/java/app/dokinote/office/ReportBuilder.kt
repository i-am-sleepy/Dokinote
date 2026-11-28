package app.dokinote.office

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File

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
        sb.append("inputs: ${inDir.listFiles()?.count { it.isFile } ?: 0} documents\n\n")

        // ---- engine starts
        val inits = lines(File(outDir, "inits.jsonl"))
        sb.append("== ENGINE STARTS (one per :lok process) ==\n")
        if (inits.isEmpty()) sb.append("(none)\n")
        for ((i, o) in inits.withIndex()) {
            sb.append("#${i + 1} ok=${o.optBoolean("ok")} first_ever=${o.optBoolean("first_init_ever")} sal=${o.optBoolean("sal_logging")} ")
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
        sb.append("\n== DOCUMENTS: ${res.size} results, $okN ok, $crashN crashed the engine, ${res.size - okN - crashN} other failures ==\n")
        sb.append("units: ms unless noted; tile passes cover one screen at fit-width zoom (256 px tiles); 'zoom2x' = twice that zoom.\n")
        for (o in res) {
            val status = o.optString("status")
            val kb = o.optLong("bytes") / 1024
            val type = when (o.optInt("type", -1)) { 0 -> "text"; 1 -> "sheet"; 2 -> "slides"; 3 -> "draw"; else -> "?" }
            sb.append("\n[$status] ${o.optString("doc")}  ($type, $kb KB)\n")
            if (status == "CRASH") {
                sb.append("  engine died at step '${o.optString("lastStep")}'; known so far: load ${num(o, "load_ms")}\n")
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
        return sb.toString()
    }
}
