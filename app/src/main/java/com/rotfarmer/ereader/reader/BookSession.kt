package com.rotfarmer.ereader.reader

import com.rotfarmer.ereader.App
import com.rotfarmer.ereader.data.Book
import com.rotfarmer.ereader.data.BookFormat
import com.rotfarmer.ereader.data.Bookmark
import com.rotfarmer.ereader.data.Excerpt
import com.rotfarmer.ereader.data.Highlight
import com.rotfarmer.ereader.data.WritingSpace
import com.rotfarmer.ereader.doc.DocxParser
import com.rotfarmer.ereader.doc.EpubParser
import com.rotfarmer.ereader.doc.FlowDocument
import com.rotfarmer.ereader.doc.PdfDoc
import com.rotfarmer.ereader.doc.TextParser
import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkStroke
import com.rotfarmer.ereader.render.ImageCache
import com.rotfarmer.ereader.render.PdfCache
import kotlinx.coroutines.launch
import java.io.File

/** One open book: its parsed content, its annotations, and saving/undo for both. */
class BookSession(private val app: App, val book: Book) {
    var flow: FlowDocument? = null
        private set
    var pdf: PdfDoc? = null
        private set
    var images: ImageCache? = null
        private set
    var pdfCache: PdfCache? = null
        private set
    val ann = Annotations()

    private class Edit(
        val added: List<InkStroke> = emptyList(),
        val removed: List<InkStroke> = emptyList(),
        val hlAdded: List<Highlight> = emptyList(),
        val hlRemoved: List<Highlight> = emptyList(),
        val spaceAdded: WritingSpace? = null,
        val spaceRemoved: WritingSpace? = null,
        /** Ink inside a removed (or un-added) space, restored with it. */
        var spaceInk: List<InkStroke> = emptyList(),
        val resized: WritingSpace? = null,
        val oldHeight: Float = 0f,
        val newHeight: Float = 0f,
    ) {
        val space: WritingSpace? get() = spaceAdded ?: spaceRemoved ?: resized
    }

    /** What an undo/redo changed, so the reader knows whether pages must be laid out again. */
    class UndoResult(val space: WritingSpace?)

    private val undoStack = ArrayList<Edit>()
    private val redoStack = ArrayList<Edit>()
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    private val db get() = app.db

    /** Parses the file and loads annotations. Call off the main thread. */
    fun open() {
        val f = File(book.path)
        if (!f.exists()) throw IllegalStateException("The file for this book is missing.")
        when (book.format) {
            BookFormat.PDF -> pdf = PdfDoc(f).also { pdfCache = PdfCache(it) }
            BookFormat.EPUB -> flow = EpubParser.parse(f, book.title)
            BookFormat.DOCX -> flow = DocxParser.parse(f, book.title)
            BookFormat.TXT -> flow = TextParser.parseText(f, book.title)
            BookFormat.HTML -> flow = TextParser.parseHtml(f, book.title)
        }
        flow?.let { images = ImageCache(it.res) }
        ann.highlights += db.highlights(book.id)
        ann.bookmarks += db.bookmarks(book.id)
        ann.excerpts += db.excerpts(book.id)
        ann.spaces += db.spaces(book.id)
        db.strokes(book.id).forEach { ann.ink.add(it) }
    }

    fun close() {
        images?.close()
        pdfCache?.close()
        try {
            flow?.close()
        } catch (_: Exception) {
        }
        pdf?.close()
    }

    private fun write(block: () -> Unit) {
        App.io.launch {
            try {
                block()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- ink + undo

    private fun push(e: Edit) {
        undoStack += e
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun strokeAdded(s: InkStroke) {
        push(Edit(added = listOf(s)))
        write { db.insertStrokes(listOf(s)) }
    }

    fun erased(strokes: List<InkStroke>, highlights: List<Highlight>) {
        push(Edit(removed = strokes, hlRemoved = highlights))
        persist(added = emptyList(), removed = strokes, hlAdded = emptyList(), hlRemoved = highlights)
    }

    fun smartHighlight(h: Highlight) {
        ann.highlights += h
        push(Edit(hlAdded = listOf(h)))
        write { db.saveHighlight(h) }
    }

    fun clearInk(strokes: List<InkStroke>) {
        strokes.forEach { ann.ink.remove(it) }
        erased(strokes, emptyList())
    }

    private fun apply(e: Edit, forward: Boolean) {
        e.spaceAdded?.let { if (forward) restoreSpace(it, e.spaceInk) else e.spaceInk = removeSpace(it) }
        e.spaceRemoved?.let { if (forward) e.spaceInk = removeSpace(it) else restoreSpace(it, e.spaceInk) }
        e.resized?.let { sp ->
            sp.height = if (forward) e.newHeight else e.oldHeight
            write { db.saveSpace(sp) }
        }
        val add = if (forward) e.added else e.removed
        val rem = if (forward) e.removed else e.added
        val hAdd = if (forward) e.hlAdded else e.hlRemoved
        val hRem = if (forward) e.hlRemoved else e.hlAdded
        add.forEach { ann.ink.add(it) }
        rem.forEach { ann.ink.remove(it) }
        ann.highlights.addAll(hAdd)
        ann.highlights.removeAll(hRem.toSet())
        persist(add, rem, hAdd, hRem)
    }

    private fun persist(added: List<InkStroke>, removed: List<InkStroke>, hlAdded: List<Highlight>, hlRemoved: List<Highlight>) {
        write {
            if (added.isNotEmpty()) db.insertStrokes(added)
            if (removed.isNotEmpty()) db.deleteStrokes(removed.map { it.id })
            hlAdded.forEach { db.saveHighlight(it) }
            hlRemoved.forEach { db.deleteHighlight(it.id) }
        }
    }

    fun undo(): UndoResult? {
        val e = undoStack.removeLastOrNull() ?: return null
        apply(e, forward = false)
        redoStack += e
        return UndoResult(e.space)
    }

    fun redo(): UndoResult? {
        val e = redoStack.removeLastOrNull() ?: return null
        apply(e, forward = true)
        undoStack += e
        return UndoResult(e.space)
    }

    // ---------------------------------------------------------------- highlights

    fun addHighlight(h: Highlight) {
        ann.highlights += h
        write { db.saveHighlight(h) }
    }

    fun updateHighlight(h: Highlight) = write { db.saveHighlight(h) }

    fun deleteHighlight(h: Highlight) {
        ann.highlights.remove(h)
        write { db.deleteHighlight(h.id) }
    }

    // ---------------------------------------------------------------- bookmarks

    fun addBookmark(b: Bookmark) {
        ann.bookmarks += b
        ann.bookmarks.sortBy { it.loc }
        write { db.insertBookmark(b) }
    }

    fun deleteBookmark(b: Bookmark) {
        ann.bookmarks.remove(b)
        write { db.deleteBookmark(b.id) }
    }

    // ---------------------------------------------------------------- excerpts

    fun addExcerpt(e: Excerpt) {
        ann.excerpts += e
        ann.excerpts.sortBy { it.start }
        write { db.saveExcerpt(e) }
    }

    fun updateExcerpt(e: Excerpt) = write { db.saveExcerpt(e) }

    fun deleteExcerpt(e: Excerpt) {
        ann.excerpts.remove(e)
        write {
            db.deleteExcerpt(e.id)
            e.imagePath?.let { File(it).delete() }
        }
    }

    // ---------------------------------------------------------------- writing spaces

    fun addSpace(s: WritingSpace) {
        restoreSpace(s, emptyList())
        push(Edit(spaceAdded = s))
    }

    fun updateSpace(s: WritingSpace) = write { db.saveSpace(s) }

    fun resizeSpace(s: WritingSpace, height: Float) {
        val old = s.height
        s.height = height
        push(Edit(resized = s, oldHeight = old, newHeight = height))
        write { db.saveSpace(s) }
    }

    fun deleteSpace(s: WritingSpace) {
        val ink = removeSpace(s)
        push(Edit(spaceRemoved = s, spaceInk = ink))
    }

    /** Removes a space and the ink in it; returns that ink so it can be restored. */
    private fun removeSpace(s: WritingSpace): List<InkStroke> {
        ann.spaces.remove(s)
        val ink = ArrayList(ann.ink.removeAnchor(Anchor.SPACE, s.id))
        write { db.deleteSpace(s.id) }
        return ink
    }

    private fun restoreSpace(s: WritingSpace, ink: List<InkStroke>) {
        if (s !in ann.spaces) ann.spaces += s
        ink.forEach { ann.ink.add(it) }
        write {
            db.saveSpace(s)
            if (ink.isNotEmpty()) db.insertStrokes(ink)
        }
    }
}
