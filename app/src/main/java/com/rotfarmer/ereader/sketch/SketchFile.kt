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
import java.util.zip.CRC32
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
 * A picture, or a page of a document, placed on a sketchbook page under the drawing. [asset] names
 * the stored file (its SHA-256 and extension); [sourcePage] is the document page (0 for pictures).
 * Position and size are in page units. [time] is the last change (for merging); [deleted] is when
 * it was removed, or 0.
 */
data class SketchItem(
    val id: Long,
    val page: Int,
    val kind: Int,
    val asset: String,
    val sourcePage: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val created: Long,
    val time: Long,
    val deleted: Long = 0L,
) {
    companion object {
        const val PICTURE = 0
        const val DOCUMENT = 1
    }
}

/**
 * The `.sketchbook` file: the whole sketchbook in one zip, used both for "Send a copy" and as the
 * shared file that devices keep in sync.
 *
 * Strokes never change once drawn; erasing a stroke records its id as deleted. So merging two
 * copies is just: every stroke either side has, minus every stroke either side deleted. Placed
 * pictures can move, so the most recent change to each wins, and a removed one stays removed.
 * Nothing is lost when two devices draw at the same time, and the copies always end up the same.
 *
 * Plain Java streams only (no Android classes), so it can be tested on any JVM.
 */
object SketchFile {
    /** Page height for a width of 1 (A4 / ISO paper), the same on every device. */
    const val ASPECT = 1.41421356f
    const val MAX_PAGES = 10000

    private const val MAGIC = "E-Reader sketchbook"
    /** 1: drawing only. 2: adds placed pictures and documents (only written when there are any). */
    private const val VERSION = 2
    private const val META = "sketchbook"
    private const val DELETED = "deleted"
    private const val STROKES = "strokes"
    private const val ITEMS = "items"
    private const val ASSET_PREFIX = "assets/"
    private const val MAX_POINTS = 200_000
    private val ASSET_NAME = Regex("[0-9a-f]{64}\\.(pdf|jpg|png|webp)")

    fun isAssetName(name: String) = ASSET_NAME.matches(name)

    /**
     * Writes a complete sketchbook. [strokes] and [deleted] (id, time) are streamed, not held in
     * memory. [items] includes removed ones (so other devices learn of the removal); [assets]
     * hands over each stored file the remaining items use, by name.
     */
    fun write(
        out: OutputStream,
        meta: SketchMeta,
        deleted: (emit: (Long, Long) -> Unit) -> Unit,
        strokes: (emit: (SketchStrokeRecord) -> Unit) -> Unit,
        items: List<SketchItem> = emptyList(),
        assets: List<Pair<String, () -> InputStream>> = emptyList(),
    ) {
        // Never close the caller's stream; a plain FilterOutputStream would write byte by byte.
        val keepOpen = object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = flush()
        }
        ZipOutputStream(BufferedOutputStream(keepOpen, 1 shl 16)).use { zip ->
            zip.putNextEntry(ZipEntry(META))
            DataOutputStream(zip).run {
                // Sketchbooks without pictures stay readable by older versions of the app.
                writeUTF(MAGIC); writeInt(if (items.isEmpty()) 1 else VERSION)
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

            if (items.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(ITEMS))
                DataOutputStream(zip).run {
                    for (it in items) {
                        if (it.id == 0L) continue
                        writeLong(it.id); writeInt(it.page); writeInt(it.kind); writeUTF(it.asset); writeInt(it.sourcePage)
                        writeFloat(it.x); writeFloat(it.y); writeFloat(it.w); writeFloat(it.h)
                        writeLong(it.created); writeLong(it.time); writeLong(it.deleted)
                    }
                    writeLong(0L); flush()
                }
                zip.closeEntry()
            }

            // Pictures and PDFs are already compressed: store them as they are.
            // Stored entries need their size and checksum first: read each file once for those,
            // then again to copy it, so a large PDF is never held in memory.
            val buf = ByteArray(1 shl 16)
            for ((name, open) in assets) {
                if (!isAssetName(name)) continue
                val crc = CRC32()
                var size = 0L
                open().use { input -> while (true) { val n = input.read(buf); if (n < 0) break; crc.update(buf, 0, n); size += n } }
                zip.putNextEntry(ZipEntry(ASSET_PREFIX + name).apply {
                    method = ZipEntry.STORED; this.size = size; compressedSize = size; this.crc = crc.value
                })
                open().use { input -> while (true) { val n = input.read(buf); if (n < 0) break; zip.write(buf, 0, n) } }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a sketchbook, handing over its parts as they are read: the settings first, then
     * deleted stroke ids, strokes, placed items and stored files. [onAsset] must read the stream it
     * is given (or not) before returning. Throws [IOException] if this isn't a sketchbook file.
     */
    fun read(
        input: InputStream,
        onMeta: (SketchMeta) -> Unit,
        onDeleted: (Long, Long) -> Unit,
        onStroke: (SketchStrokeRecord) -> Unit,
        onItem: (SketchItem) -> Unit = {},
        onAsset: (String, InputStream) -> Unit = { _, _ -> },
    ) {
        val keepOpen = object : FilterInputStream(input) { override fun close() {} }
        ZipInputStream(BufferedInputStream(keepOpen, 1 shl 16)).use { zip ->
            var meta: SketchMeta? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                val data = DataInputStream(zip)
                val name = entry.name
                if (name != META && meta == null) throw IOException("Not a sketchbook")
                when {
                    name == META -> meta = readMeta(data).also(onMeta)
                    name == DELETED -> while (true) {
                        val id = data.readLong(); if (id == 0L) break
                        onDeleted(id, data.readLong())
                    }
                    name == STROKES -> {
                        val pages = MAX_PAGES
                        while (true) {
                            val id = data.readLong(); if (id == 0L) break
                            val page = data.readInt(); val tool = data.readInt(); val color = data.readInt()
                            val width = data.readFloat(); val created = data.readLong(); val n = data.readInt()
                            if (n < 1 || n > MAX_POINTS || page !in 0 until pages || !width.isFinite() || width <= 0f) throw IOException("Damaged sketchbook")
                            val pts = FloatArray(n * 3) { data.readFloat() }
                            if (pts.any { !it.isFinite() }) throw IOException("Damaged sketchbook")
                            onStroke(SketchStrokeRecord(id, page, tool, color, width, created, pts))
                        }
                    }
                    name == ITEMS -> {
                        val pages = MAX_PAGES
                        while (true) {
                            val id = data.readLong(); if (id == 0L) break
                            val item = SketchItem(id, data.readInt(), data.readInt(), data.readUTF(), data.readInt(),
                                data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat(),
                                data.readLong(), data.readLong(), data.readLong())
                            val sizes = listOf(item.x, item.y, item.w, item.h)
                            if (item.page !in 0 until pages || !isAssetName(item.asset) || item.sourcePage < 0 ||
                                sizes.any { !it.isFinite() } || item.w <= 0f || item.h <= 0f) throw IOException("Damaged sketchbook")
                            onItem(item)
                        }
                    }
                    name.startsWith(ASSET_PREFIX) -> {
                        val asset = name.removePrefix(ASSET_PREFIX)
                        if (isAssetName(asset)) onAsset(asset, keepOpenStream(zip))
                    }
                }
                zip.closeEntry()
            }
            if (meta == null) throw IOException("Not a sketchbook")
        }
    }

    private fun keepOpenStream(input: InputStream): InputStream = object : FilterInputStream(input) { override fun close() {} }

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
            d.readFloat() // page shape; always ASPECT so far
            val created = d.readLong()
            val meta = SketchMeta(id, title, titleTime, paper, paperTime, pages, created)
            if (meta.id.isBlank() || meta.pages !in 1..MAX_PAGES) throw IOException("Damaged sketchbook")
            return meta
        } catch (e: java.io.EOFException) {
            throw IOException("Not a sketchbook")
        }
    }
}

/** How two copies of a sketchbook combine. */
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

    /**
     * One placed item seen on both sides. A removal is final (whichever side removed it, the
     * earliest removal is kept, so both sides agree); otherwise the more recent position wins
     * (ties keep [local]).
     */
    fun item(local: SketchItem, remote: SketchItem): SketchItem = when {
        local.deleted > 0 && remote.deleted > 0 -> if (remote.deleted < local.deleted) remote else local
        local.deleted > 0 -> local
        remote.deleted > 0 -> remote
        remote.time > local.time -> remote
        else -> local
    }

    /** True if [a] and [b] say the same thing (for removed items, only when they were removed matters). */
    fun sameItem(a: SketchItem, b: SketchItem): Boolean = if (a.deleted > 0 || b.deleted > 0) a.deleted == b.deleted else a == b
}
