package com.rotfarmer.ereader.sketch

import android.view.InputDevice
import android.view.MotionEvent
import com.rotfarmer.ereader.data.Settings
import com.rotfarmer.ereader.ink.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SketchInputTest {
    private fun event(action:Int,tools:IntArray,x:FloatArray,y:FloatArray,flags:Int=0):MotionEvent {
        val props=tools.mapIndexed { i,t->MotionEvent.PointerProperties().apply { id=i;toolType=t } }.toTypedArray()
        val coords=tools.indices.map { i->MotionEvent.PointerCoords().apply { this.x=x[i];this.y=y[i];pressure=.7f;size=.1f } }.toTypedArray()
        return MotionEvent.obtain(0,10,action,tools.size,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,flags)
    }
    private fun send(canvas:SketchCanvas,action:Int,tools:IntArray=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS),x:FloatArray=floatArrayOf(400f),y:FloatArray=floatArrayOf(500f),flags:Int=0){val e=event(action,tools,x,y,flags);try{canvas.onTouchEvent(e)}finally{e.recycle()}}
    private fun canvas(settings:Settings,commits:MutableList<SketchPage>)=SketchCanvas(RuntimeEnvironment.getApplication(),settings,RuntimeEnvironment.getApplication().cacheDir,{_,after->commits+=after},{_,_,_,_->}).apply { layout(0,0,1000,1450) }
    @Test fun canceledStrokeNeverCommits(){val changes=mutableListOf<SketchPage>();val settings=Settings(RuntimeEnvironment.getApplication()).apply { tool=Tools.PEN;fingerDraw=false };val c=canvas(settings,changes)
        send(c,MotionEvent.ACTION_DOWN);send(c,MotionEvent.ACTION_MOVE,x=floatArrayOf(500f));send(c,MotionEvent.ACTION_CANCEL)
        assertTrue(changes.isEmpty());assertTrue(c.page.strokes.isEmpty());c.dispose()
    }
    @Test fun stylusCanStartAfterPalmAndPalmCannotTurnPage(){val changes=mutableListOf<SketchPage>();val settings=Settings(RuntimeEnvironment.getApplication()).apply { tool=Tools.PEN;fingerDraw=false };val c=canvas(settings,changes)
        send(c,MotionEvent.ACTION_DOWN,intArrayOf(MotionEvent.TOOL_TYPE_FINGER))
        val tools=intArrayOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_STYLUS)
        send(c,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),tools,floatArrayOf(400f,450f),floatArrayOf(500f,550f))
        send(c,MotionEvent.ACTION_MOVE,tools,floatArrayOf(400f,550f),floatArrayOf(500f,650f))
        send(c,MotionEvent.ACTION_POINTER_UP or (1 shl 8),tools,floatArrayOf(400f,550f),floatArrayOf(500f,650f))
        send(c,MotionEvent.ACTION_UP,intArrayOf(MotionEvent.TOOL_TYPE_FINGER))
        assertEquals(1,changes.size);assertEquals(1,c.page.strokes.size);assertTrue(c.page.strokes.single().pts.size>=6);c.dispose()
    }
    @Test fun canceledEraserPreservesExistingInk(){val changes=mutableListOf<SketchPage>();val settings=Settings(RuntimeEnvironment.getApplication()).apply { tool=Tools.ERASER;fingerDraw=false };val c=canvas(settings,changes)
        val ink=InkStroke(1,1,Anchor(Anchor.PDF,1,0),Tools.PEN,0xff000000.toInt(),.01f,floatArrayOf(.1f,.5f,.7f,.9f,.5f,.7f),0)
        c.setPage(SketchPage(0,emptyList(),listOf(ink)))
        send(c,MotionEvent.ACTION_DOWN,x=floatArrayOf(500f),y=floatArrayOf(500f));send(c,MotionEvent.ACTION_CANCEL)
        assertTrue(changes.isEmpty());assertEquals(ink,c.page.strokes.single());c.dispose()
    }
    @Test fun secondFingerCancelsFingerInkAndBeginsNavigation(){val changes=mutableListOf<SketchPage>();val settings=Settings(RuntimeEnvironment.getApplication()).apply { tool=Tools.PEN;fingerDraw=true };val c=canvas(settings,changes)
        send(c,MotionEvent.ACTION_DOWN,intArrayOf(MotionEvent.TOOL_TYPE_FINGER))
        val tools=intArrayOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_FINGER)
        send(c,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),tools,floatArrayOf(400f,600f),floatArrayOf(500f,500f))
        send(c,MotionEvent.ACTION_MOVE,tools,floatArrayOf(300f,700f),floatArrayOf(500f,500f))
        send(c,MotionEvent.ACTION_UP,intArrayOf(MotionEvent.TOOL_TYPE_FINGER))
        assertTrue(changes.isEmpty());assertTrue(c.zoom>1f);c.dispose()
    }
}
