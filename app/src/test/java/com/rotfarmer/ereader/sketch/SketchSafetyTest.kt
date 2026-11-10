package com.rotfarmer.ereader.sketch

import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkStroke
import com.rotfarmer.ereader.ink.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Guards against losing work when two windows edit the same page, and against storage limits. */
@RunWith(RobolectricTestRunner::class)
class SketchSafetyTest {
    private fun ink(points: Int = 2) = InkStroke(SketchIds.next(), 0L, Anchor(Anchor.PDF, 0L, 0), Tools.PEN, 0xFF112233.toInt(), .003f,
        FloatArray(points * 3) { i -> if (i % 3 == 2) .5f else (i % 7) / 10f }, System.currentTimeMillis())

    private fun reference(name: String) = ReferenceLayer(SketchIds.next(), name, .1f, .1f, .5f, .4f, 1f)

    @Test fun staleEditorCopyKeepsReferencesAndPaperItNeverSaw() {
        val store = SketchStore(RuntimeEnvironment.getApplication())
        val book = store.create("Two windows")
        val pid = store.info(book, 3).id
        val stale = store.pageById(book, pid)

        // Window B imports an excerpt and changes the paper.
        val imported = reference("excerpt.png")
        val fresh = store.pageById(book, pid)
        store.savePage(book, pid, fresh, fresh.copy(references = listOf(imported), paper = 2))

        // Window A still shows the old page and draws a stroke on it.
        val stroke = ink()
        store.savePage(book, pid, stale, stale.copy(strokes = listOf(stroke)))

        val page = store.pageById(book, pid)
        assertEquals(listOf(imported.id), page.references.map { it.id })
        assertEquals(2, page.paper)
        assertEquals(listOf(stroke.id), page.strokes.map { it.id })

        // Undoing A's stroke leaves B's import alone.
        val undone = store.undo(book, pid)
        assertTrue(undone.strokes.isEmpty())
        assertEquals(listOf(imported.id), undone.references.map { it.id })
        store.close()
    }

    @Test fun editingOneReferenceLeavesTheOthersAsStored() {
        val store = SketchStore(RuntimeEnvironment.getApplication())
        val book = store.create("References")
        val pid = store.info(book, 0).id
        val a = reference("a.png")
        val empty = store.pageById(book, pid)
        store.savePage(book, pid, empty, empty.copy(references = listOf(a)))
        val seen = store.pageById(book, pid)

        // Meanwhile another window adds a second reference.
        val b = reference("b.png")
        val current = store.pageById(book, pid)
        store.savePage(book, pid, current, current.copy(references = current.references + b))

        // This window only knows about `a` and changes its opacity.
        store.savePage(book, pid, seen, seen.copy(references = listOf(seen.references.single().copy(opacity = .4f))))
        val refs = store.pageById(book, pid).references.associateBy { it.id }
        assertEquals(setOf(a.id, b.id), refs.keys)
        assertEquals(.4f, refs.getValue(a.id).opacity, 1e-6f)
        assertEquals(1f, refs.getValue(b.id).opacity, 1e-6f)
        store.close()
    }

    @Test fun largeEditCanBeUndoneAndRedone() {
        val store = SketchStore(RuntimeEnvironment.getApplication())
        val book = store.create("Dense")
        val pid = store.info(book, 0).id
        // Roughly 5 MB of journal text: more than one cursor window can return in a single row.
        val strokes = List(300) { ink(points = 1000) }
        val empty = store.pageById(book, pid)
        store.savePage(book, pid, empty, empty.copy(strokes = strokes))
        assertEquals(300, store.pageById(book, pid).strokes.size)
        assertTrue(store.undo(book, pid).strokes.isEmpty())
        assertEquals(300, store.undo(book, pid, redo = true).strokes.size)
        store.close()
    }

    @Test fun pagesStopAtTheLimitThatBackupsCanRestore() {
        val store = SketchStore(RuntimeEnvironment.getApplication())
        val book = store.create("Almost full", SketchStore.MAX_PAGES - 10)
        store.addPages(book, 10)
        assertEquals(SketchStore.MAX_PAGES, store.get(book)!!.pageCount)
        assertThrows(IllegalArgumentException::class.java) { store.addPages(book, 1) }
        assertThrows(IllegalArgumentException::class.java) { store.insertPages(book, 0, 1) }
        assertEquals(SketchStore.MAX_PAGES, store.get(book)!!.pageCount)
        store.close()
    }

    @Test fun cleanupKeepsPicturesThatUndoCanStillBringBack() {
        val store = SketchStore(RuntimeEnvironment.getApplication())
        val book = store.create("Cleanup")
        val pid = store.info(book, 0).id
        val twoDaysAgo = System.currentTimeMillis() - 2 * 24 * 3600_000L
        fun file(name: String) = File(store.referencesDir, name).apply { writeBytes(byteArrayOf(1, 2, 3)); setLastModified(twoDaysAgo) }
        val live = file("live-${SketchIds.next()}.png")
        val undoable = file("undoable-${SketchIds.next()}.png")
        val orphan = file("orphan-${SketchIds.next()}.png")

        val keep = reference(live.name)
        val removed = reference(undoable.name)
        val empty = store.pageById(book, pid)
        store.savePage(book, pid, empty, empty.copy(references = listOf(keep, removed)))
        val both = store.pageById(book, pid)
        store.savePage(book, pid, both, both.copy(references = both.references.filter { it.id == keep.id }))

        store.purgeExpired()
        assertTrue(live.exists())
        assertTrue(undoable.exists())
        assertFalse(orphan.exists())
        // And undo really can bring it back.
        assertEquals(setOf(keep.id, removed.id), store.undo(book, pid).references.map { it.id }.toSet())
        store.close()
    }
}
