package com.rotfarmer.ereader.sketch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.SizeF
import com.rotfarmer.ereader.data.BookFormat
import com.rotfarmer.ereader.data.ImportException
import com.rotfarmer.ereader.data.Importer
import com.rotfarmer.ereader.doc.DocxParser
import com.rotfarmer.ereader.doc.EpubParser
import com.rotfarmer.ereader.doc.FlowDocument
import com.rotfarmer.ereader.doc.MobiParser
import com.rotfarmer.ereader.doc.PdfDoc
import com.rotfarmer.ereader.doc.TextParser
import com.rotfarmer.ereader.layout.FlowEngine
import com.rotfarmer.ereader.layout.ImageItem
import com.rotfarmer.ereader.layout.LayoutSpec
import com.rotfarmer.ereader.render.Fonts
import com.rotfarmer.ereader.render.ImageCache
import com.rotfarmer.ereader.render.PageRenderer
import com.rotfarmer.ereader.render.ReaderColors
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.max

/** Pictures and documents brought into a sketchbook: storing them, and drawing them on a page. */
object SketchAssets {
    /** Longest side a stored picture keeps (plenty for a page, and keeps shared files small). */
    private const val MAX_PICTURE = 2400
    private const val MAX_DOCUMENT_PAGES = 2000

    /** A file ready to place. */
    sealed class Prepared {
        class Picture(val asset: String, val width: Int, val height: Int) : Prepared()
        class Document(val asset: String, val title: String, val pageSizes: List<SizeF>) : Prepared()
    }

    /**
     * Stores a picture or document the user picked. Pictures are turned upright and made no
     * bigger than they need to be; PDFs are kept as they are; EPUB, MOBI, Word and text files are
     * laid out into pages (with the reader's own layout) and kept as a PDF.
     */
    fun prepare(context: Context, uri: Uri, cancel: () -> Boolean, progress: (String) -> Unit): Prepared {
        val store = SketchStore.of(context)
        val name = SketchSync.displayName(context.contentResolver, uri) ?: "Document"
        val tmp = File.createTempFile("import-", ".tmp", context.cacheDir)
        try {
            (context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open that file")).use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            if (cancel()) throw CancellationException()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) return picture(store, tmp)

            val format = try { Importer.detect(tmp, name) } catch (e: ImportException) {
                throw IOException("Choose a picture, or a PDF, EPUB, MOBI, Word (.docx) or text file.")
            }
            val title = name.substringBeforeLast('.').ifBlank { name }
            val pdf: File = if (format == BookFormat.PDF) tmp else {
                progress("Laying out the pages…")
                val doc = when (format) {
                    BookFormat.EPUB -> EpubParser.parse(tmp, title)
                    BookFormat.MOBI -> MobiParser.parse(tmp, title)
                    BookFormat.DOCX -> DocxParser.parse(tmp, title)
                    BookFormat.HTML -> TextParser.parseHtml(tmp, title)
                    else -> TextParser.parseText(tmp, title)
                }
                File.createTempFile("layout-", ".pdf", context.cacheDir).also { out ->
                    try { layout(context, doc, out, cancel, progress) } catch (e: Throwable) { out.delete(); throw e }
                }
            }
            try {
                val sizes = try { PdfDoc(pdf).use { d -> (0 until d.pageCount).map { d.pageSize(it) } } }
                catch (e: IllegalArgumentException) { throw IOException(e.message ?: "This PDF can't be opened") }
                if (sizes.isEmpty()) throw IOException("That document has no pages")
                return Prepared.Document(store(store, pdf, "pdf"), title, sizes)
            } finally { if (pdf != tmp) pdf.delete() }
        } finally { tmp.delete() }
    }

    private fun picture(store: SketchStore, file: File): Prepared.Picture {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val scale = minOf(1f, MAX_PICTURE.toFloat() / max(w, h))
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        }
        try {
            val alpha = bmp.hasAlpha()
            val out = File.createTempFile("picture-", ".tmp", file.parentFile)
            try {
                out.outputStream().use { if (!bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it)) throw IOException("Couldn't store the picture") }
                return Prepared.Picture(store(store, out, if (alpha) "png" else "jpg"), bmp.width, bmp.height)
            } finally { out.delete() }
        } finally { bmp.recycle() }
    }

    /** Copies [file] into the store under its SHA-256, so the same file is only ever kept once. */
    private fun store(store: SketchStore, file: File, ext: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        val name = digest.digest().joinToString("") { "%02x".format(it) } + "." + ext
        val dest = store.assetFile(name)
        if (!dest.isFile) {
            val part = File(dest.path + ".part")
            file.copyTo(part, overwrite = true)
            if (!part.renameTo(dest)) { part.delete(); throw IOException("Couldn't store the file") }
        }
        return name
    }

    /** A book or document laid out as A4 pages, as a PDF (sharp text at any zoom). */
    private fun layout(context: Context, doc: FlowDocument, out: File, cancel: () -> Boolean, progress: (String) -> Unit) {
        doc.use {
            val images = ImageCache(doc.res)
            try {
                val spec = LayoutSpec(1240, 1754, 1.2f, Fonts.get(context, "literata"), 30f, 1.45f,
                    RectF(120f, 130f, 1120f, 1640f), justify = true, indent = true, textColor = Color.BLACK)
                val engine = FlowEngine(doc, spec, emptyList()) { images.size(it) }
                val renderer = PageRenderer(1.2f).apply { colors = ReaderColors(false); this.images = images; eink = false }
                val pdf = PdfDocument()
                try {
                    var number = 0
                    for (ch in 0 until engine.chapterCount) for (i in 0 until engine.pageCount(ch)) {
                        if (cancel()) throw CancellationException()
                        if (++number > MAX_DOCUMENT_PAGES) throw IOException("That document is too long (over $MAX_DOCUMENT_PAGES pages)")
                        if (number % 10 == 1) progress("Laying out page $number…")
                        val page = engine.page(ch, i)
                        for (img in page.items.filterIsInstance<ImageItem>()) {
                            images.getBlocking(img.path, img.rect.width().toInt().coerceAtLeast(1), img.rect.height().toInt().coerceAtLeast(1), false)
                        }
                        val p = pdf.startPage(PdfDocument.PageInfo.Builder(1240, 1754, number).create())
                        p.canvas.drawColor(Color.WHITE)
                        renderer.draw(p.canvas, page)
                        pdf.finishPage(p)
                    }
                    if (number == 0) throw IOException("That document has no text")
                    out.outputStream().use { pdf.writeTo(it) }
                } finally { pdf.close() }
            } finally { images.close() }
        }
    }

    /** An item's picture about [width] pixels wide, or null if its file is missing. */
    fun render(store: SketchStore, item: SketchItem, width: Int): Bitmap? {
        val f = store.assetFile(item.asset)
        if (!f.isFile) return null
        val w = width.coerceIn(48, 2000)
        return try {
            if (item.asset.endsWith(".pdf")) PdfDoc(f).use { d -> if (item.sourcePage < d.pageCount) d.renderPage(item.sourcePage, w) else null }
            else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= w) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
    }

    /**
     * Where a document page goes on a sketchbook page: as large as fits, centred, keeping its
     * shape. Page units: the sketchbook page is 1 wide and [SketchFile.ASPECT] tall.
     */
    fun fitPage(size: SizeF): RectF {
        val aspect = size.height / size.width
        return if (aspect <= SketchFile.ASPECT) RectF(0f, (SketchFile.ASPECT - aspect) / 2, 1f, (SketchFile.ASPECT + aspect) / 2)
        else { val w = SketchFile.ASPECT / aspect; RectF((1 - w) / 2, 0f, (1 + w) / 2, SketchFile.ASPECT) }
    }

    /** A picture placed in the middle of the page, at most [fraction] of the page wide or tall. */
    fun fitPicture(width: Int, height: Int, fraction: Float = 0.7f): RectF {
        val aspect = height.toFloat() / width
        var w = fraction
        var h = w * aspect
        if (h > SketchFile.ASPECT * fraction) { h = SketchFile.ASPECT * fraction; w = h / aspect }
        return RectF((1 - w) / 2, (SketchFile.ASPECT - h) / 2, (1 + w) / 2, (SketchFile.ASPECT + h) / 2)
    }

    /** Placeholder drawn while an item's picture loads (or if its file is missing). */
    fun drawPlaceholder(c: Canvas, r: RectF, dark: Boolean) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = if (dark) 0xFF262626.toInt() else 0xFFEDEAE3.toInt()
        c.drawRect(r, p)
    }
}
