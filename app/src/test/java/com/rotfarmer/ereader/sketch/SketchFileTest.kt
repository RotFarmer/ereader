package com.rotfarmer.ereader.sketch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The shared `.sketchbook` format and how two copies' settings combine (plain JVM, no Android). */
class SketchFileTest {
    private val meta = SketchMeta("book-1", "Ideas", 100L, 1, 200L, 350, 50L)

    private fun stroke(id: Long, page: Int, n: Int = 3) =
        SketchStrokeRecord(id, page, 1, 0xFF112233.toInt(), 0.004f, 1000L + id, FloatArray(n * 3) { it * 0.01f })

    private fun write(m: SketchMeta, deleted: List<Pair<Long, Long>>, strokes: List<SketchStrokeRecord>): ByteArray {
        val out = ByteArrayOutputStream()
        SketchFile.write(out, m, deleted = { emit -> deleted.forEach { emit(it.first, it.second) } }, strokes = { emit -> strokes.forEach(emit) })
        return out.toByteArray()
    }

    @Test fun roundTripKeepsEverything() {
        val strokes = listOf(stroke(11, 0), stroke(12, 4, 500), stroke(13, 349))
        val bytes = write(meta, listOf(21L to 300L, 22L to 301L), strokes)
        var readMeta: SketchMeta? = null
        val deleted = ArrayList<Pair<Long, Long>>()
        val read = ArrayList<SketchStrokeRecord>()
        SketchFile.read(ByteArrayInputStream(bytes), { readMeta = it }, { id, t -> deleted += id to t }, { read += it })
        assertEquals(meta, readMeta)
        assertEquals(listOf(21L to 300L, 22L to 301L), deleted)
        assertEquals(strokes.map { it.id }, read.map { it.id })
        for ((a, b) in strokes.zip(read)) {
            assertEquals(a.page, b.page); assertEquals(a.tool, b.tool); assertEquals(a.color, b.color)
            assertEquals(a.width, b.width, 0f); assertEquals(a.created, b.created)
            assertArrayEquals(a.pts, b.pts, 0f)
        }
    }

    @Test fun recognisesSketchbooksFromTheFirstBytes() {
        val bytes = write(meta, emptyList(), listOf(stroke(1, 0)))
        assertTrue(SketchFile.looksLikeSketchbook(bytes.copyOf(64)))
        assertEquals("book-1", SketchFile.readMeta(ByteArrayInputStream(bytes)).id)

        // An EPUB (also a zip) is not a sketchbook.
        val epub = ByteArrayOutputStream()
        ZipOutputStream(epub).use { z -> z.putNextEntry(ZipEntry("mimetype")); z.write("application/epub+zip".toByteArray()); z.closeEntry() }
        assertFalse(SketchFile.looksLikeSketchbook(epub.toByteArray().copyOf(64)))
        assertFalse(SketchFile.looksLikeSketchbook(ByteArray(64)))
    }

    @Test fun rejectsFilesThatAreNotSketchbooks() {
        val epub = ByteArrayOutputStream()
        ZipOutputStream(epub).use { z -> z.putNextEntry(ZipEntry("mimetype")); z.write("x".toByteArray()); z.closeEntry() }
        for (bad in listOf(ByteArray(0), "hello".toByteArray(), epub.toByteArray())) {
            try {
                SketchFile.read(ByteArrayInputStream(bad), {}, { _, _ -> }, {})
                fail("accepted a file that isn't a sketchbook")
            } catch (e: IOException) { /* expected */ }
        }
    }

    @Test fun rejectsADamagedStroke() {
        val broken = stroke(5, 0).let { SketchStrokeRecord(it.id, SketchFile.MAX_PAGES, it.tool, it.color, it.width, it.created, it.pts) }
        val bytes = write(meta, emptyList(), listOf(broken))
        try {
            SketchFile.read(ByteArrayInputStream(bytes), {}, { _, _ -> }, {})
            fail("accepted a stroke on a page the sketchbook doesn't have")
        } catch (e: IOException) { /* expected */ }
    }

    private fun item(id: Long, time: Long, x: Float = 0.1f, deleted: Long = 0L) =
        SketchItem(id, 2, SketchItem.PICTURE, "a".repeat(64) + ".jpg", 0, x, 0.2f, 0.5f, 0.4f, 1L, time, deleted)

    @Test fun picturesAndTheirFilesTravelInTheFile() {
        val pdfName = "b".repeat(64) + ".pdf"
        val items = listOf(item(1, 10), item(2, 11, deleted = 12), SketchItem(3, 5, SketchItem.DOCUMENT, pdfName, 7, 0f, 0f, 1f, 1.29f, 1L, 1L))
        val jpg = ByteArray(5000) { (it % 251).toByte() }
        val pdf = "%PDF-1.4 fake".toByteArray()
        val out = ByteArrayOutputStream()
        SketchFile.write(out, meta, deleted = {}, strokes = { emit -> emit(stroke(9, 0)) }, items = items,
            assets = listOf(items[0].asset to { ByteArrayInputStream(jpg) }, pdfName to { ByteArrayInputStream(pdf) }))
        val readItems = ArrayList<SketchItem>()
        val files = HashMap<String, ByteArray>()
        SketchFile.read(ByteArrayInputStream(out.toByteArray()), {}, { _, _ -> }, {}, { readItems += it }, { name, input -> files[name] = input.readBytes() })
        assertEquals(items, readItems)
        assertArrayEquals(jpg, files[items[0].asset])
        assertArrayEquals(pdf, files[pdfName])
    }

    @Test fun drawingOnlySketchbooksStayReadableByOlderVersions() {
        // Version 1 is written when nothing is placed, so devices that haven't updated still sync.
        val bytes = write(meta, emptyList(), listOf(stroke(1, 0)))
        val v = java.io.DataInputStream(java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).also { it.nextEntry }).run { readUTF(); readInt() }
        assertEquals(1, v)
    }

    @Test fun aRemovedPictureStaysRemovedAndTheNewestPositionWins() {
        val moved = item(1, 20, x = 0.6f)
        val old = item(1, 10, x = 0.1f)
        assertEquals(0.6f, SketchMerge.item(old, moved).x, 0f)
        assertEquals(0.6f, SketchMerge.item(moved, old).x, 0f)
        val removed = item(1, 15, deleted = 15)
        assertTrue(SketchMerge.item(moved, removed).deleted > 0)
        assertTrue(SketchMerge.item(removed, moved).deleted > 0)
    }

    @Test fun bothDevicesAgreeOnARemovalSoSyncSettles() {
        // Removed on both devices at different times, or moved on one and removed on the other:
        // either order of merging must give the same answer, or the devices would keep rewriting.
        val a = item(1, 100, deleted = 100)
        val b = item(1, 120, x = 0.6f, deleted = 120)
        val moved = item(1, 130, x = 0.9f)
        for ((x, y) in listOf(a to b, a to moved, b to moved)) {
            val one = SketchMerge.item(x, y); val two = SketchMerge.item(y, x)
            assertTrue(SketchMerge.sameItem(one, two))
            // Merging again changes nothing.
            assertTrue(SketchMerge.sameItem(SketchMerge.item(one, two), one))
        }
        assertEquals(100L, SketchMerge.item(b, a).deleted)
    }

    @Test fun newerSettingsWinAndPagesOnlyGrow() {
        val local = meta.copy(title = "Old", titleTime = 10, paper = 0, paperTime = 50, pages = 400)
        val remote = meta.copy(title = "New", titleTime = 20, paper = 2, paperTime = 40, pages = 350)
        val merged = SketchMerge.meta(local, remote)
        assertEquals("New", merged.title)
        assertEquals(20L, merged.titleTime)
        assertEquals(0, merged.paper) // ours was changed later
        assertEquals(50L, merged.paperTime)
        assertEquals(400, merged.pages)
        // Merging is the same from either side.
        val other = SketchMerge.meta(remote, local)
        assertEquals(merged.title, other.title); assertEquals(merged.paper, other.paper); assertEquals(merged.pages, other.pages)
    }
}
