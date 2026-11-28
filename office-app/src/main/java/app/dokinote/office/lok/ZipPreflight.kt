package app.dokinote.office.lok

import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** What [ZipPreflight.prepare] found, and the file that should be handed to LibreOffice. */
class PreflightResult(
    val file: File,
    val isZip: Boolean,
    val badEntries: Int,
    val repaired: Boolean,
    val note: String,
)

/**
 * LibreOffice refuses OOXML/ODF files whose zip entries carry a wrong CRC-32: desktop 24.2 reports "source file could
 * not be loaded" within 0.6 s, LibreOfficeKit on Android blocks in documentLoad (P0 spike, 2026-11-28). Word opens
 * such files. This pass checks every entry and, only when a checksum is wrong, writes a copy with correct checksums
 * (same entry order, same stored/deflated method, content untouched) for the engine to load instead.
 */
object ZipPreflight {
    fun prepare(src: File, workDir: File): PreflightResult {
        val zip = try {
            ZipFile(src)
        } catch (e: ZipException) {
            return PreflightResult(src, false, 0, false, "not a zip")
        } catch (e: Exception) {
            return PreflightResult(src, false, 0, false, "open failed: $e")
        }
        zip.use { z ->
            val entries = ArrayList<ZipEntry>()
            val en = z.entries()
            while (en.hasMoreElements()) entries.add(en.nextElement())
            val buf = ByteArray(64 * 1024)

            var bad = 0
            for (e in entries) {
                if (e.isDirectory) continue
                val declared = e.crc
                val crc = CRC32()
                z.getInputStream(e).use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        crc.update(buf, 0, n)
                    }
                }
                if (declared != -1L && crc.value != declared) bad++
            }
            if (bad == 0) return PreflightResult(src, true, 0, false, "ok")

            workDir.mkdirs()
            val out = File(workDir, "repaired_" + src.name)
            ZipOutputStream(FileOutputStream(out).buffered()).use { zos ->
                for (e in entries) {
                    val ne = ZipEntry(e.name)
                    if (e.time != -1L) ne.time = e.time
                    if (e.isDirectory) {
                        zos.putNextEntry(ne)
                        zos.closeEntry()
                        continue
                    }
                    if (e.method == ZipEntry.STORED) {
                        val crc = CRC32()
                        var size = 0L
                        z.getInputStream(e).use { ins ->
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                crc.update(buf, 0, n)
                                size += n
                            }
                        }
                        ne.method = ZipEntry.STORED
                        ne.size = size
                        ne.compressedSize = size
                        ne.crc = crc.value
                    } else {
                        ne.method = ZipEntry.DEFLATED
                    }
                    zos.putNextEntry(ne)
                    z.getInputStream(e).use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            zos.write(buf, 0, n)
                        }
                    }
                    zos.closeEntry()
                }
            }
            return PreflightResult(out, true, bad, true, "repaired $bad entries")
        }
    }
}
