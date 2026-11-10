package com.rotfarmer.ereader.sketch

import android.graphics.Matrix
import android.graphics.PointF
import com.rotfarmer.ereader.ink.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.*
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
class SketchEditingTest {
    @Test fun normalizedPaperBorderStaysOnePixelWide() {
        var borderWidth = 0f
        val canvas = object : android.graphics.Canvas() {
            override fun drawRect(rect: android.graphics.RectF, paint: android.graphics.Paint) {
                if (paint.style == android.graphics.Paint.Style.STROKE) borderWidth = paint.strokeWidth
            }
        }
        val pixelsPerUnit = 1000f
        SketchPaper.draw(canvas, android.graphics.RectF(0f, 0f, 1f, SketchGeometry.HEIGHT),
            0, 1f / pixelsPerUnit, dark = true)
        assertEquals(1f, borderWidth * pixelsPerUnit, 0.01f)
    }

    private fun stroke()=InkStroke(SketchIds.next(),1,Anchor(Anchor.PDF,0,0),Tools.PEN,0xff112233.toInt(),.002f,floatArrayOf(0f,.5f,.2f,1f,.5f,.8f),123)
    @Test fun partialEraserCutsSparseSegmentsAndKeepsPressure(){val s=stroke();val pieces=SketchGeometry.erase(s,.5f,.5f,.1f)
        assertEquals(2,pieces.size);assertEquals(0f,pieces[0].pts[0],.0001f);assertEquals(1f,pieces[1].pts[pieces[1].pts.size-3],.0001f)
        assertTrue(pieces[0].pts[pieces[0].pts.size-3]<.5f);assertTrue(pieces[1].pts[0]>.5f)
        assertEquals(.2f,pieces[0].pts[2],.0001f);assertEquals(.8f,pieces[1].pts.last(),.0001f)
        assertTrue(pieces.none { it.id==s.id });assertEquals(6,s.pts.size)
    }
    @Test fun selectionTransformDoesNotMutateOriginalInk(){val s=stroke();val m=Matrix().apply { setScale(2f,2f);postTranslate(.1f,.2f) };val changed=SketchGeometry.transform(s,m,2f)
        assertEquals(.1f,changed.pts[0],.0001f);assertEquals(1.2f,changed.pts[1],.0001f);assertEquals(.004f,changed.width,.0001f)
        assertEquals(0f,s.pts[0],0f);assertEquals(.5f,s.pts[1],0f);assertEquals(s.color,changed.color)
    }
    @Test fun shapeAndRangeSelectionValidateInput(){assertEquals(15,SketchGeometry.shape("Rectangle",PointF(0f,0f),PointF(1f,1f)).size)
        assertEquals(listOf(0,2,3,4),SketchImportPanel.parse("1,3-5,3",350))
        assertThrows(IllegalArgumentException::class.java){SketchImportPanel.parse("350-351",350)}
    }
    @Test fun archiveRoundTripIsEditableAndDoesNotOverwriteSource(){val ctx=RuntimeEnvironment.getApplication();SketchStore(ctx).use { store->
        val book=store.create("Backup roundtrip");val info=store.info(book,42);val empty=store.pageById(book,info.id)
        val file=File(store.referencesDir,"roundtrip-${SketchIds.next()}.png").apply { writeBytes(byteArrayOf(1,2,3)) }
        val ink=stroke();val ref=ReferenceLayer(SketchIds.next(),file.name,.1f,.2f,.4f,.6f,.3f,rotation=15f,docked=true)
        store.savePage(book,info.id,empty,empty.copy(paper=2,strokes=listOf(ink),references=listOf(ref),spacing=1.5f))
        store.metadata(info.copy(title="Diagram",section="Study",bookmarked=true));store.setCurrentPage(book,42)
        val bytes=ByteArrayOutputStream();SketchArchive.write(store,book,bytes)
        val restored=SketchArchive.restore(store,ByteArrayInputStream(bytes.toByteArray()));assertNotEquals(book,restored)
        assertEquals(350,store.get(restored)!!.pageCount);assertEquals(42,store.get(restored)!!.currentPage)
        val restoredInfo=store.info(restored,42);assertEquals("Diagram",restoredInfo.title);assertTrue(restoredInfo.bookmarked)
        val page=store.pageById(restored,restoredInfo.id);assertEquals(2,page.paper);assertEquals(1.5f,page.spacing,0f)
        assertArrayEquals(ink.pts,page.strokes.single().pts,0f);assertNotEquals(ink.id,page.strokes.single().id)
        assertNotEquals(ref.path,page.references.single().path);assertTrue(page.references.single().docked)
        assertEquals(false to false,store.history(restoredInfo.id));assertEquals(ink.id,store.page(book,42).strokes.single().id)
    } }
    @Test fun malformedArchiveCannotCreateOrOverwriteBooks(){val ctx=RuntimeEnvironment.getApplication();SketchStore(ctx).use { store->
        val book=store.create("Keep me");val count=store.list().size
        val output=ByteArrayOutputStream();ZipOutputStream(output).use { zip->zip.putNextEntry(ZipEntry("../sketchbooks.db"));zip.write(byteArrayOf(1));zip.closeEntry() }
        assertThrows(IllegalArgumentException::class.java){SketchArchive.restore(store,ByteArrayInputStream(output.toByteArray()))}
        assertEquals(count,store.list().size);assertEquals("Keep me",store.get(book)!!.title)
    } }
}
