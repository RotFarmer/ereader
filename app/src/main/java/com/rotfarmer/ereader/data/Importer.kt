package com.rotfarmer.ereader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.rotfarmer.ereader.App
import com.rotfarmer.ereader.doc.DocxParser
import com.rotfarmer.ereader.doc.EpubParser
import com.rotfarmer.ereader.doc.PdfDoc
import com.rotfarmer.ereader.render.Eink
import com.rotfarmer.ereader.render.Fonts
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class ImportException(message: String) : Exception(message)

/** Copies a picked file into the app, works out what it is, and adds it to the library. */
object Importer {

    fun import(app: App, uri: Uri): Book {
        val cr = app.contentResolver
        val name = displayName(app, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        val id = Ids.next()
        val tmp = File(app.booksDir, "import-$id.tmp")
        try {
            val input = cr.openInputStream(uri) ?: throw ImportException("Couldn't read “$name”.")
            input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            val format = detect(tmp, name)
            val dest = File(app.booksDir, "$id.${format.name.lowercase()}")
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, true); tmp.delete()
            }
            return register(app, id, dest, format, name.substringBeforeLast('.').ifBlank { name })
        } finally {
            tmp.delete()
        }
    }

    private fun register(app: App, id: Long, file: File, format: BookFormat, baseName: String): Book {
        var title = baseName
        var author = ""
        var cover: Bitmap? = null
        try {
            when (format) {
                BookFormat.EPUB -> {
                    val m = EpubParser.readMeta(file)
                    title = m.title?.ifBlank { null } ?: baseName
                    author = m.author ?: ""
                    cover = EpubParser.coverBytes(file)?.let { decodeScaled(it, 480) }
                }
                BookFormat.DOCX -> {
                    val (t, a) = DocxParser.readMeta(file)
                    title = t ?: baseName
                    author = a ?: ""
                }
                BookFormat.PDF -> PdfDoc(file).use { pdf -> if (pdf.pageCount > 0) cover = pdf.renderPage(0, 480) }
                else -> {}
            }
        } catch (e: IllegalArgumentException) {
            file.delete()
            throw ImportException(e.message ?: "This file couldn't be opened.")
        } catch (e: Exception) {
            if (format == BookFormat.EPUB || format == BookFormat.PDF) {
                file.delete()
                throw ImportException("This ${format.label} file appears to be damaged.")
            }
        }
        val existing = app.db.findBookByTitle(title, format)
        if (existing != null && File(existing.path).length() == file.length()) {
            file.delete()
            return existing
        }
        val coverFile = File(app.coversDir, "$id.jpg")
        var bmp = cover ?: generateCover(app, title, author, format)
        if (app.settings.einkImages) {
            if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
            Eink.process(bmp)
        }
        coverFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val now = System.currentTimeMillis()
        val book = Book(id, title, author, format, file.absolutePath, coverFile.absolutePath, now, 0, 0f, Locator(0, 0))
        app.db.insertBook(book)
        return book
    }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    fun detect(f: File, name: String): BookFormat {
        val head = ByteArray(8)
        val n = f.inputStream().use { it.read(head) }
        if (n >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()) {
            return BookFormat.PDF
        }
        if (n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            try {
                ZipFile(f).use { z ->
                    if (z.getEntry("word/document.xml") != null) return BookFormat.DOCX
                    if (z.getEntry("META-INF/container.xml") != null) return BookFormat.EPUB
                    val mt = z.getEntry("mimetype")?.let { e -> z.getInputStream(e).use { String(it.readBytes()).trim() } }
                    if (mt == "application/epub+zip") return BookFormat.EPUB
                }
            } catch (e: IOException) {
                throw ImportException("“$name” seems to be damaged.")
            }
            throw ImportException("“$name” isn't an EPUB, PDF or Word (.docx) document.")
        }
        if (n >= 4 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()) {
            throw ImportException("Old-style Word (.doc) files aren't supported. Open it in Word or Google Docs, save it as .docx, then add it again.")
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in setOf("html", "htm", "xhtml")) return BookFormat.HTML
        if (ext == "txt" || ext == "md" || ext == "text" || looksLikeText(f)) return BookFormat.TXT
        throw ImportException("“$name” isn't a supported file. You can add EPUB, PDF, Word (.docx) and text files.")
    }

    private fun looksLikeText(f: File): Boolean {
        val buf = ByteArray(4096)
        val n = f.inputStream().use { it.read(buf) }
        if (n <= 0) return false
        for (i in 0 until n) if (buf[i] == 0.toByte()) return false
        return true
    }

    private fun decodeScaled(bytes: ByteArray, width: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= width) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (raw.width <= width) return raw
        return Bitmap.createScaledBitmap(raw, width, (raw.height * width.toFloat() / raw.width).toInt().coerceAtLeast(1), true)
    }

    /** A plain typographic cover for books that don't have one. */
    fun generateCover(ctx: Context, title: String, author: String, format: BookFormat): Bitmap {
        val w = 480
        val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFEDEAE1.toInt())
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFF222222.toInt(); strokeWidth = 3f
        }
        c.drawRect(RectF(22f, 22f, w - 22f, h - 22f), line)
        line.strokeWidth = 1.2f
        c.drawRect(RectF(32f, 32f, w - 32f, h - 32f), line)
        val serif = Fonts.get(ctx, "literata")
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF151515.toInt(); typeface = Fonts.styled(serif, Typeface.BOLD); textSize = 46f
        }
        var layout = StaticLayout.Builder.obtain(title, 0, title.length, tp, w - 110).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).build()
        while (layout.height > 360 && tp.textSize > 24f) {
            tp.textSize -= 3f
            layout = StaticLayout.Builder.obtain(title, 0, title.length, tp, w - 110).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).build()
        }
        c.save()
        c.translate(55f, 210f - layout.height / 3f)
        layout.draw(c)
        c.restore()
        val ornY = 210f + layout.height * 2f / 3f + 40f
        line.strokeWidth = 2f
        c.drawLine(w / 2f - 60f, ornY, w / 2f + 60f, ornY, line)
        if (author.isNotBlank()) {
            val ap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF333333.toInt(); typeface = Fonts.styled(serif, Typeface.ITALIC); textSize = 28f
            }
            val al = StaticLayout.Builder.obtain(author, 0, author.length, ap, w - 110).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            c.save(); c.translate(55f, ornY + 30f); al.draw(c); c.restore()
        }
        val fp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF555555.toInt(); textSize = 20f; letterSpacing = 0.3f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val label = format.label
        c.drawText(label, (w - fp.measureText(label)) / 2f, h - 64f, fp)
        return bmp
    }
}
