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
        val broken = stroke(5, 0).let { SketchStrokeRecord(it.id, 9999, it.tool, it.color, it.width, it.created, it.pts) }
        val bytes = write(meta, emptyList(), listOf(broken))
        try {
            SketchFile.read(ByteArrayInputStream(bytes), {}, { _, _ -> }, {})
            fail("accepted a stroke on a page the sketchbook doesn't have")
        } catch (e: IOException) { /* expected */ }
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
