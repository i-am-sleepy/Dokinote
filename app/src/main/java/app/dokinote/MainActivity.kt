package app.dokinote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else {
                if (dark) darkColorScheme() else lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { SpikeScreen() }
            }
        }
    }
}

@Composable
fun SpikeScreen() {
    val ctx = LocalContext.current
    val lines = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var needle by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(Mode.NONE) }

    fun log(s: String) { lines.add(s) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val mode = pending
        if (uri != null && mode != Mode.NONE && !busy) {
            busy = true
            val app = ctx.applicationContext
            val word = needle
            thread(name = "spike-$mode") {
                try {
                    runSpike(app, uri, mode, word, ::log)
                } catch (t: Throwable) {
                    log("!! $mode failed: ${t::class.java.simpleName}: ${t.message}")
                } finally {
                    busy = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val app = ctx.applicationContext
        thread(name = "spike-env") {
            try { envReport(app, ::log) } catch (t: Throwable) { log("!! env failed: $t") }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Dokinote engine spike", style = MaterialTheme.typography.titleLarge)
        Text("On-device MuPDF checks. This app has no network permission.", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            pending = Mode.BENCH; picker.launch(arrayOf("*/*"))
        }) { Text("1. Benchmark: pick a PDF") }
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            pending = Mode.OFFICE; picker.launch(arrayOf("*/*"))
        }) { Text("2. Open + convert to PDF: pick docx/xlsx/pptx/epub/cbz...") }
        OutlinedTextField(
            value = needle, onValueChange = { needle = it },
            label = { Text("Word to redact (for test 3)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            pending = Mode.REDACT; picker.launch(arrayOf("*/*"))
        }) { Text("3. Redaction test: pick a PDF") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("report", lines.joinToString("\n")))
            }) { Text("Copy report") }
            OutlinedButton(enabled = !busy, onClick = { lines.clear() }) { Text("Clear") }
            if (busy) CircularProgressIndicator(Modifier.padding(4.dp))
        }
        Surface(Modifier.weight(1f).fillMaxWidth(), tonalElevation = 2.dp) {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                    Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}
