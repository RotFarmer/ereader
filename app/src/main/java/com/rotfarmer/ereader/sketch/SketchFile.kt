package com.rotfarmer.ereader.sketch

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A sketchbook's settings. Each setting carries the time it was last changed, so devices can merge. */
data class SketchMeta(
    val id: String,
    val title: String,
    val titleTime: Long,
    val paper: Int,
    val paperTime: Long,
    val pages: Int,
    val created: Long,
)

/** One stroke, in page units: x from 0 to 1 across the page, y from 0 to [SketchFile.ASPECT] down it. */
class SketchStrokeRecord(
    val id: Long,
    val page: Int,
    val tool: Int,
    val color: Int,
    val width: Float,
    val created: Long,
    val pts: FloatArray,
)

/**
 * The `.sketchbook` file: the whole sketchbook in one zip, used both for "Send a copy" and as the
 * shared file that devices keep in sync.
 *
 * Strokes never change once drawn; erasing a stroke records its id as deleted. So merging two
 * copies is just: every stroke either side has, minus every stroke either side deleted. Nothing
 * is lost when two devices draw at the same time, and the copies always end up the same.
 *
 * Plain Java streams only (no Android classes), so it can be tested on any JVM.
 */
object SketchFile {
    /** Page height for a width of 1 (A4 / ISO paper), the same on every device. */
    const val ASPECT = 1.41421356f
    const val MAX_PAGES = 10000

    private const val MAGIC = "E-Reader sketchbook"
    private const val VERSION = 1
    private const val META = "sketchbook"
    private const val DELETED = "deleted"
    private const val STROKES = "strokes"
    private const val MAX_POINTS = 200_000

    /** Writes a complete sketchbook. [strokes] and [deleted] (id, time) are streamed, not held in memory. */
    fun write(out: OutputStream, meta: SketchMeta, deleted: (emit: (Long, Long) -> Unit) -> Unit, strokes: (emit: (SketchStrokeRecord) -> Unit) -> Unit) {
        // Never close the caller's stream; a plain FilterOutputStream would write byte by byte.
        val keepOpen = object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = flush()
        }
        ZipOutputStream(BufferedOutputStream(keepOpen, 1 shl 16)).use { zip ->
            zip.putNextEntry(ZipEntry(META))
            DataOutputStream(zip).run {
                writeUTF(MAGIC); writeInt(VERSION)
                writeUTF(meta.id); writeUTF(meta.title); writeLong(meta.titleTime)
                writeInt(meta.paper); writeLong(meta.paperTime); writeInt(meta.pages)
                writeFloat(ASPECT); writeLong(meta.created)
                flush()
            }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(DELETED))
            DataOutputStream(zip).run {
                deleted { id, time -> if (id != 0L) { writeLong(id); writeLong(time) } }
                writeLong(0L); flush()
            }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(STROKES))
            DataOutputStream(zip).run {
                strokes { s ->
                    if (s.id == 0L) return@strokes
                    writeLong(s.id); writeInt(s.page); writeInt(s.tool); writeInt(s.color)
                    writeFloat(s.width); writeLong(s.created)
                    val n = s.pts.size / 3
                    writeInt(n)
                    for (i in 0 until n * 3) writeFloat(s.pts[i])
                }
                writeLong(0L); flush()
            }
            zip.closeEntry()
        }
    }

    /**
     * Reads a sketchbook, handing over its parts as they are read: the settings first, then
     * deleted stroke ids, then strokes. Throws [IOException] if this isn't a sketchbook file.
     */
    fun read(input: InputStream, onMeta: (SketchMeta) -> Unit, onDeleted: (Long, Long) -> Unit, onStroke: (SketchStrokeRecord) -> Unit) {
        val keepOpen = object : FilterInputStream(input) { override fun close() {} }
        ZipInputStream(BufferedInputStream(keepOpen, 1 shl 16)).use { zip ->
            var meta: SketchMeta? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                val data = DataInputStream(zip)
                when (entry.name) {
                    META -> meta = readMeta(data).also(onMeta)
                    DELETED -> {
                        if (meta == null) throw IOException("Not a sketchbook")
                        while (true) {
                            val id = data.readLong(); if (id == 0L) break
                            onDeleted(id, data.readLong())
                        }
                    }
                    STROKES -> {
                        val m = meta ?: throw IOException("Not a sketchbook")
                        while (true) {
                            val id = data.readLong(); if (id == 0L) break
                            val page = data.readInt(); val tool = data.readInt(); val color = data.readInt()
                            val width = data.readFloat(); val created = data.readLong(); val n = data.readInt()
                            if (n < 1 || n > MAX_POINTS || page !in 0 until maxOf(m.pages, 1) || !width.isFinite() || width <= 0f) throw IOException("Damaged sketchbook")
                            val pts = FloatArray(n * 3) { data.readFloat() }
                            if (pts.any { !it.isFinite() }) throw IOException("Damaged sketchbook")
                            onStroke(SketchStrokeRecord(id, page, tool, color, width, created, pts))
                        }
                    }
                }
                zip.closeEntry()
            }
            if (meta == null) throw IOException("Not a sketchbook")
        }
    }

    /** Just the settings (title, id), for deciding what to do with a file before importing it. */
    fun readMeta(input: InputStream): SketchMeta {
        val keepOpen = object : FilterInputStream(input) { override fun close() {} }
        ZipInputStream(BufferedInputStream(keepOpen)).use { zip ->
            val entry = zip.nextEntry ?: throw IOException("Not a sketchbook")
            if (entry.name != META) throw IOException("Not a sketchbook")
            return readMeta(DataInputStream(zip))
        }
    }

    /** True if [head] (the first bytes of a file) looks like a sketchbook. */
    fun looksLikeSketchbook(head: ByteArray): Boolean {
        // A zip whose first entry is named "sketchbook": local header, name length at 26, name at 30.
        if (head.size < 30 + META.length || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte() || head[2] != 3.toByte() || head[3] != 4.toByte()) return false
        val nameLen = (head[26].toInt() and 0xFF) or ((head[27].toInt() and 0xFF) shl 8)
        return nameLen == META.length && String(head, 30, nameLen, Charsets.US_ASCII) == META
    }

    private fun readMeta(d: DataInputStream): SketchMeta {
        try {
            if (d.readUTF() != MAGIC) throw IOException("Not a sketchbook")
            val version = d.readInt()
            if (version > VERSION) throw IOException("This sketchbook was made by a newer version of E-Reader. Update the app to open it.")
            val id = d.readUTF(); val title = d.readUTF(); val titleTime = d.readLong()
            val paper = d.readInt(); val paperTime = d.readLong(); val pages = d.readInt()
            d.readFloat() // page shape; always ASPECT in version 1
            val created = d.readLong()
            val meta = SketchMeta(id, title, titleTime, paper, paperTime, pages, created)
            if (meta.id.isBlank() || meta.pages !in 1..MAX_PAGES) throw IOException("Damaged sketchbook")
            return meta
        } catch (e: java.io.EOFException) {
            throw IOException("Not a sketchbook")
        }
    }
}

/** How two copies of a sketchbook's settings combine. */
object SketchMerge {
    /** The newer title and paper win (ties keep [local]); the page count only grows. */
    fun meta(local: SketchMeta, remote: SketchMeta): SketchMeta = local.copy(
        title = if (remote.titleTime > local.titleTime) remote.title else local.title,
        titleTime = maxOf(local.titleTime, remote.titleTime),
        paper = if (remote.paperTime > local.paperTime) remote.paper else local.paper,
        paperTime = maxOf(local.paperTime, remote.paperTime),
        pages = maxOf(local.pages, remote.pages).coerceAtMost(SketchFile.MAX_PAGES),
        created = minOf(local.created, remote.created),
    )
}
