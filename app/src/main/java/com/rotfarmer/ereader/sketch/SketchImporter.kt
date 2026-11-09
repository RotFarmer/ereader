package com.rotfarmer.ereader.sketch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import com.rotfarmer.ereader.doc.DocxParser
import com.rotfarmer.ereader.doc.TextBlock
import com.rotfarmer.ereader.doc.TextParser
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Imports document pages as independent, movable reference layers beneath ink. */
class SketchImporter(private val ctx: Context, private val store: SketchStore) {
    fun import(uri: Uri, mime: String?, book: Long, startPage: Int): Int {
        val source = File.createTempFile("sketch-source-", ".tmp", ctx.cacheDir)
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { input.copyTo(it) } }
                ?: error("Could not open the selected file")
            val type = mime.orEmpty().lowercase()
            val name = (ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment.orEmpty()).lowercase()
            val header = ByteArray(16)
            val headerLength = source.inputStream().use { it.read(header).coerceAtLeast(0) }
            val pdf = headerLength >= 4 && String(header, 0, 4) == "%PDF"
            val zip = header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            val docx = zip && ZipFile(source).use { it.getEntry("word/document.xml") != null }
            val textLike = headerLength > 0 && !zip && !pdf && header.take(headerLength).all {
                val value = it.toInt() and 0xff
                value >= 32 || value == 9 || value == 10 || value == 13
            }
            val image = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                .also { BitmapFactory.decodeFile(source.path, it) }.let { it.outWidth > 0 && it.outHeight > 0 }
            return when {
                type == "application/pdf" || name.endsWith(".pdf") || pdf -> importPdf(source, book, startPage)
                type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || name.endsWith(".docx") || docx -> {
                    val doc = DocxParser.parse(source, "Imported document")
                    val text = try { doc.blocks.filterIsInstance<TextBlock>().joinToString("\n\n") { it.text.toString() } } finally { doc.close() }
                    importText(text, book, startPage)
                }
                type == "text/plain" || name.endsWith(".txt") || (textLike && !image) -> importText(TextParser.decode(source.readBytes()), book, startPage)
                image -> {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(source.path, options)
                    require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image" }
                    var sample = 1
                    while (options.outWidth / sample > 2400 || options.outHeight / sample > 2400) sample *= 2
                    val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: error("Could not decode image")
                    try { ensurePages(book, startPage + 1); addBitmap(book, startPage, bitmap) } finally { bitmap.recycle() }
                    1
                }
                else -> error("Choose a JPG, PNG, WebP, PDF, DOCX or TXT file")
            }
        } finally { source.delete() }
    }

    private fun importPdf(source: File, book: Long, startPage: Int): Int {
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { pdf ->
                ensurePages(book, startPage + pdf.pageCount)
                for (index in 0 until pdf.pageCount) {
                    pdf.openPage(index).use { page ->
                        val scale = minOf(1800f / page.width, 2400f / page.height, 2f)
                        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            addBitmap(book, startPage + index, bitmap)
                        } finally { bitmap.recycle() }
                    }
                }
                return pdf.pageCount
            }
        }
    }

    private fun importText(text: String, book: Long, startPage: Int): Int {
        val content = text.ifBlank { " " }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f }
        val pageWidth = 1200
        val pageHeight = 1700
        val lineHeight = 48f
        val maxLines = ((pageHeight - 160) / lineHeight).toInt()
        var offset = 0
        var count = 0
        while (offset < content.length) {
            val bitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val canvas = Canvas(bitmap)
                var line = 0
                while (line < maxLines && offset < content.length) {
                    val newline = content.indexOf('\n', offset).let { if (it < 0) content.length else it }
                    if (newline == offset) {
                        offset++
                    } else {
                        val fitting = paint.breakText(content, offset, newline, true, (pageWidth - 160).toFloat(), null).coerceAtLeast(1)
                        var end = minOf(newline, offset + fitting)
                        if (end < newline) {
                            val wordEnd = content.lastIndexOf(' ', end - 1)
                            if (wordEnd > offset + 12) end = wordEnd + 1
                        }
                        canvas.drawText(content.substring(offset, end).trimEnd(), 80f,
                            80f + paint.textSize + line * lineHeight, paint)
                        offset = end
                        if (offset == newline && offset < content.length) offset++
                    }
                    line++
                }
                ensurePages(book, startPage + count + 1)
                addBitmap(book, startPage + count, bitmap)
            } finally { bitmap.recycle() }
            count++
        }
        return count
    }

    private fun addBitmap(book: Long, page: Int, bitmap: Bitmap) {
        val filename = UUID.randomUUID().toString() + ".webp"
        val file = File(store.referencesDir, filename)
        try {
            file.outputStream().use { output ->
                @Suppress("DEPRECATION")
                check(bitmap.compress(Bitmap.CompressFormat.WEBP, 92, output)) { "Could not save reference" }
            }
            val ratio = bitmap.height.toFloat() / bitmap.width
            val width = minOf(0.8f, 0.8f / ratio)
            store.addReference(book, page, filename, width, width * ratio)
        } catch (e: Exception) { file.delete(); throw e }
    }

    private fun ensurePages(book: Long, required: Int) {
        val current = store.get(book)?.pageCount ?: error("Sketchbook missing")
        if (required > current) store.addPages(book, required - current)
    }
}
