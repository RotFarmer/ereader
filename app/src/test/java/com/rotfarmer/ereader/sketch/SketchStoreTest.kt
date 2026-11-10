package com.rotfarmer.ereader.sketch

import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkStroke
import com.rotfarmer.ereader.ink.InkColors
import com.rotfarmer.ereader.ink.Tools
import com.rotfarmer.ereader.doc.EpubParser
import com.rotfarmer.ereader.doc.TextBlock
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SketchStoreTest {
    @Test fun darkInkIsReadableWithoutChangingStoredColor() {
        assertTrue(Color.red(InkColors.display(Color.BLACK, true)) > 128)
        assertEquals(Color.BLACK, InkColors.display(Color.BLACK, false))
    }

    @Test fun sparsePagesPersistAcrossReopenAndReferenceRemovalKeepsInk() {
        val ctx = RuntimeEnvironment.getApplication()
        var store = SketchStore(ctx)
        val id = store.create("Ideas")
        assertEquals(350, store.get(id)?.pageCount)
        assertEquals(0, store.page(id, 349).strokes.size)
        assertEquals(0, store.page(id, 349).references.size)
        store.setPaper(id, 349, 2)
        store.addPages(id, 25)
        store.setCurrentPage(id, 349)
        val stroke = InkStroke(-1, id, Anchor(Anchor.PDF, 349, 0), Tools.PEN, 0xFF000000.toInt(),
            0.003f, floatArrayOf(0.1f, 0.2f, 0.4f, 0.3f, 0.4f, 0.8f), 1234)
        store.insertStroke(id, 349, stroke)
        val file = java.io.File(store.referencesDir, "reference.webp").apply { writeText("reference") }
        store.addReference(id, 349, file.name, 0.5f, 0.7f)
        store.close()

        store = SketchStore(ctx)
        assertEquals(375, store.get(id)?.pageCount)
        assertEquals(349, store.get(id)?.currentPage)
        assertEquals(2, store.page(id, 349).paper)
        assertEquals(2, store.page(id, 349).strokes.single().pts.size / 3)
        val ref = store.page(id, 349).references.single()
        store.removeReference(id, ref)
        assertTrue(store.page(id, 349).references.isEmpty())
        assertFalse(store.page(id, 349).strokes.isEmpty())
        store.close()
    }

    @Test fun imageImportCreatesMovableReferenceOnCurrentPage() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SketchStore(ctx)
        val id = store.create("Tracing")
        val source = java.io.File(ctx.cacheDir, "test-reference.png")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        assertEquals(1, SketchImporter(ctx, store).import(Uri.fromFile(source), "image/png", id, 4))
        val ref = store.page(id, 4).references.single()
        assertTrue(java.io.File(store.referencesDir, ref.path).exists())
        store.updateReference(ref.copy(x = 0.25f, opacity = 0.4f))
        assertEquals(0.25f, store.page(id, 4).references.single().x, 0.001f)
        assertEquals(0.4f, store.page(id, 4).references.single().opacity, 0.001f)
        store.delete(id)
        assertTrue(java.io.File(store.referencesDir, ref.path).exists())
        assertEquals(null, store.get(id))
        store.restore(id)
        assertEquals(ref.path, store.page(id, 4).references.single().path)
        source.delete()
        store.close()
    }

    @Test fun textAndWordDocumentsImportAsReferencePages() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SketchStore(ctx)
        val id = store.create("Documents")
        val text = java.io.File(ctx.cacheDir, "reference.txt").apply { writeText("Trace this paragraph.\n".repeat(400)) }
        val imported = SketchImporter(ctx, store).import(Uri.fromFile(text), "text/plain", id, 0)
        assertTrue(imported > 1)
        assertEquals(1, store.page(id, 0).references.size)
        assertEquals(1, store.page(id, imported - 1).references.size)

        val docx = java.io.File(ctx.cacheDir, "reference.docx")
        java.util.zip.ZipOutputStream(docx.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write("""<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Word reference for tracing</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
        assertEquals(1, SketchImporter(ctx, store).import(Uri.fromFile(docx),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", id, imported))
        assertEquals(1, store.page(id, imported).references.size)
        store.delete(id)
        text.delete(); docx.delete(); store.close()
    }

    @Test fun existingEpubParserStillReadsBookText() {
        val ctx = RuntimeEnvironment.getApplication()
        val epub = java.io.File(ctx.cacheDir, "reader-regression.epub")
        java.util.zip.ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(name: String, value: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
            entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""")
            entry("OPS/book.opf", """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>Test book</dc:title></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""")
            entry("OPS/chapter.xhtml", """<html><body><h1>Chapter one</h1><p>Existing EPUB reading works.</p></body></html>""")
        }
        EpubParser.parse(epub, "Fallback").use { doc ->
            assertEquals("Test book", doc.title)
            assertTrue(doc.blocks.filterIsInstance<TextBlock>().any { it.text.contains("Existing EPUB reading works") })
        }
        epub.delete()
    }
}
