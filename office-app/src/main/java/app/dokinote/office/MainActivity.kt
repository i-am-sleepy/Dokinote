package app.dokinote.office

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Office track, P0 spike UI: import sample documents, run the LibreOfficeKit benchmark in the :lok process, show and
 * export a text report. See docs/tracks/OFFICE.md and status/office.md.
 */
class MainActivity : ComponentActivity() {
    private lateinit var controller: SpikeController

    private val logText = mutableStateOf("")
    private val busy = mutableStateOf(false)
    private val live = mutableStateOf("")
    private val inCount = mutableStateOf(0)
    private val report = mutableStateOf("")
    private val pngs = mutableStateOf<List<File>>(emptyList())

    private fun appendLog(s: String) {
        val lines = (logText.value + s + "\n").lines()
        logText.value = lines.takeLast(40).joinToString("\n")
    }

    private fun refreshReport() {
        val text = ReportBuilder.build(this, controller.outDir, controller.inDir)
        report.value = text
        try {
            controller.root.mkdirs()
            File(controller.root, "report.txt").writeText(text)
        } catch (e: Exception) {
            appendLog("could not write report.txt: $e")
        }
        val dir = File(controller.outDir, "png")
        pngs.value = dir.listFiles()?.filter { it.extension == "png" }?.sortedBy { it.name } ?: emptyList()
    }

    private fun importFolder(uri: Uri) {
        busy.value = true
        appendLog("importing documents...")
        Thread {
            val n = try {
                controller.importTree(uri) { }
            } catch (e: Exception) {
                runOnUiThread { appendLog("import failed: $e") }
                0
            }
            runOnUiThread {
                inCount.value = n
                busy.value = false
                appendLog("imported $n documents")
            }
        }.start()
    }

    private fun copyReport() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("dokinote-spike-report", report.value))
        appendLog("report copied to clipboard (${report.value.length} chars)")
    }

    private fun shareReport() {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, report.value)
        startActivity(Intent.createChooser(i, "Share spike report"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = SpikeController(
            app = applicationContext,
            onLog = { appendLog(it) },
            onBusy = { busy.value = it },
            onFinished = { refreshReport() },
            onLive = { live.value = it },
        )
        inCount.value = controller.inputCount()
        refreshReport()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var sal by remember { mutableStateOf(false) }
                    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        if (uri != null) importFolder(uri)
                    }
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Dokinote Office - P0 engine spike", style = MaterialTheme.typography.titleLarge)
                        Text("Documents imported: ${inCount.value}${if (busy.value) "   (busy...)" else ""}")
                        Button(onClick = { picker.launch(null) }, enabled = !busy.value) { Text("1. Pick the sample folder") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = sal, onCheckedChange = { sal = it })
                            Text("Viewer-like LOK logging (SAL_LOG=+WARN+INFO), read when you press Run")
                        }
                        Button(
                            onClick = { controller.startBench(sal) },
                            enabled = !busy.value && inCount.value > 0,
                        ) { Text("2. Run benchmark") }
                        Button(
                            onClick = { controller.startCold(3, sal) },
                            enabled = !busy.value && inCount.value > 0,
                        ) { Text("3. Cold-start test (3 runs)") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { refreshReport() }) { Text("Refresh report") }
                            OutlinedButton(onClick = { copyReport() }) { Text("Copy") }
                            OutlinedButton(onClick = { shareReport() }) { Text("Share") }
                        }
                        OutlinedButton(
                            onClick = { controller.resetResults(); refreshReport() },
                            enabled = !busy.value,
                        ) { Text("Clear results") }
                        if (live.value.isNotEmpty()) Text(live.value, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text(logText.value, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        if (pngs.value.isNotEmpty()) {
                            Text("Rendered first screens (check fidelity by eye):")
                            LazyRow(Modifier.height(260.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(pngs.value) { f ->
                                    val bmp = remember(f.path) {
                                        val o = BitmapFactory.Options()
                                        o.inSampleSize = 2
                                        BitmapFactory.decodeFile(f.path, o)
                                    }
                                    Column {
                                        if (bmp != null) {
                                            Image(bitmap = bmp.asImageBitmap(), contentDescription = f.name, modifier = Modifier.height(240.dp))
                                        }
                                        Text(f.name, fontSize = 9.sp)
                                    }
                                }
                            }
                        }
                        SelectionContainer {
                            Text(report.value, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
