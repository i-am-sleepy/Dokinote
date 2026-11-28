package app.dokinote.office

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.provider.DocumentsContract
import app.dokinote.office.lok.LokBenchService
import app.dokinote.office.lok.LokProtocol
import org.json.JSONObject
import java.io.File

/**
 * UI-process side of the P0 spike. Talks to the `:lok` engine process through a Messenger, restarts it when the native
 * code crashes (the engine journals its progress, so the crashing document is recorded and skipped), and imports the
 * sample documents from a folder picked with the system picker. Never loads any LibreOffice class itself.
 */
class SpikeController(
    private val app: Context,
    private val onLog: (String) -> Unit,
    private val onBusy: (Boolean) -> Unit,
    private val onFinished: () -> Unit,
    private val onLive: (String) -> Unit = {},
) {
    val root = File(app.filesDir, "spike")
    val inDir = File(root, "in")
    val outDir = File(root, "out")

    private val main = Handler(Looper.getMainLooper())
    private val reply = Messenger(Handler(Looper.getMainLooper()) { m -> onReply(m); true })
    private var conn: ServiceConnection? = null
    private var service: Messenger? = null
    private var active = false
    private var restarts = 0
    private var job: Bundle? = null
    private var bindAt = 0L
    private var coldLeft = 0
    private var coldIndex = 0
    private var sal = false
    private var watchdogFired = false
    private var graceUntil = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            checkLive()
            main.postDelayed(this, 3000)
        }
    }

    /** Shows what the engine is doing right now and kills it when one step runs longer than [STEP_TIMEOUT_S]. */
    private fun checkLive() {
        if (SystemClock.elapsedRealtime() < graceUntil) return
        val ip = File(outDir, "inprogress.txt")
        if (!ip.exists()) {
            onLive("engine: between documents")
            return
        }
        val doc = try { ip.readText().trim() } catch (e: Exception) { "?" }
        val stepFile = if (doc == "<init>") File(outDir, "initstep.txt") else File(outDir, "partial.json")
        var step = "?"
        try {
            step = if (doc == "<init>") stepFile.readText().trim() else JSONObject(stepFile.readText()).optString("lastStep")
        } catch (e: Exception) {
            // file being rewritten
        }
        val base = if (stepFile.exists()) stepFile else ip
        val ageS = (System.currentTimeMillis() - base.lastModified()) / 1000
        onLive("now: $doc | step: $step | ${ageS}s in this step")
        val limit = if (step.startsWith("export")) EXPORT_TIMEOUT_S else STEP_TIMEOUT_S
        if (ageS > limit && !watchdogFired && service != null) {
            watchdogFired = true
            try { File(outDir, "watchdog.txt").writeText("step=$step secs=$ageS limit=$limit") } catch (e: Exception) { /* ignore */ }
            log("WATCHDOG: step '$step' of $doc ran over ${limit}s - killing the engine process")
            try { service?.send(Message.obtain(null, LokProtocol.MSG_KILL)) } catch (e: Exception) { /* ignore */ }
        }
    }

    // ------------------------------------------------------------ public actions

    fun startBench(salLogging: Boolean) {
        if (active) return
        sal = salLogging
        restarts = 0
        job = jobBundle(LokProtocol.MODE_BENCH, null)
        log("starting benchmark (SAL logging ${if (salLogging) "on" else "off"})")
        begin()
    }

    fun startCold(times: Int, salLogging: Boolean) {
        if (active) return
        sal = salLogging
        coldLeft = times
        coldIndex = 0
        nextCold()
    }

    fun resetResults() {
        outDir.deleteRecursively()
        log("results cleared")
    }

    // ------------------------------------------------------------ import

    /** Adds the supported documents below [tree] to the private input folder (existing ones with the same name and size are kept). Call from a background thread. */
    fun importTree(tree: Uri, progress: (String) -> Unit): Int {
        inDir.mkdirs()
        val cr = app.contentResolver
        var added = 0
        fun walk(docId: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val rows = ArrayList<DocRow>()
            cr.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    rows.add(DocRow(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", size))
                }
            }
            for (row in rows) {
                if (row.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(row.id)
                } else if (isSupported(row.name)) {
                    val safe = row.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val existing = File(inDir, safe)
                    if (existing.exists() && row.size >= 0 && existing.length() == row.size) continue
                    val src = DocumentsContract.buildDocumentUriUsingTree(tree, row.id)
                    val dest = uniqueDest(row.name)
                    cr.openInputStream(src)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    added++
                    progress("imported $added: ${dest.name}")
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree))
        return inputCount()
    }

    fun clearInputs() {
        inDir.deleteRecursively()
        log("imported documents cleared")
    }

    private class DocRow(val id: String, val name: String, val mime: String, val size: Long)

    fun inputCount(): Int = inDir.listFiles()?.count { it.isFile } ?: 0

    private fun isSupported(name: String): Boolean {
        val n = name.lowercase()
        return listOf(".docx", ".doc", ".dotx", ".odt", ".rtf", ".pptx", ".ppt", ".odp").any { n.endsWith(it) }
    }

    private fun uniqueDest(name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var f = File(inDir, safe)
        var i = 1
        while (f.exists()) {
            f = File(inDir, "${i}_$safe")
            i++
        }
        return f
    }

    // ------------------------------------------------------------ engine process plumbing

    private fun jobBundle(mode: String, doc: String?): Bundle {
        val b = Bundle()
        b.putString(LokProtocol.KEY_MODE, mode)
        b.putString(LokProtocol.KEY_IN, inDir.absolutePath)
        b.putString(LokProtocol.KEY_OUT, outDir.absolutePath)
        b.putBoolean(LokProtocol.KEY_SAL, sal)
        if (doc != null) b.putString(LokProtocol.KEY_DOC, doc)
        return b
    }

    private fun begin() {
        active = true
        watchdogFired = false
        graceUntil = SystemClock.elapsedRealtime() + 10_000
        main.removeCallbacks(tick)
        main.postDelayed(tick, 3000)
        onBusy(true)
        bindAt = SystemClock.elapsedRealtime()
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) return
                service = Messenger(binder)
                sendJob()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                if (active) onEngineDied()
            }
        }
        conn = c
        val ok = app.bindService(Intent(app, LokBenchService::class.java), c, Context.BIND_AUTO_CREATE)
        if (!ok) {
            log("bindService failed")
            finish()
        }
    }

    private fun sendJob() {
        val m = Message.obtain(null, LokProtocol.MSG_RUN)
        val j = job
        m.data = if (j != null) Bundle(j) else Bundle()
        m.replyTo = reply
        try {
            service?.send(m)
        } catch (e: Exception) {
            log("send failed: $e")
        }
    }

    private fun onEngineDied() {
        unbindQuiet()
        val mode = job?.getString(LokProtocol.KEY_MODE)
        if (mode == LokProtocol.MODE_BENCH) {
            restarts++
            if (restarts > 40) {
                log("engine process died too many times, giving up")
                finish()
            } else {
                log(if (watchdogFired) "engine stopped by the watchdog - restarting, attempt $restarts" else "engine process died (native crash?) - restarting, attempt $restarts")
                main.postDelayed({ begin() }, 600)
            }
        } else {
            log("engine process died during the cold-start test")
            coldLeft = 0
            finish()
        }
    }

    private fun onReply(m: Message) {
        when (m.what) {
            LokProtocol.MSG_LOG -> log(m.data.getString(LokProtocol.KEY_TEXT) ?: "")
            LokProtocol.MSG_DONE -> {
                val mode = m.data.getString(LokProtocol.KEY_MODE)
                val ok = m.data.getBoolean(LokProtocol.KEY_OK)
                val e2e = SystemClock.elapsedRealtime() - bindAt
                active = false
                if (mode == LokProtocol.MODE_COLD) {
                    outDir.mkdirs()
                    File(outDir, "cold_e2e.txt").appendText("run=${coldIndex} ok=$ok e2e_ms=$e2e\n")
                    log("cold run $coldIndex: ok=$ok, bind-to-first-screen $e2e ms")
                } else if (!ok) {
                    log("benchmark stopped early: ${m.data.getString(LokProtocol.KEY_TEXT) ?: ""}")
                } else {
                    log("benchmark finished")
                }
                killEngine()
                if (mode == LokProtocol.MODE_COLD && coldLeft > 0 && ok) {
                    main.postDelayed({ nextCold() }, 2500)
                } else {
                    coldLeft = 0
                    finish()
                }
            }
        }
    }

    private fun nextCold() {
        coldIndex++
        coldLeft--
        // Median-sized .docx among the documents that are not known to hang or crash the engine.
        val bad = HashSet<String>()
        val rf = File(outDir, "results.jsonl")
        if (rf.exists()) {
            for (l in rf.readLines()) {
                try {
                    val o = JSONObject(l)
                    if (o.optString("status") != "OK") bad.add(o.optString("doc"))
                } catch (e: Exception) {
                    // skip
                }
            }
        }
        val docx = inDir.listFiles()
            ?.filter { it.isFile && it.name.lowercase().endsWith(".docx") && it.name !in bad }
            ?.sortedBy { it.length() }
        val prefer = docx?.getOrNull(docx.size / 2)
        job = jobBundle(LokProtocol.MODE_COLD, prefer?.name)
        log("cold-start run $coldIndex")
        begin()
    }

    /** Asks the engine process to exit (unloads LibreOffice), then drops the binding. */
    private fun killEngine() {
        try {
            service?.send(Message.obtain(null, LokProtocol.MSG_KILL))
        } catch (e: Exception) {
            // already gone
        }
        main.postDelayed({ unbindQuiet() }, 400)
    }

    private fun unbindQuiet() {
        val c = conn ?: return
        conn = null
        try {
            app.unbindService(c)
        } catch (e: IllegalArgumentException) {
            // not bound
        }
    }

    private fun finish() {
        active = false
        main.removeCallbacks(tick)
        onLive("")
        onBusy(false)
        onFinished()
    }

    private fun log(s: String) {
        onLog(s)
        try {
            outDir.mkdirs()
            val f = File(outDir, "log.txt")
            if (f.exists() && f.length() > 200_000) f.delete()
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText("$ts $s\n")
        } catch (e: Exception) {
            // logging must never break the run
        }
    }

    companion object {
        /** One engine step (load, one tile pass, one export...) longer than this is treated as a hang. */
        const val STEP_TIMEOUT_S = 120L

        /** Exports (save as docx/pptx, PDF) get a longer leash. */
        const val EXPORT_TIMEOUT_S = 240L
    }
}
