package com.rotfarmer.ereader.doc

import com.rotfarmer.ereader.data.Locator
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset

private fun u16(b: ByteArray, o: Int): Int =
    if (o + 2 > b.size) 0 else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

private fun u32(b: ByteArray, o: Int): Int =
    if (o + 4 > b.size) 0 else ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

/** A Palm database file (the container MOBI uses): a list of records. Pictures are served as "rec/N". */
class PdbFile(file: File) : ResourceSource {
    private val raf = RandomAccessFile(file, "r")
    private val length = raf.length()
    val type: String
    private val offsets: LongArray

    init {
        val head = ByteArray(78)
        raf.readFully(head)
        type = String(head, 60, 8, Charsets.ISO_8859_1)
        val n = u16(head, 76)
        val table = ByteArray(n * 8)
        raf.readFully(table)
        offsets = LongArray(n) { u32(table, it * 8).toLong() and 0xFFFFFFFFL }
    }

    val count: Int get() = offsets.size

    @Synchronized
    fun record(i: Int): ByteArray {
        if (i !in 0 until count) return ByteArray(0)
        val start = offsets[i]
        val end = if (i + 1 < count) offsets[i + 1] else length
        if (start < 0 || end > length || end <= start) return ByteArray(0)
        val b = ByteArray((end - start).toInt())
        raf.seek(start)
        raf.readFully(b)
        return b
    }

    override fun open(path: String): InputStream? {
        if (!path.startsWith("rec/")) return null
        val i = path.substring(4).toIntOrNull() ?: return null
        val r = record(i)
        return if (r.isEmpty()) null else ByteArrayInputStream(r)
    }

    override fun close() = raf.close()
}

/** Reads DRM-free Mobipocket (.mobi / .prc / .azw) and plain PalmDOC (.pdb) books. */
object MobiParser {

    class Meta(val title: String?, val author: String?, val cover: ByteArray?)

    private class Header(
        val compression: Int,
        val textLength: Int,
        val textRecords: Int,
        val encryption: Int,
        val isMobi: Boolean,
        val charset: Charset,
        val version: Int,
        val firstImage: Int,
        val extraFlags: Int,
        val huffRecord: Int,
        val huffCount: Int,
        val title: String?,
        val author: String?,
        val coverRecord: Int,
    )

    private fun header(pdb: PdbFile): Header {
        val r0 = pdb.record(0)
        if (r0.size < 16) throw IllegalArgumentException("This MOBI file is damaged.")
        val compression = u16(r0, 0)
        val textLength = u32(r0, 4)
        val textRecords = u16(r0, 8)
        val encryption = u16(r0, 12)
        val isMobi = r0.size >= 24 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        if (!isMobi) {
            return Header(compression, textLength, textRecords, encryption, false, Charset.forName("windows-1252"), 0, -1, 0, 0, 0, null, null, -1)
        }
        val mobiLen = u32(r0, 20)
        val charset = if (u32(r0, 28) == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        val version = u32(r0, 36)
        val nameOff = u32(r0, 84)
        val nameLen = u32(r0, 88)
        val firstImage = u32(r0, 108)
        val huffRecord = u32(r0, 112)
        val huffCount = u32(r0, 116)
        val exthFlags = u32(r0, 128)
        val extraFlags = if (mobiLen >= 0xE4 && r0.size >= 0xF4) u16(r0, 0xF2) else 0
        var title: String? = if (nameOff > 0 && nameLen > 0 && nameOff + nameLen <= r0.size) String(r0, nameOff, nameLen, charset).trim() else null
        var author: String? = null
        var cover = -1
        val exth = 16 + mobiLen
        if (exthFlags and 0x40 != 0 && exth + 12 <= r0.size && String(r0, exth, 4, Charsets.ISO_8859_1) == "EXTH") {
            val n = u32(r0, exth + 8)
            var p = exth + 12
            for (k in 0 until n) {
                if (p + 8 > r0.size) break
                val type = u32(r0, p)
                val len = u32(r0, p + 4)
                if (len < 8 || p + len > r0.size) break
                val data = r0.copyOfRange(p + 8, p + len)
                when (type) {
                    100 -> author = (author?.let { "$it, " } ?: "") + String(data, charset).trim()
                    503 -> String(data, charset).trim().takeIf { it.isNotEmpty() }?.let { title = it }
                    201 -> if (data.size >= 4 && firstImage > 0) cover = firstImage + u32(data, 0)
                }
                p += len
            }
        }
        return Header(compression, textLength, textRecords, encryption, true, charset, version, firstImage, extraFlags, huffRecord, huffCount, title, author, cover)
    }

    private fun check(h: Header) {
        if (h.encryption != 0) {
            throw IllegalArgumentException(
                "This book is copy-protected (DRM), so only Amazon's Kindle apps can open it. DRM-free MOBI files work."
            )
        }
        if (h.compression != 1 && h.compression != 2 && h.compression != 17480) {
            throw IllegalArgumentException("This MOBI file uses an unknown compression, so it can't be opened. Converting it to EPUB with Calibre will work.")
        }
    }

    fun readMeta(file: File): Meta = PdbFile(file).use { pdb ->
        val h = header(pdb)
        check(h)
        var cover: ByteArray? = if (h.coverRecord > 0) pdb.record(h.coverRecord).takeIf { it.isNotEmpty() } else null
        if (cover == null && h.firstImage > 0) {
            // No cover declared: use the first picture.
            for (i in h.firstImage until minOf(pdb.count, h.firstImage + 5)) {
                val r = pdb.record(i)
                if (isImage(r)) {
                    cover = r; break
                }
            }
        }
        Meta(h.title, h.author, cover)
    }

    private fun isImage(r: ByteArray): Boolean = r.size > 8 && (
        (r[0] == 0xFF.toByte() && r[1] == 0xD8.toByte()) ||
            (r[0] == 0x89.toByte() && r[1] == 'P'.code.toByte()) ||
            (r[0] == 'G'.code.toByte() && r[1] == 'I'.code.toByte()) ||
            (r[0] == 'B'.code.toByte() && r[1] == 'M'.code.toByte())
        )

    fun parse(file: File, fallbackTitle: String): FlowDocument {
        val pdb = PdbFile(file)
        try {
            val h = header(pdb)
            check(h)
            val raw = text(pdb, h)
            if (!h.isMobi) {
                // Plain PalmDOC: usually text, but old Mobipocket files keep HTML in it.
                val head = String(raw, 0, minOf(raw.size, 4096), h.charset)
                if (!head.contains("<html", ignoreCase = true) && !head.contains("<p", ignoreCase = true)) {
                    pdb.close()
                    return TextParser.parseString(String(raw, h.charset), h.title ?: fallbackTitle)
                }
            }
            val html = prepare(raw, h)
            val doc = Jsoup.parse(html)
            val css = CssLite()
            doc.select("style").forEach { css.parse(it.data()) }
            val out = DocBuilder()
            HtmlConverter(out, "", "", css).convert(doc.body())
            out.blocks.forEachIndexed { i, b ->
                if (b is TextBlock && b.kind == TextBlock.HEADING && b.level <= 2) {
                    out.toc += TocEntry(b.text.toString().replace('\n', ' ').trim().take(80), b.level, Locator(i, 0))
                }
            }
            return out.build(h.title ?: fallbackTitle, h.author ?: "", pdb)
        } catch (e: Throwable) {
            pdb.close()
            throw e
        }
    }

    /** Joins and decompresses the text records. */
    private fun text(pdb: PdbFile, h: Header): ByteArray {
        val out = ByteBuf(maxOf(h.textLength, 4096))
        val huff = if (h.compression == 17480) {
            if (h.huffCount <= 0 || h.huffRecord <= 0) throw IllegalArgumentException("This MOBI file is damaged (missing dictionary).")
            HuffCdic((h.huffRecord until h.huffRecord + h.huffCount).map { pdb.record(it) })
        } else null
        for (i in 1..h.textRecords) {
            if (i >= pdb.count) break
            val rec = pdb.record(i)
            val size = rec.size - trailingSize(rec, h.extraFlags)
            if (size <= 0) continue
            when {
                huff != null -> huff.unpack(rec, size, out)
                h.compression == 2 -> palmDoc(rec, size, out)
                else -> out.addAll(rec, size)
            }
        }
        return out.toArray(if (h.textLength > 0) minOf(h.textLength, out.size) else out.size)
    }

    /** Turns Mobipocket markup into ordinary HTML: link targets, page breaks and pictures. */
    private fun prepare(raw: ByteArray, h: Header): String {
        // Work on a one-char-per-byte string so positions are byte offsets, as filepos links are.
        var s = String(raw, Charsets.ISO_8859_1)
        val filepos = Regex("filepos\\s*=\\s*['\"]?0*(\\d+)['\"]?", RegexOption.IGNORE_CASE)
        val targets = filepos.findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 0..s.length }.toSortedSet()
        if (targets.isNotEmpty()) {
            val sb = StringBuilder(s)
            for (pos in targets.reversed()) {
                var p = pos
                val lt = s.lastIndexOf('<', p - 1)
                val gt = s.lastIndexOf('>', p - 1)
                if (lt > gt) p = lt // don't land inside a tag
                sb.insert(p, "<a id=\"filepos$pos\"></a>")
            }
            s = sb.toString()
            s = filepos.replace(s) { "href=\"#filepos${it.groupValues[1]}\"" }
        }
        // Old-style pictures: <img recindex="00012">.
        s = Regex("recindex\\s*=\\s*['\"]?0*(\\d+)['\"]?", RegexOption.IGNORE_CASE).replace(s) {
            val n = it.groupValues[1].toIntOrNull() ?: 1
            "src=\"rec/${h.firstImage + n - 1}\""
        }
        // KF8 pictures: kindle:embed:XXXX (base-32 index).
        s = Regex("kindle:embed:([0-9A-Va-v]{4})(\\?[^\"')\\s]*)?").replace(s) {
            "rec/${h.firstImage + base32(it.groupValues[1]) - 1}"
        }
        // Metadata blocks in old files' <head> would otherwise show up as text.
        s = Regex("<(metadata|dc-metadata|x-metadata|guide)\\b.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s, "")
        return String(s.toByteArray(Charsets.ISO_8859_1), h.charset)
    }

    private fun base32(v: String): Int {
        var n = 0
        for (c in v.uppercase()) n = n * 32 + if (c in '0'..'9') c - '0' else c - 'A' + 10
        return n
    }

    /** Size of the extra data some MOBI writers append to each text record. */
    private fun trailingSize(d: ByteArray, flags: Int): Int {
        var num = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) num += backwardVarint(d, d.size - num)
            f = f shr 1
        }
        if (flags and 1 != 0) {
            val i = d.size - num - 1
            if (i >= 0) num += (d[i].toInt() and 3) + 1
        }
        return num.coerceIn(0, d.size)
    }

    private fun backwardVarint(d: ByteArray, end: Int): Int {
        var size = end
        var bit = 0
        var result = 0
        if (size <= 0) return 0
        while (true) {
            val v = d[size - 1].toInt() and 0xFF
            result = result or ((v and 0x7F) shl bit)
            bit += 7
            size--
            if (v and 0x80 != 0 || bit >= 28 || size == 0) return result
        }
    }

    /** PalmDOC LZ77 decompression. */
    private fun palmDoc(src: ByteArray, len: Int, out: ByteBuf) {
        var i = 0
        while (i < len) {
            val c = src[i++].toInt() and 0xFF
            when {
                c == 0 || c in 9..0x7F -> out.add(c)
                c in 1..8 -> {
                    var k = 0
                    while (k < c && i < len) {
                        out.add(src[i++].toInt() and 0xFF); k++
                    }
                }
                c in 0x80..0xBF -> {
                    if (i >= len) return
                    val pair = ((c shl 8) or (src[i++].toInt() and 0xFF)) and 0x3FFF
                    val dist = pair shr 3
                    val n = (pair and 7) + 3
                    repeat(n) {
                        val p = out.size - dist
                        out.add(if (dist > 0 && p >= 0) out[p] else 0x20)
                    }
                }
                else -> {
                    out.add(0x20); out.add(c xor 0x80)
                }
            }
        }
    }

    /** Amazon's HUFF/CDIC dictionary compression (same algorithm as Calibre and KindleUnpack). */
    private class HuffCdic(records: List<ByteArray>) {
        private val d1Len = IntArray(256)
        private val d1Term = BooleanArray(256)
        private val d1Max = LongArray(256)
        private val minCode = LongArray(33)
        private val maxCode = LongArray(33)
        private val dict = ArrayList<ByteArray>()
        private val ready = ArrayList<Boolean>()

        init {
            val h = records.firstOrNull() ?: throw IllegalArgumentException("Missing HUFF record")
            if (h.size < 24 || String(h, 0, 4, Charsets.ISO_8859_1) != "HUFF") throw IllegalArgumentException("This MOBI file is damaged (bad HUFF record).")
            val off1 = u32(h, 8)
            val off2 = u32(h, 12)
            for (i in 0 until 256) {
                val v = u32(h, off1 + i * 4).toLong() and MASK
                val len = (v and 0x1F).toInt()
                d1Len[i] = len
                d1Term[i] = (v and 0x80L) != 0L
                d1Max[i] = (((v ushr 8) + 1) shl (32 - len)) - 1
            }
            minCode[0] = 0
            maxCode[0] = (1L shl 32) - 1
            for (cl in 1..32) {
                val mn = u32(h, off2 + (cl - 1) * 8).toLong() and MASK
                val mx = u32(h, off2 + (cl - 1) * 8 + 4).toLong() and MASK
                minCode[cl] = mn shl (32 - cl)
                maxCode[cl] = ((mx + 1) shl (32 - cl)) - 1
            }
            for (r in records.drop(1)) loadCdic(r)
        }

        private fun loadCdic(c: ByteArray) {
            if (c.size < 16 || String(c, 0, 4, Charsets.ISO_8859_1) != "CDIC") return
            val phrases = u32(c, 8)
            val bits = u32(c, 12)
            val n = minOf(1 shl bits.coerceIn(0, 30), phrases - dict.size)
            for (i in 0 until n) {
                val off = u16(c, 16 + i * 2)
                val blen = u16(c, 16 + off)
                val start = 18 + off
                val end = minOf(c.size, start + (blen and 0x7FFF))
                dict += if (start < end) c.copyOfRange(start, end) else ByteArray(0)
                ready += (blen and 0x8000) != 0
            }
        }

        private fun q(d: ByteArray, pos: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or ((d.getOrNull(pos + k)?.toLong() ?: 0L) and 0xFF)
            return v
        }

        fun unpack(src: ByteArray, len: Int, out: ByteBuf) {
            var bitsLeft = len.toLong() * 8
            var pos = 0
            var x = q(src, pos)
            var n = 32
            while (true) {
                if (n <= 0) {
                    pos += 4
                    x = q(src, pos)
                    n += 32
                }
                val code = (x ushr n) and MASK
                val top = (code ushr 24).toInt()
                var cl = d1Len[top]
                var mx = d1Max[top]
                if (!d1Term[top]) {
                    while (cl < 32 && code < minCode[cl]) cl++
                    mx = maxCode[cl]
                }
                if (cl <= 0) return
                n -= cl
                bitsLeft -= cl
                if (bitsLeft < 0) return
                val r = ((mx - code) ushr (32 - cl)).toInt()
                if (r !in dict.indices) return
                if (!ready[r]) {
                    // Phrases can themselves be compressed; expand once and remember.
                    val inner = ByteBuf(64)
                    ready[r] = true
                    unpack(dict[r], dict[r].size, inner)
                    dict[r] = inner.toArray(inner.size)
                }
                out.addAll(dict[r], dict[r].size)
            }
        }

        companion object {
            const val MASK = 0xFFFFFFFFL
        }
    }

    private class ByteBuf(cap: Int) {
        private var buf = ByteArray(cap)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = v.toByte()
        }

        fun addAll(b: ByteArray, n: Int) {
            while (size + n > buf.size) buf = buf.copyOf(buf.size * 2)
            System.arraycopy(b, 0, buf, size, n)
            size += n
        }

        operator fun get(i: Int): Int = buf[i].toInt() and 0xFF
        fun toArray(n: Int): ByteArray = buf.copyOf(n)
    }
}
