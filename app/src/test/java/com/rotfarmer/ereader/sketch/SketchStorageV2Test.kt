package com.rotfarmer.ereader.sketch

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkStroke
import com.rotfarmer.ereader.ink.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SketchStorageV2Test {
    private fun ink(created: Long, pts: FloatArray = floatArrayOf(0.1f, 0.2f, 0.5f, 0.3f, 0.4f, 0.8f),
                    width: Float = 2f, tool: Int = Tools.PEN) =
        InkStroke(-1, 0L, Anchor(Anchor.PDF, 0L, 0), tool, 0xFF112233.toInt(), width, pts, created)

    @Test fun pageIdentityStaysStableThroughInsertMoveDeleteAndRestore() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SketchStore(ctx)
        val book = store.create("Identity")
        val inkPts = floatArrayOf(0.05f, 0.1f, 0.5f, 0.25f, 0.2f, 0.9f)
        val original = store.info(book, 10)
        val neighbor = store.info(book, 12)
        val refFile = java.io.File(store.referencesDir, "stable-ref.png").apply { writeText("stable") }
        val inkId = store.insertStroke(book, 10, ink(88L, inkPts))
        val refId = store.addReference(book, 10, refFile.name, 0.3f, 0.2f)

        store.insertPages(book, 2, 3)
        val afterInsert = store.info(book, 13)
        assertEquals(original.id, afterInsert.id)
        assertEquals(original.slot, afterInsert.slot)
        assertEquals(neighbor.id, store.info(book, 15).id)
        val contentAfterInsert = store.page(book, 13)
        assertEquals(listOf(inkId), contentAfterInsert.strokes.map { it.id })
        assertTrue(contentAfterInsert.strokes.single().pts.contentEquals(inkPts))
        assertEquals(listOf(refId), contentAfterInsert.references.map { it.id })

        store.movePage(book, original.id, 0)
        val top = store.info(book, 0)
        assertEquals(original.id, top.id)
        assertEquals(neighbor.id, store.info(book, 15).id)
        val contentAtTop = store.page(book, 0)
        assertEquals(listOf(inkId), contentAtTop.strokes.map { it.id })
        assertTrue(contentAtTop.strokes.single().pts.contentEquals(inkPts))
        assertEquals(listOf(refId), contentAtTop.references.map { it.id })

        store.deletePage(book, original.id)
        assertEquals(352, store.get(book)?.pageCount)
        assertFalse(store.pages(book).any { it.id == original.id })
        assertEquals(neighbor.id, store.info(book, 14).id)
        val trashed = store.trash(book)
        assertEquals(1, trashed.size)
        assertEquals(original.id, trashed.single().id)
        assertTrue(trashed.single().page)
        store.close()

        val reopened = SketchStore(ctx)
        assertEquals(1, reopened.trash(book).size)
        reopened.restore(original.id, book)
        assertEquals(353, reopened.get(book)?.pageCount)
        assertTrue(reopened.trash(book).isEmpty())
        val revived = reopened.info(book, 352)
        assertEquals(original.id, revived.id)
        val revivedPage = reopened.page(book, 352)
        assertEquals(listOf(inkId), revivedPage.strokes.map { it.id })
        assertTrue(revivedPage.strokes.single().pts.contentEquals(inkPts))
        assertEquals(listOf(refId), revivedPage.references.map { it.id })
        assertTrue(refFile.exists())
        reopened.close()
    }

    @Test fun duplicatePageGetsIndependentInkIdsButSharesReferenceAssets() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SketchStore(ctx)
        val book = store.create("Dup")
        val index = 7
        val refFile = java.io.File(store.referencesDir, "dup-ref.webp").apply { writeText("dup") }
        val refId = store.addReference(book, index, refFile.name, 0.4f, 0.25f)
        val srcStrokeId = store.insertStroke(book, index, ink(313L))
        val sourceInfo = store.info(book, index)
        val source = store.page(book, index)
        val filesBefore = store.referencesDir.listFiles().orEmpty().size

        val dupId = store.duplicatePage(book, sourceInfo.id)
        assertEquals(index + 1, store.info(book, index + 1).index)
        val duplicate = store.page(book, index + 1)

        assertEquals(source.paper, duplicate.paper)
        assertEquals(source.spacing, duplicate.spacing, 1e-6f)
        assertNotEquals(sourceInfo.id, dupId)
        assertEquals(1, duplicate.strokes.size)
        val dupStroke = duplicate.strokes.single()
        assertNotEquals(srcStrokeId, dupStroke.id)
        assertEquals(Tools.PEN, dupStroke.tool)
        assertEquals(source.strokes.single().created, dupStroke.created)
        assertEquals(source.strokes.single().width, dupStroke.width, 1e-6f)
        assertTrue(dupStroke.pts.contentEquals(source.strokes.single().pts))

        assertEquals(1, duplicate.references.size)
        val dupRef = duplicate.references.single()
        assertNotEquals(refId, dupRef.id)
        assertEquals(source.references.single().path, dupRef.path)
        assertEquals(source.references.single().x, dupRef.x, 1e-6f)
        assertEquals(source.references.single().width, dupRef.width, 1e-6f)
        assertEquals(source.references.single().height, dupRef.height, 1e-6f)
        assertTrue(java.io.File(store.referencesDir, dupRef.path).exists())
        assertEquals(filesBefore, store.referencesDir.listFiles().orEmpty().size)

        assertTrue(SketchCodec.same(source.strokes.single(), store.page(book, index).strokes.single()))
        assertEquals(source.references, store.page(book, index).references)
        store.close()
    }

    @Test fun migrateRealV1DatabaseKeepsPaperInkReferencesAndCurrentPage() {
        val ctx = RuntimeEnvironment.getApplication()
        java.io.File(ctx.filesDir, "sketch-references" + java.io.File.separator + "v1-ref.webp")
            .apply { parentFile.mkdirs() }.writeText("v1 reference body")
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("sketchbooks.db").path.also {
            java.io.File(it).parentFile.mkdirs()
        }, null).use { db ->
            db.execSQL("PRAGMA user_version = 1")
            db.execSQL("CREATE TABLE sketchbooks(id INTEGER PRIMARY KEY,title TEXT NOT NULL,page_count INTEGER NOT NULL,current_page INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE sketch_pages(book INTEGER NOT NULL,page INTEGER NOT NULL,paper INTEGER NOT NULL,PRIMARY KEY(book,page))")
            db.execSQL("CREATE TABLE sketch_strokes(id INTEGER PRIMARY KEY,book INTEGER NOT NULL,page INTEGER NOT NULL,tool INTEGER NOT NULL,color INTEGER NOT NULL,width REAL NOT NULL,points BLOB NOT NULL,created INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE sketch_refs(id INTEGER PRIMARY KEY,book INTEGER NOT NULL,page INTEGER NOT NULL,path TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,width REAL NOT NULL,height REAL NOT NULL,opacity REAL NOT NULL)")
            db.insert("sketchbooks", null, ContentValues().apply {
                put("id", 1L); put("title", "Manuscript"); put("page_count", 350)
                put("current_page", 349); put("updated", 4242L)
            })
            db.insert("sketch_pages", null, ContentValues().apply {
                put("book", 1L); put("page", 100); put("paper", 2)
            })
            val p1 = floatArrayOf(0.1f, 0.1f, 0.5f, 0.2f, 0.2f, 0.7f)
            val p2 = floatArrayOf(0.3f, 0.1f, 0.6f, 0.4f, 0.5f, 0.8f)
            fun strokeRow(id: Long, page: Int, pts: FloatArray, created: Long) = db.insert("sketch_strokes", null, ContentValues().apply {
                put("id", id); put("book", 1L); put("page", page); put("tool", Tools.FOUNTAIN)
                put("color", 0xFF000000.toInt()); put("width", 2.0)
                put("points", SketchCodec.points(pts)); put("created", created)
            })
            assertEquals(11L, strokeRow(11L, 100, p1, 10L))
            assertTrue(strokeRow(12L, 100, p2, 20L) > 0)
            assertTrue(strokeRow(13L, 0, p1, 5L) > 0)
            db.insert("sketch_refs", null, ContentValues().apply {
                put("id", 31L); put("book", 1L); put("page", 100); put("path", "v1-ref.webp")
                put("x", 0.2); put("y", 0.3); put("width", 0.5); put("height", 0.4); put("opacity", 0.8)
            })
        }

        val store = SketchStore(ctx)
        val book = store.get(1)!!
        assertEquals("Manuscript", book.title)
        assertEquals(350, book.pageCount)
        assertEquals(349, book.currentPage)
        assertEquals(350, store.pages(1).size)

        val migrated = store.pageById(1, store.info(1, 100).id)
        assertEquals(2, migrated.paper)
        assertEquals(1f, migrated.spacing, 1e-6f)
        assertEquals(listOf(11L, 12L), migrated.strokes.map { it.id })
        assertTrue(migrated.strokes[0].pts.contentEquals(floatArrayOf(0.1f, 0.1f, 0.5f, 0.2f, 0.2f, 0.7f)))
        assertTrue(migrated.strokes[1].pts.contentEquals(floatArrayOf(0.3f, 0.1f, 0.6f, 0.4f, 0.5f, 0.8f)))
        assertEquals(Tools.FOUNTAIN, migrated.strokes[0].tool)
        assertEquals(2f, migrated.strokes[0].width, 1e-6f)
        assertEquals(listOf(13L), store.page(1, 0).strokes.map { it.id })

        val migratedRef = migrated.references.single()
        assertEquals(31L, migratedRef.id)
        assertEquals("v1-ref.webp", migratedRef.path)
        assertEquals(0.2f, migratedRef.x, 1e-6f)
        assertEquals(0.3f, migratedRef.y, 1e-6f)
        assertEquals(0.5f, migratedRef.width, 1e-6f)
        assertEquals(0.4f, migratedRef.height, 1e-6f)
        assertEquals(0.8f, migratedRef.opacity, 1e-6f)
        assertTrue(java.io.File(store.referencesDir, "v1-ref.webp").exists())

        assertTrue(store.page(1, book.currentPage).strokes.isEmpty())
        assertTrue(java.io.File(ctx.filesDir, "sketch-recovery-v1").listFiles().orEmpty().isNotEmpty())
        store.close()
    }

    @Test fun create350StoresMetadataOnlyWithoutAssetFiles() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SketchStore(ctx)
        val book = store.create("Empty")
        val created = store.get(book)!!
        assertEquals(350, created.pageCount)
        assertEquals(0, created.currentPage)
        assertEquals(0, created.defaultPaper)
        assertEquals(1f, created.defaultSpacing, 1e-6f)
        val pages = store.pages(book)
        assertEquals(350, pages.size)
        assertTrue(pages.all { !it.used })
        assertEquals(0, store.page(book, 349).strokes.size)
        assertEquals(0, store.page(book, 349).references.size)
        assertTrue(store.referencesDir.listFiles().orEmpty().isEmpty())
        assertNull(store.get(book + 1))
        store.close()
    }

    @Test fun deltaJournalHandlesUndoRedoAcrossReopenForInkTransformPaperAndReferenceRemoval() {
        val ctx = RuntimeEnvironment.getApplication()
        var store = SketchStore(ctx)
        val book = store.create("Journal")
        val pid = store.info(book, 0).id
        val refFile = java.io.File(store.referencesDir, "ref-j.png").apply { writeText("j") }
        val refId = store.addReference(book, 0, refFile.name, 0.5f, 0.4f)
        val originalPts = floatArrayOf(0.1f, 0.1f, 0.5f, 0.2f, 0.2f, 0.7f)
        val s1Id = store.insertStroke(book, 0, ink(1000L, originalPts))
        val s1 = store.pageById(book, pid).strokes.single()

        val beforeTransform = store.pageById(book, pid)
        val transformed = s1.copy(width = 4f, pts = floatArrayOf(0.05f, 0.1f, 0.5f, 0.25f, 0.25f, 0.9f))
        store.savePage(book, pid, beforeTransform, beforeTransform.copy(strokes = listOf(transformed)))
        assertEquals(4f, store.pageById(book, pid).strokes.single().width, 1e-6f)

        val beforeAdd = store.pageById(book, pid)
        val s2 = ink(2000L, width = 3f, tool = Tools.MARKER).copy(id = SketchIds.next())
        store.savePage(book, pid, beforeAdd, beforeAdd.copy(strokes = beforeAdd.strokes + s2))
        assertEquals(2, store.pageById(book, pid).strokes.size)

        store.setPaper(book, 0, 2)
        assertEquals(2, store.pageById(book, pid).paper)

        val beforeRefRemoval = store.pageById(book, pid)
        val refLayer = beforeRefRemoval.references.single()
        store.savePage(book, pid, beforeRefRemoval, beforeRefRemoval.copy(references = emptyList()))
        assertTrue(store.pageById(book, pid).references.isEmpty())
        assertTrue(store.history(pid).first)
        assertFalse(store.history(pid).second)
        store.close()

        store = SketchStore(ctx)
        assertTrue(store.pageById(book, pid).references.isEmpty())
        assertEquals(2, store.pageById(book, pid).paper)
        var page = store.undo(book, pid)
        assertEquals(listOf(refLayer), page.references)
        assertTrue(java.io.File(store.referencesDir, refLayer.path).exists())

        page = store.undo(book, pid)
        assertEquals(0, page.paper)
        assertEquals(2, page.strokes.size)

        page = store.undo(book, pid)
        assertEquals(listOf(s1Id), page.strokes.map { it.id })

        page = store.undo(book, pid)
        assertEquals(2f, page.strokes.single().width, 1e-6f)
        assertTrue(page.strokes.single().pts.contentEquals(originalPts))
        assertFalse(store.history(pid).first)
        assertTrue(store.history(pid).second)
        store.close()

        store = SketchStore(ctx)
        assertTrue(store.history(pid).second)
        page = store.undo(book, pid, redo = true)
        assertEquals(4f, page.strokes.single().width, 1e-6f)
        page = store.undo(book, pid, redo = true)
        assertEquals(listOf(s1Id, s2.id), page.strokes.map { it.id })
        page = store.undo(book, pid, redo = true)
        assertEquals(2, page.paper)
        page = store.undo(book, pid, redo = true)
        assertTrue(page.references.isEmpty())
        assertTrue(store.history(pid).first)
        assertFalse(store.history(pid).second)
        store.close()

        store = SketchStore(ctx)
        assertTrue(store.history(pid).first)
        assertFalse(store.history(pid).second)
        val final = store.pageById(book, pid)
        assertEquals(2, final.paper)
        assertTrue(final.references.isEmpty())
        assertEquals(2, final.strokes.size)
        assertEquals(4f, final.strokes.first().width, 1e-6f)
        store.close()
    }

    @Test fun newEditInvalidatesRedoStack() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SketchStore(ctx)
        val book = store.create("Fresh")
        val pid = store.info(book, 0).id
        val before = store.pageById(book, pid)
        store.savePage(book, pid, before, before.copy(strokes = listOf(ink(777L).copy(id = SketchIds.next()))))
        assertEquals(1, store.pageById(book, pid).strokes.size)

        store.undo(book, pid)
        assertTrue(store.pageById(book, pid).strokes.isEmpty())
        assertFalse(store.history(pid).first)
        assertTrue(store.history(pid).second)

        store.setPaper(book, 0, 3)
        assertTrue(store.history(pid).first)
        assertFalse(store.history(pid).second)

        store.undo(book, pid, redo = true)
        assertEquals(3, store.pageById(book, pid).paper)
        assertTrue(store.pageById(book, pid).strokes.isEmpty())

        val page = store.undo(book, pid)
        assertEquals(0, page.paper)
        store.close()
    }

    @Test fun historyIsBoundedToOneHundredActions() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SketchStore(ctx)
        val book = store.create("Ledger")
        val pid = store.info(book, 0).id
        repeat(105) { i -> store.setPaper(book, 0, i % 3 + 1) }
        assertEquals(3, store.pageById(book, pid).paper)
        var page = store.pageById(book, pid)
        repeat(100) { page = store.undo(book, pid) }
        assertEquals(2, page.paper)
        page = store.undo(book, pid)
        assertEquals(2, page.paper)
        assertFalse(store.history(pid).first)
        assertTrue(store.history(pid).second)
        store.close()

        val reopened = SketchStore(ctx)
        assertFalse(reopened.history(pid).first)
        assertTrue(reopened.history(pid).second)
        reopened.close()
    }

    @Test fun failedWriteRollsBackTransactionCompletely() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SketchStore(ctx)
        val book = store.create("Rollback")
        try {
            store.transaction {
                store.rename(book, "Scrambled")
                store.setPaper(book, 0, 3)
                store.insertStroke(book, 0, ink(4242L))
                error("simulated write failure")
            }
            fail("Transaction block should rethrow the failure")
        } catch (expected: IllegalStateException) {
            assertEquals("simulated write failure", expected.message)
        }
        val after = store.get(book)!!
        assertEquals("Rollback", after.title)
        assertEquals(350, after.pageCount)
        assertEquals(0, store.page(book, 0).paper)
        assertEquals(0, store.page(book, 0).strokes.size)
        store.close()
    }
}
