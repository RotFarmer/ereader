package com.rotfarmer.ereader.sketch

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import com.rotfarmer.ereader.data.Settings
import com.rotfarmer.ereader.ink.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

/** One page, one invertible transform, independent stylus and navigation gestures. */
class SketchCanvas(context:Context,private val settings:Settings,private val assets:File,
    private val commit:(SketchPage,SketchPage)->Unit,private val viewport:(Float,Float,Float,Float)->Unit):View(context) {
    var page=SketchPage(0,emptyList(),emptyList());private set
    var bookId=0L
    var pageId=0L
    var enabledForInput=true
    var mode=DRAW
        set(value){field=value;selected.clear();onSelectionChanged?.invoke();invalidate()}
    /** Called whenever the lasso selection changes, so the screen can offer copy/delete. */
    var onSelectionChanged:(()->Unit)?=null
    val selectionCount get()=selected.size
    val canPaste get()=clipboard.isNotEmpty()
    var partialEraser=false
    var referenceId:Long?=null
    /** Called with +1 / -1 when a finger swipes across the page at normal zoom. */
    var onSwipe:((Int)->Unit)?=null
    var zoom=1f;private set
    var pageRotation=0f;private set
    private var panX=0f;private var panY=0f
    private var unit=1f
    private val matrix=Matrix();private val inverse=Matrix()
    private val painter=InkPainter()
    private val strokePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE }
    private val shadowPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL }
    private val main=Handler(Looper.getMainLooper());private val executor=Executors.newSingleThreadExecutor()
    /** Reference pictures at the resolution the current zoom needs, sized from the app's memory. */
    private val cacheBytes=(Runtime.getRuntime().maxMemory()/8).coerceIn(24L shl 20,96L shl 20).toInt()
    private val bitmaps=object:LruCache<String,Bitmap>(cacheBytes){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
    private val pending=mutableSetOf<String>();private var disposed=false;private var generation=0
    /** Decode attempts per key, so a picture that can't stay cached is not decoded in a loop. */
    private val attempts=HashMap<String,Int>()
    private var swipeX=0f;private var swipeY=0f;private var swiping=false
    private var rotating=false;private var rotationSlack=0f
    private var lastStylusTime=0L
    /** A hand resting on the screen while the S Pen hovers or has just lifted is a palm, not a swipe. */
    private fun palmLikely()=android.os.SystemClock.uptimeMillis()-lastStylusTime<450 || hover!=null
    private var penId=-1;private var fingerId=-1;private var fingers=false
    private var penIsFinger=false
    private var initialStrokeIds=emptySet<Long>()
    private var live=ArrayList<Float>();private val lasso=mutableListOf<PointF>();private val selected=mutableSetOf<Long>()
    private var working:SketchPage?=null;private var gesturePage:SketchPage?=null
    private var index=SketchStrokeIndex(emptyList())
    private var start=PointF();private var last=PointF()
    private var gestureTool=Tools.PEN;private var widthUnits=.003f
    private var startZoom=1f;private var startRotation=0f;private var startPanX=0f;private var startPanY=0f
    private var pinchDistance=0f;private var pinchAngle=0f;private var pinchCenter=PointF();private var pinchAnchor=PointF()
    private var transformKind="";private var selectionStart=RectF();private var referenceStart:ReferenceLayer?=null
    private var clipboard=emptyList<InkStroke>()
    private var hover:PointF?=null
    fun setPage(value:SketchPage,info:PageInfo?=null) {
        page=value;working=null;gesturePage=null;live.clear();lasso.clear();index=SketchStrokeIndex(page.strokes)
        if(info!=null) { pageId=info.id;bookId=info.book;zoom=info.zoom;pageRotation=info.rotation;panX=info.panX;panY=info.panY;selected.clear();referenceId=null;generation++;attempts.clear();onSelectionChanged?.invoke() }
        invalidate()
    }
    fun fit(){zoom=1f;pageRotation=0f;panX=0f;panY=0f;viewport(zoom,panX,panY,pageRotation);invalidate()}
    fun resetRotation(){pageRotation=0f;viewport(zoom,panX,panY,pageRotation);invalidate()}
    fun dispose(){disposed=true;generation++;executor.shutdownNow();bitmaps.evictAll();pending.clear()}
    fun clearSelection(){selected.clear();onSelectionChanged?.invoke();invalidate()}
    fun copySelection(){clipboard=page.strokes.filter { it.id in selected }}
    fun paste(){if(clipboard.isEmpty())return;val m=Matrix().apply { setTranslate(.03f,.03f) };val added=clipboard.map { SketchGeometry.transform(it,m).copy(id=SketchIds.next()) };selected.clear();selected.addAll(added.map { it.id });change(page.copy(strokes=page.strokes+added));onSelectionChanged?.invoke()}
    fun duplicateSelection(){copySelection();paste()}
    fun deleteSelection(){if(selected.isEmpty())return;change(page.copy(strokes=page.strokes.filterNot { it.id in selected }));selected.clear();onSelectionChanged?.invoke()}
    private fun change(next:SketchPage) { val before=page;page=next;working=null;index=SketchStrokeIndex(page.strokes);commit(before,next);invalidate() }
    private fun updateMatrix() {
        unit=min(width-32f*resources.displayMetrics.density,(height-24f*resources.displayMetrics.density)/SketchGeometry.HEIGHT).coerceAtLeast(1f)
        matrix.reset();matrix.setTranslate(-.5f,-SketchGeometry.HEIGHT/2);matrix.postScale(unit*zoom,unit*zoom);matrix.postRotate(pageRotation)
        matrix.postTranslate(width/2f+panX*unit,height/2f+panY*unit);matrix.invert(inverse)
    }
    private fun point(x:Float,y:Float):PointF { val p=floatArrayOf(x,y);inverse.mapPoints(p);return PointF(p[0],p[1]) }
    private fun bounds(strokes:List<InkStroke>):RectF { val r=RectF();strokes.forEachIndexed { i,s -> if(i==0)r.set(s.bounds) else r.union(s.bounds) };return r }
    override fun onDraw(c:Canvas) {
        super.onDraw(c);updateMatrix();c.drawColor(if(settings.dark)0xFF0B0B0B.toInt() else 0xFFE4E1D8.toInt())
        c.save();c.concat(matrix)
        // A soft shadow so the sheet sits on the desk.
        val px=1f/(unit*zoom);val d=resources.displayMetrics.density
        shadowPaint.color=if(settings.dark)0x66000000 else 0x1A000000
        for(k in 1..3){val o=k*1.2f*d*px;c.drawRect(-o*.5f,o*.2f,1f+o*.5f,SketchGeometry.HEIGHT+o,shadowPaint)}
        val current=working ?: page
        SketchRenderer.draw(c,current,unit,settings.dark,settings.pressure,painter,unit*zoom) { ref -> bitmap(ref) }
        painter.dark=settings.dark;painter.sensitivity=settings.pressure
        if(live.isNotEmpty()) painter.drawLive(c,live.toFloatArray(),live.size/3,gestureTool,settings.toolColor(gestureTool),widthUnits,unit*zoom)
        strokePaint.color=if(settings.dark)0xFFADC8BF.toInt() else 0xFF476A5D.toInt();strokePaint.strokeWidth=2f/(unit*zoom)
        if(lasso.size>1) { val p=Path();p.moveTo(lasso[0].x,lasso[0].y);lasso.drop(1).forEach { p.lineTo(it.x,it.y) };c.drawPath(p,strokePaint) }
        if(selected.isNotEmpty())drawHandles(c,bounds(current.strokes.filter { it.id in selected }))
        referenceId?.let { id -> current.references.firstOrNull { it.id==id }?.let { r -> c.save();c.rotate(r.rotation,r.x+r.width/2,r.y+r.height/2);drawHandles(c,RectF(r.x,r.y,r.x+r.width,r.y+r.height));c.restore() } }
        hover?.let { if(settings.tool==Tools.ERASER)c.drawCircle(it.x,it.y,settings.toolWidth(Tools.ERASER)*resources.displayMetrics.density/unit/2,strokePaint) }
        c.restore()
    }
    private fun drawHandles(c:Canvas,r:RectF){c.drawRect(r,strokePaint);val radius=7f*resources.displayMetrics.density/(unit*zoom);c.drawCircle(r.right,r.bottom,radius,strokePaint);c.drawCircle(r.centerX(),r.top-radius*3,radius,strokePaint)}
    private fun bitmap(ref:ReferenceLayer):Bitmap? {
        // Every visible reference must fit in the cache at once, or they would keep evicting
        // each other and be decoded forever. The page-space aspect matches the picture's.
        val visible=(working ?: page).references.count { it.visible && !it.docked }.coerceAtLeast(1)
        val aspect=(ref.height/ref.width).takeIf { it.isFinite() }?.coerceIn(.05f,20f) ?: 1.4f
        val affordable=sqrt(cacheBytes/(visible+1)/4f/aspect).toInt()
        val pixels=(unit*zoom*ref.width).toInt().coerceIn(128,3072)
        val bucket=(((pixels+255)/256)*256).coerceAtMost(maxOf(256,affordable/256*256))
        val key="${ref.source}|${ref.path}|${ref.sourcePage}|$bucket"
        bitmaps.get(key)?.let { return it }
        val tries=attempts[key] ?: 0
        if(tries<2 && pending.add(key) && !disposed) { attempts[key]=tries+1;if(attempts.size>500)attempts.clear();val request=generation;executor.execute {
            val result=runCatching { SketchRenderer.decode(assets,ref,bucket) }.getOrNull()
            main.post { pending.remove(key);if(disposed || request!=generation)result?.recycle() else if(result!=null){bitmaps.put(key,result);invalidate()} }
        } }
        // Keep the previous resolution visible while a sharper decode is pending.
        return bitmaps.snapshot().entries.firstOrNull { it.key.startsWith("${ref.source}|${ref.path}|${ref.sourcePage}|") }?.value
    }
    override fun onHoverEvent(e:MotionEvent):Boolean { lastStylusTime=android.os.SystemClock.uptimeMillis();updateMatrix();hover=if(e.actionMasked==MotionEvent.ACTION_HOVER_EXIT)null else point(e.x,e.y);invalidate();return true }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(!enabledForInput)return true
        updateMatrix();val ai=e.actionIndex;val action=e.actionMasked
        val stylus=e.getToolType(ai)==MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(ai)==MotionEvent.TOOL_TYPE_ERASER
        val canceled=android.os.Build.VERSION.SDK_INT>=33 && e.flags and MotionEvent.FLAG_CANCELED!=0
        if(stylus)lastStylusTime=android.os.SystemClock.uptimeMillis()
        if(action==MotionEvent.ACTION_CANCEL || ((action==MotionEvent.ACTION_POINTER_UP || action==MotionEvent.ACTION_UP) && canceled && e.getPointerId(ai)==penId)) {
            cancelGesture();return true
        }
        if((action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) && stylus) {
            if(fingers){zoom=startZoom;panX=startPanX;panY=startPanY;pageRotation=startRotation;working=null;updateMatrix()}
            fingers=false;beginPen(e,ai);return true
        }
        if(penId>=0 && penIsFinger && action==MotionEvent.ACTION_POINTER_DOWN && e.pointerCount==2) {
            cancelGesture();fingers=true;fingerId=e.getPointerId(0);gesturePage=page
            startZoom=zoom;startRotation=pageRotation;startPanX=panX;startPanY=panY
        }
        if(penId>=0) {
            val pi=e.findPointerIndex(penId)
            if(pi<0){cancelGesture();return true}
            if(action==MotionEvent.ACTION_MOVE){sample(e,pi);invalidate()}
            if((action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) && e.getPointerId(ai)==penId){sample(e,pi);finishPen();penId=-1;invalidate()}
            return true
        }
        if(settings.fingerDraw && mode!="Lasso" && referenceId==null && e.pointerCount==1 && action==MotionEvent.ACTION_DOWN) { beginPen(e,ai);return true }
        if(action==MotionEvent.ACTION_DOWN) {
            fingers=true;fingerId=e.getPointerId(0);start=point(e.x,e.y);last=PointF(e.x,e.y);rotating=false
            startZoom=zoom;startRotation=pageRotation;startPanX=panX;startPanY=panY;pinchDistance=0f;gesturePage=page;working=null
            referenceStart=page.references.firstOrNull { it.id==referenceId && !it.locked }
            selectionStart=bounds(page.strokes.filter { it.id in selected })
            val box=referenceStart?.let { RectF(it.x,it.y,it.x+it.width,it.y+it.height) } ?: selectionStart
            val threshold=24f*resources.displayMetrics.density/(unit*zoom)
            transformKind=when { box.isEmpty -> "";hypot(start.x-box.right,start.y-box.bottom)<threshold -> "scale"
                hypot(start.x-box.centerX(),start.y-box.top+threshold*.875f)<threshold -> "rotate";box.contains(start.x,start.y)->"move";else->"" }
            if(referenceStart!=null && transformKind=="")referenceStart=null
            // At normal zoom the whole page is visible: a one-finger swipe turns the page instead of panning.
            swiping=transformKind=="" && zoom<=1.01f && onSwipe!=null && !palmLikely();swipeX=e.x;swipeY=e.y
        } else if(fingers && action==MotionEvent.ACTION_POINTER_DOWN && e.pointerCount==2) {
            swiping=false;rotating=false
            pinchDistance=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0)).coerceAtLeast(1f)
            pinchAngle=atan2(e.getY(1)-e.getY(0),e.getX(1)-e.getX(0))*180f/PI.toFloat()
            pinchCenter=PointF((e.getX(0)+e.getX(1))/2,(e.getY(0)+e.getY(1))/2);pinchAnchor=point(pinchCenter.x,pinchCenter.y)
            startZoom=zoom;startRotation=pageRotation;startPanX=panX;startPanY=panY
        } else if(fingers && action==MotionEvent.ACTION_MOVE) {
            if(e.pointerCount>=2 && pinchDistance>0) {
                val ratio=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0))/pinchDistance
                val angle=normalizeAngle(atan2(e.getY(1)-e.getY(0),e.getX(1)-e.getX(0))*180f/PI.toFloat()-pinchAngle)
                val cx=(e.getX(0)+e.getX(1))/2;val cy=(e.getY(0)+e.getY(1))/2
                if(referenceStart!=null) { val r=referenceStart!!;val scale=ratio.coerceIn(.05f,20f);previewReference(r.copy(width=r.width*scale,height=r.height*scale,rotation=r.rotation+angle)) }
                else {
                    // Zooming shouldn't tilt the page: rotation starts only after a clear twist.
                    if(!rotating && abs(angle)>ROTATE_THRESHOLD){rotating=true;rotationSlack=if(angle>0)ROTATE_THRESHOLD else -ROTATE_THRESHOLD}
                    zoom=(startZoom*ratio).coerceIn(1f,8f);pageRotation=if(rotating)startRotation+angle-rotationSlack else startRotation
                    updateMatrix();val p=floatArrayOf(pinchAnchor.x,pinchAnchor.y);matrix.mapPoints(p);panX+=(cx-p[0])/unit;panY+=(cy-p[1])/unit
                }
            } else if(pinchDistance==0f) {
                val pi=e.findPointerIndex(fingerId);if(pi<0)return true
                val now=point(e.getX(pi),e.getY(pi))
                if(transformKind.isNotBlank())transform(now)
                else if(!swiping) { panX+=(e.getX(pi)-last.x)/unit;panY+=(e.getY(pi)-last.y)/unit }
                last=PointF(e.getX(pi),e.getY(pi))
            };invalidate()
        } else if(fingers && (action==MotionEvent.ACTION_UP || (action==MotionEvent.ACTION_POINTER_UP && canceled))) {
            if(canceled)cancelGesture() else {
                working?.let { change(it) }
                if(rotating) { val straight=Math.round(pageRotation/90f)*90f;if(abs(pageRotation-straight)<SNAP_DEGREES)pageRotation=straight }
                viewport(zoom,panX,panY,pageRotation);fingers=false;gesturePage=null;working=null
                val dx=e.x-swipeX;val dy=e.y-swipeY
                if(swiping && action==MotionEvent.ACTION_UP && abs(dx)>maxOf(width*.12f,64f*resources.displayMetrics.density) && abs(dx)>abs(dy)*1.5f)onSwipe?.invoke(if(dx<0)1 else -1)
                swiping=false;rotating=false
            }
            invalidate()
        }
        return true
    }
    private fun beginPen(e:MotionEvent,i:Int) {
        penId=e.getPointerId(i);penIsFinger=e.getToolType(i)==MotionEvent.TOOL_TYPE_FINGER;requestUnbufferedDispatch(e);gesturePage=page;working=null;live.clear();lasso.clear()
        gestureTool=if(e.getToolType(i)==MotionEvent.TOOL_TYPE_ERASER || e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY!=0)Tools.ERASER else settings.tool
        widthUnits=settings.toolWidth(gestureTool)*resources.displayMetrics.density/unit
        start=point(e.getX(i),e.getY(i));index=SketchStrokeIndex(page.strokes);initialStrokeIds=page.strokes.mapTo(hashSetOf()){it.id}
        if(start.x !in 0f..1f || start.y !in 0f..SketchGeometry.HEIGHT){penId=-1;return}
        sample(e,i)
    }
    private fun sample(e:MotionEvent,i:Int) {
        fun add(x:Float,y:Float,pressure:Float){val p=point(x,y)
            if(mode=="Lasso" && gestureTool!=Tools.ERASER){lasso+=p;return}
            if(gestureTool==Tools.ERASER){erase(p);return}
            if(mode!="Draw" && mode!="Lasso"){live=ArrayList(SketchGeometry.shape(mode,start,p).toList());return}
            live+=p.x;live+=p.y;live+=pressure.coerceIn(.01f,1f)
        }
        for(h in 0 until e.historySize)add(e.getHistoricalX(i,h),e.getHistoricalY(i,h),e.getHistoricalPressure(i,h))
        add(e.getX(i),e.getY(i),e.getPressure(i))
    }
    private fun erase(p:PointF) {
        val current=working ?: page;val radius=widthUnits/2
        val ids=index.candidates(p.x,p.y,radius)
        // Fragments created during this gesture are considered too; only the untouched set needs indexing.
        val next=current.strokes.flatMap { s -> if((s.id in ids || s.id !in initialStrokeIds) && SketchGeometry.hit(s,p.x,p.y,radius)) {
            if(partialEraser)SketchGeometry.erase(s,p.x,p.y,radius) else emptyList()
        } else listOf(s) };working=current.copy(strokes=next)
    }
    private fun finishPen() {
        if(mode=="Lasso" && gestureTool!=Tools.ERASER) {
            selected.clear();if(lasso.size>2)page.strokes.forEach { s -> if((s.pts.indices step 3).any { SketchGeometry.inside(s.pts[it],s.pts[it+1],lasso) })selected+=s.id }
            onSelectionChanged?.invoke()
        } else if(gestureTool==Tools.ERASER)working?.let { change(it) }
        else if(live.isNotEmpty()) { val stroke=InkStroke(SketchIds.next(),bookId,Anchor(Anchor.PDF,pageId,0),gestureTool,settings.toolColor(gestureTool),widthUnits,live.toFloatArray(),System.currentTimeMillis());change(page.copy(strokes=page.strokes+stroke)) }
        live.clear();lasso.clear();gesturePage=null;working=null
    }
    companion object {
        private const val ROTATE_THRESHOLD=15f;private const val SNAP_DEGREES=8f
        const val DRAW="Draw";const val LASSO="Lasso"
        const val LINE="Straightedge";const val ARROW="Arrow";const val RECTANGLE="Rectangle";const val ELLIPSE="Ellipse"
        val SHAPES=listOf(LINE,ARROW,RECTANGLE,ELLIPSE)
        /** Material icon for a shape mode, or null for freehand and lasso. */
        fun shapeIcon(mode:String):String?=when(mode){LINE->"\uf108";ARROW->"\ue941";RECTANGLE->"\ue835";ELLIPSE->"\ue40c";else->null}
    }
    private fun previewReference(ref:ReferenceLayer){working=page.copy(references=page.references.map { if(it.id==ref.id)ref else it })}
    private fun transform(now:PointF) {
        val ref=referenceStart;val rect=ref?.let { RectF(it.x,it.y,it.x+it.width,it.y+it.height) } ?: selectionStart
        val scale=if(transformKind=="scale") (hypot(now.x-rect.left,now.y-rect.top)/hypot(start.x-rect.left,start.y-rect.top).coerceAtLeast(.001f)).coerceIn(.05f,20f) else 1f
        val angle=if(transformKind=="rotate") (atan2(now.y-rect.centerY(),now.x-rect.centerX())-atan2(start.y-rect.centerY(),start.x-rect.centerX()))*180f/PI.toFloat() else 0f
        val dx=if(transformKind=="move")now.x-start.x else 0f;val dy=if(transformKind=="move")now.y-start.y else 0f
        if(ref!=null)previewReference(ref.copy(x=ref.x+dx,y=ref.y+dy,width=ref.width*scale,height=ref.height*scale,rotation=ref.rotation+angle))
        else { val m=Matrix();m.setScale(scale,scale,rect.left,rect.top);m.postRotate(angle,rect.centerX(),rect.centerY());m.postTranslate(dx,dy)
            working=page.copy(strokes=page.strokes.map { if(it.id in selected)SketchGeometry.transform(it,m,scale) else it }) }
    }
    private fun normalizeAngle(a:Float):Float { var d=a%360f;if(d>180f)d-=360f;if(d< -180f)d+=360f;return d }
    private fun cancelGesture() { working=null;gesturePage=null;live.clear();lasso.clear();penId=-1;swiping=false;rotating=false
        if(fingers){zoom=startZoom;pageRotation=startRotation;panX=startPanX;panY=startPanY};fingers=false;invalidate() }
}
