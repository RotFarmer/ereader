package com.rotfarmer.ereader.data

import android.graphics.RectF
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Unique ids generated on the device, so new annotations never wait for the database. */
object Ids {
    private val counter = AtomicLong(System.currentTimeMillis() * 1000 + Random.nextLong(1000))
    fun next(): Long = counter.incrementAndGet()
}

/**
 * A position inside a document that survives re-layout.
 * Reflowable documents: [block] is the paragraph index, [offset] the character inside it.
 * PDFs: [block] is the page index and [offset] the vertical position in 1/10000 of the page.
 */
data class Locator(val block: Int, val offset: Int) : Comparable<Locator> {
    override fun compareTo(other: Locator): Int =
        if (block != other.block) block.compareTo(other.block) else offset.compareTo(other.offset)
}

enum class BookFormat(val label: String) {
    EPUB("EPUB"), PDF("PDF"), DOCX("WORD"), TXT("TEXT"), HTML("HTML"), MOBI("MOBI");

    companion object {
        fun of(name: String?): BookFormat = values().firstOrNull { it.name == name } ?: EPUB
    }
}

data class Book(
    val id: Long,
    val title: String,
    val author: String,
    val format: BookFormat,
    val path: String,
    val coverPath: String?,
    val added: Long,
    val opened: Long,
    val progress: Float,
    val loc: Locator,
)

data class Bookmark(
    val id: Long,
    val bookId: Long,
    val loc: Locator,
    val label: String,
    val created: Long,
)

data class Highlight(
    val id: Long,
    val bookId: Long,
    val start: Locator,
    val end: Locator,
    var color: Int,
    val text: String,
    var note: String?,
    val label: String,
    val created: Long,
)

data class Excerpt(
    val id: Long,
    val bookId: Long,
    val start: Locator,
    val end: Locator,
    val text: String,
    var note: String?,
    /** For PDF excerpts: a picture of the selected region. */
    val imagePath: String?,
    /** For PDF excerpts: the selected region in page coordinates (0..1). */
    val region: RectF?,
    val label: String,
    val created: Long,
    /** Filled in when listing excerpts across the whole library. */
    val bookTitle: String = "",
)

/** Blank room for handwriting, inserted into the flow of a document. */
class WritingSpace(
    val id: Long,
    val bookId: Long,
    val kind: Int,
    val loc: Locator,
    var height: Float,
    var paper: Int,
    val created: Long,
) {
    companion object {
        /** Space inside reflowable text, [height] is a fraction of the page height. */
        const val FLOW = 0
        /** PDF page split at loc.offset/10000; the rest of the page moves to the next screen. */
        const val PDF_SPLIT = 1
        /** Full blank page inserted after PDF page loc.block. */
        const val PDF_BLANK = 2

        const val PAPER_BLANK = 0
        const val PAPER_LINED = 1
        const val PAPER_GRID = 2
        const val PAPER_DOTS = 3
    }
}
