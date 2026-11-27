package app.dokinote.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Turns a content Uri into a local file MuPDF can open (kept in the cache dir, reused while unchanged). */
object DocSource {
    class Local(val file: File, val displayName: String)

    private const val CACHE_LIMIT = 1_500_000_000L

    fun toLocal(ctx: Context, uri: Uri): Local {
        if (uri.scheme == "file") {
            val f = File(requireNotNull(uri.path))
            return Local(f, f.name)
        }
        var name = "document"
        var size = -1L
        var modified = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                val m = c.getColumnIndex("last_modified")
                if (m >= 0 && !c.isNull(m)) modified = c.getLong(m)
            }
        }
        val dir = File(ctx.cacheDir, "docs").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val tag = Hash.sha1Hex(uri.toString()).take(10)
        val f = File(dir, "${tag}_$safe") // keeps the extension: MuPDF picks the handler from it
        val meta = File(dir, "$tag.meta")
        val metaStr = "$size:$modified"
        val cacheable = size >= 0 || modified >= 0
        val reuse = cacheable && f.exists() && meta.exists() && meta.readText() == metaStr && (size < 0 || f.length() == size)
        if (!reuse) {
            val tmp = File(dir, "$tag.tmp")
            val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot read this file")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            f.delete()
            check(tmp.renameTo(f)) { "Cannot cache file" }
            meta.writeText(metaStr)
        }
        f.setLastModified(System.currentTimeMillis())
        trim(dir, f)
        return Local(f, name)
    }

    private fun trim(dir: File, keep: File) {
        val files = dir.listFiles { x -> x.isFile && !x.name.endsWith(".meta") && !x.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        for (x in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_LIMIT) break
            if (x == keep) continue
            total -= x.length()
            x.delete()
            File(dir, x.name.substringBefore('_') + ".meta").delete()
        }
    }
}
