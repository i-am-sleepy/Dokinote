package app.dokinote.office.lok

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors

/**
 * Hosts LibreOfficeKit in the `:lok` process (see manifest). All LOK calls run on [worker], the one engine thread.
 * The UI process talks to it through a [Messenger]; results are persisted to files in the shared app storage.
 */
class LokBenchService : Service() {
    private val createdAt = SystemClock.elapsedRealtime()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> onMessage(msg); true })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun onMessage(msg: Message) {
        when (msg.what) {
            LokProtocol.MSG_RUN -> {
                val data = Bundle(msg.data)
                val reply = msg.replyTo
                worker.execute { runJob(data, reply) }
            }
            LokProtocol.MSG_KILL -> {
                // Killing the process is the only way to unload LibreOffice (docs/DECISIONS.md).
                main.postDelayed({ Process.killProcess(Process.myPid()) }, 150)
            }
        }
    }

    private fun send(to: Messenger?, what: Int, b: Bundle) {
        if (to == null) return
        try {
            val m = Message.obtain(null, what)
            m.data = b
            to.send(m)
        } catch (e: DeadObjectException) {
            // UI went away; results are on disk anyway.
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun runJob(data: Bundle, reply: Messenger?) {
        val mode = data.getString(LokProtocol.KEY_MODE) ?: LokProtocol.MODE_BENCH
        val log: (String) -> Unit = { text ->
            val b = Bundle()
            b.putString(LokProtocol.KEY_TEXT, text)
            send(reply, LokProtocol.MSG_LOG, b)
        }
        var ok = false
        var err = ""
        try {
            val bench = LokBench(
                ctx = this,
                inDir = File(data.getString(LokProtocol.KEY_IN) ?: ""),
                outDir = File(data.getString(LokProtocol.KEY_OUT) ?: ""),
                salLogging = data.getBoolean(LokProtocol.KEY_SAL, false),
                serviceCreatedAt = createdAt,
                log = log,
            )
            ok = when (mode) {
                LokProtocol.MODE_COLD -> bench.runCold(data.getString(LokProtocol.KEY_DOC))
                LokProtocol.MODE_TYPING -> bench.runTyping()
                else -> bench.runBench()
            }
        } catch (t: Throwable) {
            err = t.toString()
            log("job failed: $err")
        }
        val b = Bundle()
        b.putString(LokProtocol.KEY_MODE, mode)
        b.putBoolean(LokProtocol.KEY_OK, ok)
        b.putString(LokProtocol.KEY_TEXT, err)
        send(reply, LokProtocol.MSG_DONE, b)
    }
}
