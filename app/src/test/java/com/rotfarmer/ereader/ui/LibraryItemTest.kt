package com.rotfarmer.ereader.ui

import com.rotfarmer.ereader.data.Book
import com.rotfarmer.ereader.data.BookFormat
import com.rotfarmer.ereader.data.Locator
import com.rotfarmer.ereader.sketch.Sketchbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryItemTest {
    private fun book(id: Long, title: String, author: String = "", opened: Long = 0L, added: Long = 0L, format: BookFormat = BookFormat.EPUB) =
        Book(id, title, author, format, "/books/$id", null, added, opened, 0f, Locator(0, 0))

    private fun sketch(id: Long, title: String, updated: Long = 0L) = Sketchbook(id, title, 350, 0, updated)

    @Test fun keysAreTypeQualifiedSoIdsCannotCollide() {
        val bookItem = LibraryItem.of(book(7, "Same title"))
        val sketchItem = LibraryItem.of(sketch(7, "Same title"))
        assertTrue(bookItem.key.startsWith("book:"))
        assertTrue(sketchItem.key.startsWith("sketchbook:"))
        assertFalse(bookItem.key == sketchItem.key)
        assertEquals(2, setOf(bookItem.key, sketchItem.key).size)
    }

    @Test fun titleSortIgnoresCase() {
        val items = listOf(LibraryItem.of(book(1, "banana")), LibraryItem.of(sketch(2, "Apple")), LibraryItem.of(book(3, "Cherry")))
        assertEquals(listOf("Apple", "banana", "Cherry"), LibraryItem.sorted(items, 1).map { it.title })
    }

    @Test fun authorSortPutsMissingAuthorsAfterNonEmpty() {
        val items = listOf(
            LibraryItem.of(book(1, "Zebra", author = "")),
            LibraryItem.of(book(2, "Apple", author = "   ")),
            LibraryItem.of(sketch(3, "Sketch")),
            LibraryItem.of(book(4, "Casual", author = "Sally Longname")),
            LibraryItem.of(book(5, "Bus", author = "Ann Allin")),
        )
        val sorted = LibraryItem.sorted(items, 2)
        assertEquals(listOf("Bus", "Casual", "Apple", "Sketch", "Zebra"), sorted.map { it.title })
    }

    @Test fun recentSortMixesBooksAndSketchbooks() {
        val items = listOf(
            LibraryItem.of(book(1, "Old", opened = 100, added = 0)),
            LibraryItem.of(sketch(2, "Sketch", updated = 500)),
            LibraryItem.of(book(3, "Open", opened = 300, added = 0)),
            LibraryItem.of(book(4, "Added", opened = 0, added = 400)),
            LibraryItem.of(sketch(5, "Oldest", updated = 10)),
        )
        assertEquals(listOf("Sketch", "Added", "Open", "Old", "Oldest"), LibraryItem.sorted(items, 0).map { it.title })
    }

    @Test fun filtersSeparateFormatsAndSketchbooks() {
        val items = listOf(
            LibraryItem.of(book(1, "A", format = BookFormat.EPUB)),
            LibraryItem.of(book(2, "B", format = BookFormat.PDF)),
            LibraryItem.of(book(3, "C", format = BookFormat.DOCX)),
            LibraryItem.of(book(4, "D", format = BookFormat.MOBI)),
            LibraryItem.of(sketch(5, "E")),
        )
        assertEquals(5, items.count { LibraryFilter.All.matches(it) })
        assertEquals(listOf("E"), items.filter { LibraryFilter.Sketchbooks.matches(it) }.map { it.title })
        assertEquals(listOf("A"), items.filter { LibraryFilter.EPUB.matches(it) }.map { it.title })
        assertEquals(listOf("B"), items.filter { LibraryFilter.PDF.matches(it) }.map { it.title })
        assertEquals(listOf("C"), items.filter { LibraryFilter.DOCX.matches(it) }.map { it.title })
        assertEquals(listOf("D"), items.filter { LibraryFilter.MOBI.matches(it) }.map { it.title })
    }

    @Test fun titleQueryIsCaseInsensitiveAndBlankMatchesAll() {
        val bookItem = LibraryItem.of(book(1, "The Lighthouse Keeper"))
        val sketchItem = LibraryItem.of(sketch(2, "Field Notes"))
        assertTrue(bookItem.matchesQuery("LIGHTHOUSE"))
        assertTrue(bookItem.matchesQuery("lighthouse keeper"))
        assertFalse(bookItem.matchesQuery("keeper's"))
        assertTrue(sketchItem.matchesQuery("field"))
        assertTrue(sketchItem.matchesQuery("  "))
        assertFalse(sketchItem.matchesQuery("keeper"))
    }
}
