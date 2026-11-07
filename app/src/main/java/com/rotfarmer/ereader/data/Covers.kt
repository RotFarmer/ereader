package com.rotfarmer.ereader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rotfarmer.ereader.App
import com.rotfarmer.ereader.doc.DocxParser
import com.rotfarmer.ereader.doc.EpubParser
import com.rotfarmer.ereader.doc.FlowDocument
import com.rotfarmer.ereader.doc.MobiParser
import com.rotfarmer.ereader.doc.TextBlock
import com.rotfarmer.ereader.doc.TextParser
import com.rotfarmer.ereader.layout.FlowEngine
import com.rotfarmer.ereader.layout.ImageItem
import com.rotfarmer.ereader.layout.LayoutSpec
import com.rotfarmer.ereader.layout.TextItem
import com.rotfarmer.ereader.render.Eink
import com.rotfarmer.ereader.render.Fonts
import com.rotfarmer.ereader.render.ImageCache
import com.rotfarmer.ereader.render.PageRenderer
import com.rotfarmer.ereader.render.ReaderColors
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Library thumbnails. If a book's cover picture doesn't show its title (checked with on-device
 * text recognition), the book's title page is drawn and used instead.
 */
object Covers {
    private val STOP = setOf(
        "the", "and", "for", "with", "from", "into", "volume", "vol", "book", "books", "series",
        "collection", "edition", "novel", "set", "part", "complete",
    )

    /** Text found in a picture, or null if text recognition isn't available on this device. */
    fun recognizeText(bmp: Bitmap): String? = try {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 20, TimeUnit.SECONDS).text
        } finally {
            recognizer.close()
        }
    } catch (e: Throwable) {
        null
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Does the recognised text contain (enough of) the title? Lenient with OCR mistakes. */
    fun showsTitle(ocr: String, title: String): Boolean {
        val words = norm(title).split(" ").filter { it.length >= 3 && it !in STOP }.distinct()
        if (words.isEmpty()) return true
        val text = norm(ocr)
        if (text.isEmpty()) return false
        val tokens = text.split(" ")
        val joined = text.replace(" ", "")
        val hits = words.count { w ->
            joined.contains(w) || tokens.any { t -> w.length >= 5 && t.length >= 4 && closeEnough(t, w) }
        }
        return hits >= if (words.size <= 3) 1 else 2
    }

    /** Edit distance of at most one. */
    private fun closeEnough(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++; j++; continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> {
                    i++; j++
                }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    /** The book's own cover picture, if it has one. */
    fun coverPicture(book: Book): Bitmap? = try {
        val f = File(book.path)
        when (book.format) {
            BookFormat.EPUB -> EpubParser.coverBytes(f)?.let { Importer.decodeScaled(it, 480) }
            BookFormat.MOBI -> MobiParser.readMeta(f).cover?.let { Importer.decodeScaled(it, 480) }
            else -> null
        }
    } catch (e: Throwable) {
        null
    }

    /** A picture of the book's title page, or null if none could be found. */
    fun titlePage(ctx: Context, book: Book): Bitmap? = try {
        val f = File(book.path)
        val doc: FlowDocument? = when (book.format) {
            BookFormat.EPUB -> EpubParser.titlePage(f, book.title)
            BookFormat.MOBI -> MobiParser.parse(f, book.title)
            BookFormat.DOCX -> DocxParser.parse(f, book.title)
            BookFormat.TXT -> TextParser.parseText(f, book.title)
            BookFormat.HTML -> TextParser.parseHtml(f, book.title)
            BookFormat.PDF -> null
        }
        doc?.use { renderPage(ctx, it, chapterWithTitle(it, book.title)) }
    } catch (e: Throwable) {
        null
    }

    /** The early chapter that shows the title (for formats where the title page isn't marked). */
    private fun chapterWithTitle(doc: FlowDocument, title: String): Int {
        if (doc.chapters.size <= 1) return 0
        val words = norm(title).split(" ").filter { it.length >= 3 && it !in STOP }.take(2)
        val limit = minOf(doc.chapters.size, 6)
        fun firstText(c: Int): String {
            val ch = doc.chapters[c]
            return (ch.start until minOf(ch.end, ch.start + 12))
                .mapNotNull { (doc.blocks[it] as? TextBlock)?.text?.toString() }
                .joinToString(" ")
        }
        if (words.isNotEmpty()) {
            for (c in 0 until limit) {
                val t = norm(firstText(c))
                if (words.all { it in t }) return c
            }
        }
        for (c in 0 until limit) if (firstText(c).isNotBlank()) return c
        return 0
    }

    /** Draws the first page of [chapter] the way the reader would, at thumbnail size. */
    private fun renderPage(ctx: Context, doc: FlowDocument, chapter: Int): Bitmap? {
        val w = 600
        val h = 900
        val density = 1.6f
        val images = ImageCache(doc.res)
        try {
            val spec = LayoutSpec(
                width = w, height = h, density = density,
                typeface = Fonts.get(ctx, "literata"), textSizePx = 24f, lineSpacing = 1.4f,
                content = RectF(46f, 64f, w - 46f, h - 52f), justify = false, indent = false,
                textColor = 0xFF1C1C1C.toInt(),
            )
            val engine = FlowEngine(doc, spec, emptyList()) { images.size(it) }
            val page = engine.page(chapter, 0)
            if (page.items.none { it is TextItem || it is ImageItem }) return null
            for (it in page.items) {
                if (it is ImageItem) images.getBlocking(it.path, it.rect.width().toInt().coerceAtLeast(1), it.rect.height().toInt().coerceAtLeast(1), false)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFFF2F0E9.toInt())
            val r = PageRenderer(density)
            r.colors = ReaderColors(false)
            r.images = images
            r.eink = false
            r.draw(c, page)
            val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = 0xFFBDB8AD.toInt(); strokeWidth = 3f
            }
            c.drawRect(RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f), frame)
            return bmp
        } finally {
            images.close()
        }
    }

    /** Saves [bmp] as the book's thumbnail (in e-ink grays if that setting is on). */
    fun save(app: App, book: Book, bmp: Bitmap) {
        var b = bmp
        if (app.settings.einkImages) {
            if (!b.isMutable) b = b.copy(Bitmap.Config.ARGB_8888, true)
            Eink.process(b)
        }
        val f = File(book.coverPath ?: File(app.coversDir, "${book.id}.jpg").absolutePath)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    /**
     * If [picture] (the cover) doesn't show the book's title, returns a picture of the title page
     * to use instead; otherwise null.
     */
    fun betterThan(ctx: Context, book: Book, picture: Bitmap?): Bitmap? {
        if (book.format != BookFormat.EPUB && book.format != BookFormat.MOBI) return null
        val pic = picture ?: return null
        val text = recognizeText(pic) ?: return null
        if (showsTitle(text, book.title)) return null
        return titlePage(ctx, book)
    }

    private const val CHECKED = "coversChecked"

    fun isChecked(app: App, id: Long) = app.settings.prefs.getStringSet(CHECKED, emptySet())!!.contains(id.toString())

    fun markChecked(app: App, id: Long) {
        val set = HashSet(app.settings.prefs.getStringSet(CHECKED, emptySet())!!)
        set += id.toString()
        app.settings.prefs.edit().putStringSet(CHECKED, set).apply()
    }
}
