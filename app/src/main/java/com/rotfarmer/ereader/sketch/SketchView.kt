package com.rotfarmer.ereader.sketch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.PointerIcon
import android.graphics.RenderNode
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.rotfarmer.ereader.data.Settings
import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkPainter
import com.rotfarmer.ereader.ink.InkStroke
import com.rotfarmer.ereader.ink.StrokeGeometry
import com.rotfarmer.ereader.ink.Tools
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One sketchbook page. The S Pen draws (hold its button to erase); a finger swipes to turn the
 * page, and two fingers zoom and move it. A hand resting on the screen while the pen is near is
 * ignored. Strokes are stored in page units (the page is 1 wide), so they look the same on any
 * screen and any device.
 */
@SuppressLint("ViewConstructor")
class SketchView(context: Context, private val settings: Settings) : View(context) {
    interface Listener {
        fun onStrokeAdded(stroke: InkStroke)
        fun onStrokesErased(strokes: List<InkStroke>)
        fun onTurn(step: Int)
    }

    var listener: Listener? = null
    var page = 0; private set
    var paper = SketchStore.BLANK
        set(v) { field = v; contentChanged() }
    var dark = false
        set(v) { field = v; contentChanged() }
    /** Space kept clear around the page (top bar, pen toolbar), in pixels. */
    var insetLeft = 0; var insetTop = 0; var insetRight = 0; var insetBottom = 0

    private var strokes = ArrayList<InkStroke>()
    val pageStrokes: List<InkStroke> get() = strokes
    private val painter = InkPainter()
    private val livePainter = InkPainter()
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // Page placement: at zoom 1 the page fits the free area; zoom and pan move it from there.
    private var fitLeft = 0f; private var fitTop = 0f; private var fitW = 1f
    private var zoom = 1f; private var panX = 0f; private var panY = 0f
    private val originX get() = fitLeft + panX
    private val originY get() = fitTop + panY
    private val scale get() = fitW * zoom

    // Paper and finished strokes are recorded once and redrawn cheaply every frame.
    private val node = RenderNode("sketch-page")
    private var nodeDirty = true
    private var nodeWidth = 0f

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // Pen state
    private var penPointer = -1
    private var penIsFinger = false
    private var penErasing = false
    private var live = FloatArray(600)
    private var liveCount = 0
    private val erasedNow = ArrayList<InkStroke>()
    private var lastStylusTime = 0L
    private var hovering = false
    private var hoverX = 0f; private var hoverY = 0f; private var hoverButton = false
    private var eraseX = 0f; private var eraseY = 0f

    // Finger state
    private var fActive = false; private var fIgnore = false; private var fMulti = false; private var pinched = false
    private var fDownX = 0f; private var fDownY = 0f; private var fLastX = 0f; private var fLastY = 0f
    private var pinchDist = 1f; private var pinchZoom = 1f; private var pinchPageX = 0f; private var pinchPageY = 0f
    private val slop = dp(8f)

    fun setPage(index: Int, list: List<InkStroke>) {
        resetPen()
        page = index
        strokes = ArrayList(list)
        zoom = 1f; panX = 0f; panY = 0f
        contentChanged()
    }

    /** Replaces this page's strokes (after undo, or changes from another device), keeping the view. */
    fun setStrokes(list: List<InkStroke>) {
        strokes = ArrayList(list)
        contentChanged()
    }

    fun refreshPen() {
        livePainter.sensitivity = settings.pressure
        strokes.forEach { it.shape = null }
        contentChanged()
    }

    fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }

    private fun contentChanged() { nodeDirty = true; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutPage(); contentChanged() }

    fun layoutPage() {
        val m = dp(14f)
        val availW = width - insetLeft - insetRight - 2 * m
        val availH = height - insetTop - insetBottom - 2 * m
        if (availW <= 0 || availH <= 0) return
        fitW = min(availW, availH / SketchFile.ASPECT)
        fitLeft = insetLeft + m + (availW - fitW) / 2
        fitTop = insetTop + m + (availH - fitW * SketchFile.ASPECT) / 2
        nodeDirty = true
        invalidate()
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        c.drawColor(SketchRender.deskColor(dark))
        val w = fitW
        val h = w * SketchFile.ASPECT
        if (nodeDirty || !node.hasDisplayList() || nodeWidth != w) {
            nodeWidth = w
            node.setPosition(0, 0, ceil(w).toInt(), ceil(h).toInt())
            val rc = node.beginRecording()
            try { SketchRender.drawPage(rc, w, paper, strokes, dark, settings.pressure, painter, max(1f, density * 0.6f)) }
            finally { node.endRecording() }
            nodeDirty = false
        }
        c.save()
        c.translate(originX, originY)
        c.scale(zoom, zoom)
        // A soft edge so the sheet sits on the desk.
        shadow.color = if (dark) 0x55000000 else 0x22000000
        val o = dp(1.2f) / zoom
        for (k in 1..3) c.drawRect(-o * k * 0.4f, o * k * 0.5f, w + o * k * 0.4f, h + o * k, shadow)
        if (c.isHardwareAccelerated) c.drawRenderNode(node)
        else SketchRender.drawPage(c, w, paper, strokes, dark, settings.pressure, painter, max(1f, density * 0.6f))
        outline.color = if (dark) 0xFF2E2E2E.toInt() else 0xFFCFCAC0.toInt()
        outline.strokeWidth = dp(1f) / zoom
        c.drawRect(0f, 0f, w, h, outline)
        c.restore()

        if (liveCount > 0 && !penErasing) {
            c.save()
            c.clipRect(originX, originY, originX + scale, originY + scale * SketchFile.ASPECT)
            livePainter.dark = dark
            livePainter.sensitivity = settings.pressure
            val tool = settings.tool
            livePainter.drawLive(c, live, liveCount, tool, settings.toolColor(tool), dp(settings.toolWidth(tool)))
            c.restore()
        }
        outline.color = if (dark) 0xFFBBBBBB.toInt() else 0xFF555555.toInt()
        outline.strokeWidth = dp(1.2f)
        if (penErasing && penPointer != -1) {
            c.drawCircle(eraseX, eraseY, dp(settings.toolWidth(Tools.ERASER)) / 2, outline)
        } else if (hovering && penPointer == -1) {
            if (hoverButton || settings.tool == Tools.ERASER) c.drawCircle(hoverX, hoverY, dp(settings.toolWidth(Tools.ERASER)) / 2, outline)
            else {
                // Where the pen will touch down.
                val tool = settings.tool
                shadow.color = com.rotfarmer.ereader.ink.InkColors.display(settings.toolColor(tool), dark)
                c.drawCircle(hoverX, hoverY, max(dp(2f), dp(settings.toolWidth(tool)) / 2), shadow)
            }
        }
    }

    // ------------------------------------------------------------------ input

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val tt = e.getToolType(0)
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) {
            lastStylusTime = SystemClock.uptimeMillis()
            hoverX = e.x; hoverY = e.y
            hoverButton = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 || tt == MotionEvent.TOOL_TYPE_ERASER
            hovering = e.actionMasked != MotionEvent.ACTION_HOVER_EXIT
            invalidate()
            return true
        }
        return super.onHoverEvent(e)
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val tt = event.getToolType(pointerIndex)
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        return super.onResolvePointerIcon(event, pointerIndex)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val raw = e.action
        // Older Samsung firmware reports S Pen events with the side button held as actions 211-213.
        val samsung = raw == 211 || raw == 212 || raw == 213
        val act = when (raw) {
            211 -> MotionEvent.ACTION_DOWN
            212 -> MotionEvent.ACTION_UP
            213 -> MotionEvent.ACTION_MOVE
            else -> e.actionMasked
        }
        val idx = if (samsung) 0 else e.actionIndex
        val tt = e.getToolType(idx)
        val stylus = samsung || tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER
        when (act) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (stylus && penPointer == -1) {
                    cancelFinger()
                    startPen(e, idx, samsung, finger = false)
                    return true
                }
                if (penPointer != -1) {
                    if (penIsFinger && stylus) {
                        // A finger stroke was probably a resting hand: drop it, the pen draws.
                        resetPen()
                        cancelFinger()
                        startPen(e, idx, samsung, finger = false)
                        return true
                    }
                    if (penIsFinger && !stylus) {
                        // A second finger while finger-drawing: a gesture, not a stroke.
                        cancelPen()
                        startPinch(e)
                    }
                    return true
                }
                return finger(e, act)
            }
            MotionEvent.ACTION_MOVE -> {
                if (penPointer != -1) { movePen(e, samsung); return true }
                return finger(e, act)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (penPointer != -1) {
                    if (act == MotionEvent.ACTION_UP || e.getPointerId(idx) == penPointer) endPen()
                    return true
                }
                return finger(e, act)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (penPointer != -1) cancelPen()
                cancelFinger()
                return true
            }
        }
        return true
    }

    private fun palmLikely() = SystemClock.uptimeMillis() - lastStylusTime < 450 || hovering

    private fun finger(e: MotionEvent, act: Int): Boolean {
        when (act) {
            MotionEvent.ACTION_DOWN -> {
                fActive = true; fMulti = false; pinched = false
                fDownX = e.x; fDownY = e.y; fLastX = e.x; fLastY = e.y
                fIgnore = palmLikely()
                if (!fIgnore && settings.fingerDraw) startPen(e, 0, false, finger = true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (fActive && !fIgnore && e.pointerCount >= 2) startPinch(e)
            MotionEvent.ACTION_MOVE -> {
                if (!fActive || fIgnore) return true
                if (fMulti && e.pointerCount >= 2) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
                    val cx = (e.getX(0) + e.getX(1)) / 2; val cy = (e.getY(0) + e.getY(1)) / 2
                    zoom = (pinchZoom * d / pinchDist).coerceIn(1f, 5f)
                    // Keep the point between the fingers under the fingers.
                    panX = cx - pinchPageX * fitW * zoom - fitLeft
                    panY = cy - pinchPageY * fitW * zoom - fitTop
                    clampPan(); invalidate()
                } else if (!fMulti && zoom > 1.01f) {
                    panX += e.x - fLastX; panY += e.y - fLastY
                    clampPan(); invalidate()
                }
                fLastX = e.x; fLastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Keep panning with the finger that stays down, without a jump.
                if (fMulti && e.pointerCount == 2) {
                    val keep = if (e.actionIndex == 0) 1 else 0
                    fLastX = e.getX(keep); fLastY = e.getY(keep)
                    fMulti = false; pinched = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (fActive && !fIgnore && !fMulti && !pinched && zoom <= 1.01f) {
                    val dx = e.x - fDownX; val dy = e.y - fDownY
                    if (abs(dx) > max(dp(60f), width * 0.1f) && abs(dx) > abs(dy) * 1.4f) listener?.onTurn(if (dx < 0) 1 else -1)
                }
                if (zoom <= 1.01f) { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
                fActive = false
            }
        }
        return true
    }

    private fun startPinch(e: MotionEvent) {
        if (e.pointerCount < 2) return
        fActive = true; fMulti = true; fIgnore = false; pinched = true
        pinchDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
        pinchZoom = zoom
        val cx = (e.getX(0) + e.getX(1)) / 2; val cy = (e.getY(0) + e.getY(1)) / 2
        pinchPageX = (cx - originX) / scale; pinchPageY = (cy - originY) / scale
    }

    private fun clampPan() {
        // Keep at least a third of the page on screen.
        val w = scale; val h = scale * SketchFile.ASPECT
        val minX = -fitLeft - w * 2 / 3; val maxX = width - fitLeft - w / 3
        val minY = -fitTop - h * 2 / 3; val maxY = height - fitTop - h / 3
        panX = panX.coerceIn(minX, maxX); panY = panY.coerceIn(minY, maxY)
    }

    private fun cancelFinger() { fActive = false; fMulti = false }

    // ------------------------------------------------------------------ pen

    private fun startPen(e: MotionEvent, idx: Int, samsungButton: Boolean, finger: Boolean) {
        penPointer = e.getPointerId(idx)
        penIsFinger = finger
        if (!finger) {
            lastStylusTime = SystemClock.uptimeMillis()
            hovering = false
            requestUnbufferedDispatch(e)
        }
        liveCount = 0
        erasedNow.clear()
        val tt = e.getToolType(idx)
        penErasing = settings.tool == Tools.ERASER || tt == MotionEvent.TOOL_TYPE_ERASER || samsungButton ||
            (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
        addPoint(e.getX(idx), e.getY(idx), pressure(e, idx))
        if (penErasing) eraseAt(e.getX(idx), e.getY(idx))
        invalidate()
    }

    private fun pressure(e: MotionEvent, i: Int): Float = if (penIsFinger) 0.6f else e.getPressure(i).coerceIn(0.05f, 1f)

    private fun movePen(e: MotionEvent, samsungButton: Boolean) {
        val i = e.findPointerIndex(penPointer)
        if (i < 0) return
        if (!penIsFinger) lastStylusTime = SystemClock.uptimeMillis()
        if (!penErasing && !penIsFinger && liveCount <= 3 && (samsungButton || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)) penErasing = true
        for (h in 0 until e.historySize) {
            val x = e.getHistoricalX(i, h); val y = e.getHistoricalY(i, h)
            if (penErasing) eraseAt(x, y) else addPoint(x, y, if (penIsFinger) 0.6f else e.getHistoricalPressure(i, h).coerceIn(0.05f, 1f))
        }
        if (penErasing) eraseAt(e.getX(i), e.getY(i)) else addPoint(e.getX(i), e.getY(i), pressure(e, i))
        invalidate()
    }

    private fun addPoint(x: Float, y: Float, p: Float) {
        if (liveCount > 0) {
            val j = (liveCount - 1) * 3
            if (abs(live[j] - x) < 0.6f && abs(live[j + 1] - y) < 0.6f) { live[j + 2] = max(live[j + 2], p); return }
        }
        if ((liveCount + 1) * 3 > live.size) live = live.copyOf(live.size * 2)
        val j = liveCount * 3
        live[j] = x; live[j + 1] = y; live[j + 2] = p
        liveCount++
    }

    private fun endPen() {
        when {
            penErasing -> if (erasedNow.isNotEmpty()) listener?.onStrokesErased(ArrayList(erasedNow))
            liveCount > 0 -> commitStroke()
        }
        resetPen()
    }

    private fun cancelPen() {
        if (penErasing && erasedNow.isNotEmpty()) listener?.onStrokesErased(ArrayList(erasedNow))
        resetPen()
    }

    private fun resetPen() {
        if (penPointer != -1 && !penIsFinger) lastStylusTime = SystemClock.uptimeMillis()
        penPointer = -1; penErasing = false; penIsFinger = false; liveCount = 0
        invalidate()
    }

    private fun commitStroke() {
        val n = liveCount
        val s = scale
        // Ignore strokes that never touch the page (a slip on the desk around it).
        var onPage = false
        val pts = FloatArray(n * 3)
        for (k in 0 until n) {
            val x = (live[k * 3] - originX) / s
            val y = (live[k * 3 + 1] - originY) / s
            if (x in 0f..1f && y in 0f..SketchFile.ASPECT) onPage = true
            pts[k * 3] = x; pts[k * 3 + 1] = y; pts[k * 3 + 2] = live[k * 3 + 2]
        }
        if (!onPage) return
        val tool = settings.tool
        val stroke = InkStroke(SketchStore.newStrokeId(), 0L, Anchor(Anchor.PDF, page.toLong(), 0), tool, settings.toolColor(tool),
            dp(settings.toolWidth(tool)) / s, pts, System.currentTimeMillis())
        strokes.add(stroke)
        contentChanged()
        listener?.onStrokeAdded(stroke)
    }

    private fun eraseAt(sx: Float, sy: Float) {
        eraseX = sx; eraseY = sy
        val s = scale
        val x = (sx - originX) / s
        val y = (sy - originY) / s
        val r = dp(settings.toolWidth(Tools.ERASER)) / 2 / s
        var hit = false
        val it = strokes.iterator()
        while (it.hasNext()) {
            val st = it.next()
            val b = st.bounds
            if (x < b.left - r - st.width || x > b.right + r + st.width || y < b.top - r - st.width || y > b.bottom + r + st.width) continue
            if (StrokeGeometry.distanceTo(st, x, y) <= r + st.width / 2) { it.remove(); erasedNow += st; hit = true }
        }
        if (hit) contentChanged()
    }
}
