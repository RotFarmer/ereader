package com.rotfarmer.ereader.ui

import com.rotfarmer.ereader.data.Book
import com.rotfarmer.ereader.sketch.Sketchbook

/**
 * One card in the unified Library grid: either a book or a sketchbook.
 * Pure Kotlin so sorting/filtering rules are unit-testable without Android.
 */
sealed class LibraryItem {
    /** Stable, type-qualified identity; a book and a sketchbook can share a numeric id. */
    abstract val key: String
    abstract val title: String
    /** Blank for sketchbooks, which have no author. */
    abstract val author: String
    /** Timestamp used for "most recent" sorting. */
    abstract val recent: Long
    /** Book format name ("EPUB", …), or null for sketchbooks. */
    abstract val format: String?

    data class BookItem(val book: Book) : LibraryItem() {
        override val key = "book:${book.id}"
        override val title = book.title
        override val author = book.author
        override val recent = maxOf(book.opened, book.added)
        override val format = book.format.name
    }

    data class SketchbookItem(val sketchbook: Sketchbook) : LibraryItem() {
        override val key = "sketchbook:${sketchbook.id}"
        override val title = sketchbook.title
        override val author = ""
        override val recent = sketchbook.updated
        override val format = null
    }

    companion object {
        fun of(book: Book): LibraryItem = BookItem(book)
        fun of(sketchbook: Sketchbook): LibraryItem = SketchbookItem(sketchbook)

        /** 0 = most recent, 1 = title, 2 = author (missing authors last, then by title). */
        fun sorted(items: List<LibraryItem>, mode: Int): List<LibraryItem> = when (mode) {
            1 -> items.sortedWith(compareBy { it.title.lowercase() })
            2 -> items.sortedWith(compareBy({ it.author.isBlank() }, { it.author.trim().lowercase() }, { it.title.lowercase() }))
            else -> items.sortedByDescending { it.recent }
        }
    }
}

/** Title crowd-out filter for the Library tab. */
enum class LibraryFilter(val label: String) {
    All("All"), Sketchbooks("Sketchbooks"), EPUB("EPUB"), PDF("PDF"), DOCX("DOCX"), MOBI("MOBI");

    fun matches(item: LibraryItem): Boolean = when (this) {
        All -> true
        Sketchbooks -> item.format == null
        else -> item.format == name
    }
}

/** Case-insensitive, blank-tolerant title search. */
fun LibraryItem.matchesQuery(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return title.lowercase().contains(q)
}
