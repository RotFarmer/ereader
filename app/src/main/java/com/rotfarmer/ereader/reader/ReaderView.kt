package com.rotfarmer.ereader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Spanned
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewConfiguration
import com.rotfarmer.ereader.data.Highlight
import com.rotfarmer.ereader.data.Ids
import com.rotfarmer.ereader.data.Locator
import com.rotfarmer.ereader.data.WritingSpace
import com.rotfarmer.ereader.doc.FlowDocument
import com.rotfarmer.ereader.doc.LinkSpan
import com.rotfarmer.ereader.doc.TextBlock
import com.rotfarmer.ereader.ink.Anchoring
import com.rotfarmer.ereader.ink.InkColors
import com.rotfarmer.ereader.ink.InkPainter
import com.rotfarmer.ereader.ink.InkStroke
import com.rotfarmer.ereader.ink.StrokeGeometry
import com.rotfarmer.ereader.ink.Tools
import com.rotfarmer.ereader.layout.Engine
import com.rotfarmer.ereader.layout.ImageItem
import com.rotfarmer.ereader.layout.LayoutSpec
import com.rotfarmer.ereader.layout.Page
import com.rotfarmer.ereader.layout.PagePos
import com.rotfarmer.ereader.layout.PdfSliceItem
import com.rotfarmer.ereader.layout.RuleItem
import com.rotfarmer.ereader.layout.SpaceItem
import com.rotfarmer.ereader.layout.TextItem
import com.rotfarmer.ereader.render.Eink
import com.rotfarmer.ereader.render.PageRenderer
import com.rotfarmer.ereader.render.PdfCache
import com.rotfarmer.ereader.render.ReaderColors
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows one page at a time like an e-ink reader. Fingers turn pages (tap or swipe), select text
 * (long press) and zoom PDFs (pinch). The S Pen only ever writes, draws, highlights or erases.
 */
@SuppressLint("ViewConstructor")
class ReaderView(ctx: Context) : View(ctx) {

    companion object {
        /** Smallest writing space, as a fraction of a page. */
        const val MIN_SPACE = 0.06f
    }

    interface Listener {
        fun onPageShown(pos: PagePos)
        fun onToggleChrome()
        fun onToggleBookmark()
        fun onFooterTap()
        fun onSelection(sel: TextSel, anchor: RectF)
        fun onSelectionCleared()
        fun onHighlightTap(h: Highlight, anchor: RectF)
        fun onRegion(page: Int, region: RectF, anchor: RectF)
        fun onSpaceLongPress(space: WritingSpace, anchor: RectF)
        fun onInsertSpace(target: InsertTarget, anchor: RectF)
        /** Press-and-drag with the insert tool: add a space of [height] (fraction of a page) at [loc]. */
        fun onInsertSpaceSized(loc: Locator, height: Float)
        /** The bottom edge of a writing space was dragged to a new [height]. */
        fun onSpaceResized(space: WritingSpace, height: Float)
        /** The × on a writing space was tapped. */
        fun onDeleteSpace(space: WritingSpace)
        fun onStrokeAdded(s: InkStroke)
        fun onStrokesErased(list: List<InkStroke>, highlights: List<Highlight>)
        fun onSmartHighlight(start: Locator, end: Locator, color: Int)
        fun onLink(target: Locator)
        fun onFontPinch(steps: Int)
        fun onPenUsed()
        fun onSizeChanged(w: Int, h: Int)
    }

    var listener: Listener? = null
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ------------------------------------------------------------- state set by the activity
    var engine: Engine? = null
        private set
    var spec: LayoutSpec? = null
        private set
    var pos = PagePos(0, 0)
        private set
    var doc: FlowDocument? = null
    var ann = Annotations()
    var bookId = 0L
    var pdfCache: PdfCache? = null
    val renderer = PageRenderer(density)
    val inkPainter = InkPainter()

    var colors = ReaderColors(false)
        set(v) {
            field = v
            renderer.colors = v
            inkPainter.dark = v.dark
            texturePaint.shader = BitmapShader(Eink.paperTile(v.dark), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            invalidateContent()
        }
    var paperTexture = true
    var flashEvery = 6
    var ghosting = false
        set(v) {
            field = v
            if (!v) ghostNode = null
        }
    var fingerDraw = false
    var smartHighlight = true
    var rtl = false

    var tool = Tools.PEN
    var toolColor = 0xFF1A1A1A.toInt()
    var toolWidthDp = 1.6f
    var eraserWidthDp = 14f
    var armedInsert = false
        set(v) {
            field = v; invalidate()
        }

    var header = ""
    var footerLeft = ""
    var footerRight = ""
    var bookmarked = false

    // ------------------------------------------------------------- rendering
    private var contentNode = RenderNode("content")
    private var inkNode = RenderNode("ink")
    private var ghostNode: RenderNode? = null
    private var contentDirty = true
    private var inkDirty = true
    private var recordedPage: Page? = null
    private val texturePaint = Paint().apply { shader = BitmapShader(Eink.paperTile(false), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11.5f); typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selPath = Path()
    private var turns = 0
    private var flashStart = 0L

    // ------------------------------------------------------------- zoom (PDF only)
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val zoomMatrix = Matrix()
    private val inverse = Matrix()
    private var hiRes: Bitmap? = null
    private var hiResKey = ""

    // ------------------------------------------------------------- selection
    var selection: TextSel? = null
        private set
    private var region: RectF? = null
    private var regionPage = -1
    private var dragHandle = 0
    private var locateStart: Locator? = null
    private var locateEnd: Locator? = null
    private var locateRegion: RectF? = null
    private var locateUntil = 0L
    private var insertMarkY = -1f
    // Sizing writing spaces by hand.
    private var insertTarget: Locator? = null
    private var insertDownY = 0f
    private var insertCurY = 0f
    private var insertDragging = false
    private var resizing: SpaceItem? = null
    /** Finger went down on a space's bottom edge; becomes [resizing] once it moves vertically. */
    private var resizeCandidate: SpaceItem? = null
    private var resizeY = 0f

    // ------------------------------------------------------------- input
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var penPointer = -1
    private var penIsFinger = false
    private var penErasing = false
    private var penInsert = false
    private var live = FloatArray(3 * 512)
    private var liveCount = 0
    private var lastStylusTime = 0L
    private var hovering = false
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverButton = false
    private val erasedNow = ArrayList<InkStroke>()
    private val erasedHl = ArrayList<Highlight>()

    private var fActive = false
    private var fIgnore = false
    private var fMoved = false
    private var fDownX = 0f
    private var fDownY = 0f
    private var fLastX = 0f
    private var fLastY = 0f
    private var fLong = false
    private var fMulti = false
    private var pinchDist = 0f
    private var pinchZoom = 1f
    private var pinchScale = 1f
    private var pinched = false
    private val longPress = Runnable { onLongPress() }

    init {
        isFocusable = true
        isHapticFeedbackEnabled = true
    }

    // ================================================================= public API

    fun setEngine(e: Engine, s: LayoutSpec, p: PagePos) {
        engine?.cancel()
        engine = e
        spec = s
        pos = p
        resetZoom()
        clearSelection()
        invalidateContent()
        listener?.onPageShown(p)
        prefetch()
    }

    fun clearEngine() {
        engine?.cancel()
        engine = null
        invalidate()
    }

    fun invalidateContent() {
        contentDirty = true
        inkDirty = true
        invalidate()
    }

    fun invalidateInk() {
        inkDirty = true
        invalidate()
    }

    fun currentPage(): Page? = engine?.page(pos.ch, pos.idx)

    fun goTo(p: PagePos, flash: Boolean = true) {
        val e = engine ?: return
        if (p == pos) return
        if (ghosting) {
            ghostNode = contentNode
            contentNode = RenderNode("content")
        }
        pos = p
        resetZoom()
        clearSelection()
        insertMarkY = -1f
        contentDirty = true
        inkDirty = true
        turns++
        if (flash && flashEvery > 0 && turns % flashEvery == 0) {
            flashStart = SystemClock.uptimeMillis()
            ghostNode = null
        }
        listener?.onPageShown(p)
        invalidate()
        if (e.isFixed) prefetch()
    }

    fun next(): Boolean {
        val n = engine?.next(pos) ?: return false
        goTo(n); return true
    }

    fun prev(): Boolean {
        val n = engine?.prev(pos) ?: return false
        goTo(n); return true
    }

    fun clearSelection() {
        if (selection != null || region != null) {
            selection = null
            region = null
            dragHandle = 0
            invalidate()
        }
    }

    fun clearInsertMark() {
        insertMarkY = -1f; invalidate()
    }

    /** Briefly outlines a passage so it can be found on the page. */
    fun showLocate(start: Locator, end: Locator) {
        locateStart = start; locateEnd = end; locateRegion = null
        locateUntil = SystemClock.uptimeMillis() + 3000
        invalidate()
        postDelayed({ invalidate() }, 3100)
    }

    fun showLocateRegion(page: Int, r: RectF) {
        locateStart = null; locateRegion = RectF(r); regionPage = page
        locateUntil = SystemClock.uptimeMillis() + 3000
        invalidate()
        postDelayed({ invalidate() }, 3100)
    }

    private fun prefetch() {
        val e = engine ?: return
        val cache = pdfCache ?: return
        if (!e.isFixed) return
        for (p in listOf(e.next(pos), e.prev(pos))) {
            val pg = p?.let { e.page(it.ch, it.idx) } ?: continue
            pg.items.filterIsInstance<PdfSliceItem>().forEach {
                cache.prefetch(it.page, it.pageRect.width().toInt(), renderer.eink)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) listener?.onSizeChanged(w, h)
    }

    // ================================================================= drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colors.paper)
        val e = engine
        val page = if (e != null) currentPage() else null
        if (page == null) {
            return
        }
        if (page !== recordedPage) {
            contentDirty = true; inkDirty = true
        }
        val hw = canvas.isHardwareAccelerated
        val zoomed = zoom > 1.001f

        canvas.save()
        if (zoomed) canvas.concat(zoomMatrix)
        if (hw) {
            // Android discards our layers' contents when the app goes to the background,
            // so re-record whenever they're gone, not only when the page changed.
            if (contentDirty || !contentNode.hasDisplayList()) {
                // Note: not inside apply{} — RenderNode has its own width/height that would shadow the view's.
                contentNode.setPosition(0, 0, width, height)
                val c = contentNode.beginRecording(width, height)
                renderer.highlights = ann.highlights
                renderer.draw(c, page)
                contentNode.endRecording()
                contentDirty = false
                recordedPage = page
            }
            canvas.drawRenderNode(contentNode)
        } else {
            renderer.highlights = ann.highlights
            renderer.draw(canvas, page)
        }
        canvas.restore()

        if (zoomed) drawHiRes(canvas, page)

        ghostNode?.let {
            if (hw && it.hasDisplayList()) {
                it.alpha = 0.07f
                canvas.drawRenderNode(it)
            }
        }
        if (paperTexture) canvas.drawPaint(texturePaint)

        canvas.save()
        if (zoomed) canvas.concat(zoomMatrix)
        if (hw) {
            if (inkDirty || !inkNode.hasDisplayList()) {
                inkNode.setPosition(0, 0, width, height)
                val c = inkNode.beginRecording(width, height)
                drawInk(c, page)
                inkNode.endRecording()
                inkDirty = false
            }
            canvas.drawRenderNode(inkNode)
        } else drawInk(canvas, page)
        drawSelection(canvas, page)
        drawLocate(canvas, page)
        canvas.restore()

        if (liveCount > 0 && !penErasing && !penInsert) {
            inkPainter.drawLive(canvas, live, liveCount, tool, toolColor, dp(toolWidthDp))
        }
        if (insertMarkY >= 0 && !insertDragging) drawInsertMark(canvas)
        drawSizing(canvas)
        drawChrome(canvas)
        drawHover(canvas)
        drawFlash(canvas)
    }

    private fun drawInk(c: Canvas, page: Page) {
        val s = spec ?: return
        Anchoring.forEachStroke(page, ann.ink, s.content, width) { stroke, pl -> inkPainter.draw(c, stroke, pl) }
    }

    private fun drawHiRes(canvas: Canvas, page: Page) {
        val bmp = hiRes ?: return
        if (hiResKey != zoomKey()) return
        val slice = page.items.filterIsInstance<PdfSliceItem>().firstOrNull() ?: return
        val r = RectF(slice.rect)
        zoomMatrix.mapRect(r)
        canvas.save()
        canvas.clipRect(r)
        overlay.colorFilter = colors.pdfFilter
        canvas.drawBitmap(bmp, 0f, 0f, overlay)
        overlay.colorFilter = null
        canvas.restore()
    }

    private fun drawSelection(c: Canvas, page: Page) {
        val sel = selection
        if (sel != null) {
            PageRenderer.rangePath(page, sel.start, sel.end, selPath)
            overlay.style = Paint.Style.FILL
            overlay.color = colors.selection
            c.drawPath(selPath, overlay)
            overlay.color = colors.text
            handlePoint(page, sel.start, false)?.let { drawHandle(c, it) }
            handlePoint(page, sel.end, true)?.let { drawHandle(c, it) }
        }
        val r = region
        if (r != null) {
            val sr = regionScreenRect(page, r) ?: return
            overlay.style = Paint.Style.FILL
            overlay.color = colors.selection
            c.drawRect(sr, overlay)
            overlay.style = Paint.Style.STROKE
            overlay.strokeWidth = dp(1.5f)
            overlay.color = colors.text
            c.drawRect(sr, overlay)
            overlay.style = Paint.Style.FILL
            for (p in listOf(PointF(sr.left, sr.top), PointF(sr.right, sr.top), PointF(sr.left, sr.bottom), PointF(sr.right, sr.bottom))) {
                c.drawCircle(p.x, p.y, dp(7f), overlay)
            }
        }
    }

    private fun drawHandle(c: Canvas, p: PointF) {
        overlay.style = Paint.Style.FILL
        overlay.color = colors.text
        c.drawRect(p.x - dp(1f), p.y - dp(4f), p.x + dp(1f), p.y + dp(6f), overlay)
        c.drawCircle(p.x, p.y + dp(12f), dp(7f), overlay)
    }

    private fun drawLocate(c: Canvas, page: Page) {
        if (SystemClock.uptimeMillis() > locateUntil) return
        overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = dp(2f)
        overlay.color = colors.text
        overlay.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
        val s = locateStart
        val e = locateEnd
        if (s != null && e != null) {
            PageRenderer.rangePath(page, s, e, selPath)
            val b = RectF()
            selPath.computeBounds(b, true)
            if (!b.isEmpty) {
                b.inset(-dp(6f), -dp(4f)); c.drawRoundRect(b, dp(4f), dp(4f), overlay)
            }
        }
        locateRegion?.let { r -> regionScreenRect(page, r)?.let { c.drawRect(it, overlay) } }
        overlay.pathEffect = null
    }

    private fun drawInsertMark(c: Canvas) {
        val s = spec ?: return
        overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = dp(2f)
        overlay.color = colors.text
        overlay.pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(5f)), 0f)
        c.drawLine(s.content.left, insertMarkY, s.content.right, insertMarkY, overlay)
        overlay.pathEffect = null
        overlay.style = Paint.Style.FILL
        val path = Path().apply {
            moveTo(s.content.left - dp(10f), insertMarkY - dp(6f))
            lineTo(s.content.left - dp(2f), insertMarkY)
            lineTo(s.content.left - dp(10f), insertMarkY + dp(6f))
            close()
        }
        c.drawPath(path, overlay)
    }

    private fun drawChrome(c: Canvas) {
        val s = spec ?: return
        textPaint.color = colors.muted
        val baseTop = s.content.top - dp(14f)
        if (header.isNotEmpty() && baseTop > dp(12f)) {
            val h = ellipsize(header, width * 0.6f)
            c.drawText(h, (width - textPaint.measureText(h)) / 2f, baseTop, textPaint)
        }
        val fy = height - dp(14f)
        c.drawText(footerLeft, s.content.left, fy, textPaint)
        c.drawText(footerRight, s.content.right - textPaint.measureText(footerRight), fy, textPaint)
        if (bookmarked) {
            overlay.style = Paint.Style.FILL
            overlay.color = colors.text
            val x = width - dp(34f)
            val path = Path().apply {
                moveTo(x, 0f); lineTo(x + dp(18f), 0f); lineTo(x + dp(18f), dp(30f))
                lineTo(x + dp(9f), dp(23f)); lineTo(x, dp(30f)); close()
            }
            c.drawPath(path, overlay)
        }
        if (armedInsert) {
            val t = "Tap to add writing space, or press and drag down to size it"
            textPaint.color = colors.text
            c.drawText(t, (width - textPaint.measureText(t)) / 2f, s.content.top - dp(14f) + dp(2f), textPaint)
        }
    }

    private fun ellipsize(t: String, max: Float): String {
        if (textPaint.measureText(t) <= max) return t
        var n = t.length
        while (n > 1 && textPaint.measureText(t, 0, n) + textPaint.measureText("…") > max) n--
        return t.substring(0, n) + "…"
    }

    private fun drawHover(c: Canvas) {
        if (!hovering || penPointer != -1) return
        val erasing = tool == Tools.ERASER || hoverButton
        overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = dp(1f)
        overlay.color = if (erasing) colors.muted else InkColors.display(toolColor, colors.dark)
        val r = if (erasing) dp(eraserWidthDp) / 2 else max(dp(toolWidthDp) / 2, dp(2.5f))
        c.drawCircle(hoverX, hoverY, r, overlay)
        if (!erasing) {
            overlay.style = Paint.Style.FILL
            c.drawCircle(hoverX, hoverY, dp(1.2f), overlay)
        }
    }

    private fun drawFlash(c: Canvas) {
        if (flashStart == 0L) return
        val t = SystemClock.uptimeMillis() - flashStart
        when {
            t < 80 -> c.drawColor(colors.text)
            t < 170 -> c.drawColor(colors.paper)
            else -> {
                flashStart = 0L; return
            }
        }
        postInvalidateOnAnimation()
    }

    // ================================================================= geometry helpers

    private fun zoomKey() = "${pos.ch}:${pos.idx}:$zoom:$panX:$panY:$width:$height"

    private fun toPage(x: Float, y: Float): PointF {
        if (zoom <= 1.001f) return PointF(x, y)
        val p = floatArrayOf(x, y)
        inverse.mapPoints(p)
        return PointF(p[0], p[1])
    }

    private fun toScreen(r: RectF): RectF {
        val out = RectF(r)
        if (zoom > 1.001f) zoomMatrix.mapRect(out)
        return out
    }

    private fun handlePoint(page: Page, loc: Locator, isEnd: Boolean): PointF? {
        for (item in page.items) {
            if (item !is TextItem || item.block != loc.block) continue
            val inRange = loc.offset in item.absStart until item.absEnd || (isEnd && loc.offset == item.absEnd)
            if (!inRange) continue
            val local = loc.offset - item.pieceStart
            val line = item.layout.getLineForOffset(if (isEnd) (local - 1).coerceAtLeast(0) else local).coerceIn(item.lineStart, item.lineEnd - 1)
            val x = if (isEnd && local >= item.layout.getLineEnd(line)) {
                if (item.layout.getParagraphDirection(line) < 0) item.layout.getLineLeft(line) else item.layout.getLineRight(line)
            } else item.layout.getPrimaryHorizontal(local)
            return PointF(item.rect.left + x, item.lineBottom(line))
        }
        return null
    }

    /** PDF region (page fractions) to view coordinates (before zoom). */
    private fun regionPageRect(page: Page, r: RectF): RectF? {
        val slice = page.items.filterIsInstance<PdfSliceItem>().firstOrNull { it.page == regionPage } ?: return null
        val pr = slice.pageRect
        return RectF(pr.left + r.left * pr.width(), pr.top + r.top * pr.height(), pr.left + r.right * pr.width(), pr.top + r.bottom * pr.height())
    }

    private fun regionScreenRect(page: Page, r: RectF): RectF? = regionPageRect(page, r)

    /** The text position under a point on the current page, if it is over text. */
    private fun textAt(x: Float, y: Float, tolerance: Float): Pair<TextItem, Int>? {
        val page = currentPage() ?: return null
        for (item in page.items) {
            if (item !is TextItem) continue
            if (y < item.rect.top - tolerance || y > item.rect.bottom + tolerance) continue
            if (x < item.rect.left - dp(30f) || x > item.rect.right + dp(30f)) continue
            return item to item.offsetAt(x, y.coerceIn(item.rect.top, item.rect.bottom - 1))
        }
        return null
    }

    /** Nearest text position on the page (for dragging selection handles). */
    private fun nearestText(x: Float, y: Float): Locator? {
        val page = currentPage() ?: return null
        var best: TextItem? = null
        var bestD = Float.MAX_VALUE
        for (item in page.items) {
            if (item !is TextItem) continue
            val d = when {
                y < item.rect.top -> item.rect.top - y
                y > item.rect.bottom -> y - item.rect.bottom
                else -> 0f
            }
            if (d < bestD) {
                bestD = d; best = item
            }
        }
        val it = best ?: return null
        return Locator(it.block, it.offsetAt(x, y.coerceIn(it.rect.top, it.rect.bottom - 1)))
    }

    private fun blockText(b: Int): CharSequence? = (doc?.blocks?.getOrNull(b) as? TextBlock)?.text

    private fun wordRange(block: Int, off: Int): Pair<Int, Int> {
        val text = blockText(block) ?: return off to off
        val len = text.length
        if (len == 0) return 0 to 0
        val bi = BreakIterator.getWordInstance()
        bi.setText(text.toString())
        val o = off.coerceIn(0, len - 1)
        var s = bi.preceding(o + 1)
        if (s == BreakIterator.DONE) s = 0
        var e = bi.following(o)
        if (e == BreakIterator.DONE) e = len
        return s to e
    }

    private fun selectionBounds(page: Page): RectF {
        val b = RectF()
        selection?.let { PageRenderer.rangePath(page, it.start, it.end, selPath); selPath.computeBounds(b, true) }
        return b
    }

    // ================================================================= touch input

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
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) {
            return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        }
        return super.onResolvePointerIcon(event, pointerIndex)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (engine == null) return true
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
                    if (penIsFinger && !stylus) {
                        // A second finger while finger-drawing: it's a gesture, not a stroke.
                        cancelPen()
                        fActive = true; fMulti = true; fIgnore = false; fMoved = true
                        fDownX = e.getX(0); fDownY = e.getY(0)
                        if (e.pointerCount >= 2) {
                            pinchDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
                        }
                        pinchZoom = zoom
                        pinchScale = 1f
                    }
                    return true
                }
                return finger(e, act)
            }
            MotionEvent.ACTION_MOVE -> {
                if (penPointer != -1) {
                    movePen(e, samsung); return true
                }
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

    // ----------------------------------------------------------------- pen

    private fun startPen(e: MotionEvent, idx: Int, samsungButton: Boolean, finger: Boolean) {
        penPointer = e.getPointerId(idx)
        penIsFinger = finger
        lastStylusTime = SystemClock.uptimeMillis()
        hovering = false
        if (!finger) {
            requestUnbufferedDispatch(e)
            listener?.onPenUsed()
        }
        if (selection != null || region != null) {
            clearSelection(); listener?.onSelectionCleared()
        }
        liveCount = 0
        erasedNow.clear()
        erasedHl.clear()
        penInsert = armedInsert
        if (penInsert) {
            addPoint(e.getX(idx), e.getY(idx), 1f)
            insertDragging = false
            insertTarget = null
            if (engine?.isFixed == false) {
                val page = currentPage()
                val t = page?.let { flowInsertTarget(it, toPage(e.getX(idx), e.getY(idx))) }
                if (t != null) {
                    insertTarget = t.first
                    insertMarkY = t.second
                    insertDownY = t.second
                    insertCurY = t.second
                }
            }
            invalidate()
            return
        }
        val tt = e.getToolType(idx)
        penErasing = tool == Tools.ERASER || tt == MotionEvent.TOOL_TYPE_ERASER || samsungButton ||
            (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
        addPoint(e.getX(idx), e.getY(idx), pressure(e, idx))
        if (penErasing) eraseAt(e.getX(idx), e.getY(idx))
        invalidate()
    }

    private fun pressure(e: MotionEvent, i: Int): Float = if (penIsFinger) 0.6f else e.getPressure(i).coerceIn(0.05f, 1f)

    private fun movePen(e: MotionEvent, samsungButton: Boolean) {
        val i = e.findPointerIndex(penPointer)
        if (i < 0) return
        lastStylusTime = SystemClock.uptimeMillis()
        if (penInsert) {
            if (insertTarget != null) {
                insertCurY = toPage(e.getX(i), e.getY(i)).y
                if (insertCurY - insertDownY > slop * 2) insertDragging = true
                invalidate()
            }
            return
        }
        if (!penErasing && !penIsFinger && liveCount <= 3 &&
            (samsungButton || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
        ) {
            penErasing = true
        }
        for (h in 0 until e.historySize) {
            val x = e.getHistoricalX(i, h)
            val y = e.getHistoricalY(i, h)
            if (penErasing) eraseAt(x, y) else addPoint(x, y, if (penIsFinger) 0.6f else e.getHistoricalPressure(i, h).coerceIn(0.05f, 1f))
        }
        if (penErasing) eraseAt(e.getX(i), e.getY(i)) else addPoint(e.getX(i), e.getY(i), pressure(e, i))
        invalidate()
    }

    private fun addPoint(x: Float, y: Float, p: Float) {
        if (liveCount > 0) {
            val j = (liveCount - 1) * 3
            if (abs(live[j] - x) < 0.6f && abs(live[j + 1] - y) < 0.6f) {
                live[j + 2] = max(live[j + 2], p); return
            }
        }
        if ((liveCount + 1) * 3 > live.size) live = live.copyOf(live.size * 2)
        val j = liveCount * 3
        live[j] = x; live[j + 1] = y; live[j + 2] = p
        liveCount++
    }

    private fun endPen() {
        if (penIsFinger && !penErasing && !penInsert && liveCount > 0) {
            // With finger drawing on, a plain tap still turns pages.
            var moved = 0f
            for (k in 1 until liveCount) moved = max(moved, hypot(live[k * 3] - live[0], live[k * 3 + 1] - live[1]))
            if (moved < slop) {
                val x = live[0]
                val y = live[1]
                resetPen()
                tap(x, y)
                return
            }
        }
        when {
            penInsert -> {
                val t = insertTarget
                if (insertDragging && t != null) {
                    insertMarkY = -1f
                    listener?.onInsertSpaceSized(t, dragHeight(insertDownY, insertCurY))
                } else if (liveCount > 0) insertAt(live[0], live[1])
            }
            penErasing -> if (erasedNow.isNotEmpty() || erasedHl.isNotEmpty()) {
                listener?.onStrokesErased(ArrayList(erasedNow), ArrayList(erasedHl))
            }
            liveCount > 0 -> commitStroke()
        }
        resetPen()
    }

    private fun cancelPen() {
        if (penErasing && (erasedNow.isNotEmpty() || erasedHl.isNotEmpty())) {
            listener?.onStrokesErased(ArrayList(erasedNow), ArrayList(erasedHl))
        }
        resetPen()
    }

    private fun resetPen() {
        insertDragging = false
        insertTarget = null
        penPointer = -1
        penErasing = false
        penInsert = false
        penIsFinger = false
        liveCount = 0
        lastStylusTime = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun commitStroke() {
        val page = currentPage() ?: return
        val s = spec ?: return
        val n = liveCount
        val pagePts = FloatArray(n * 3)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (k in 0 until n) {
            val p = toPage(live[k * 3], live[k * 3 + 1])
            pagePts[k * 3] = p.x; pagePts[k * 3 + 1] = p.y; pagePts[k * 3 + 2] = live[k * 3 + 2]
            minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        if (tool == Tools.HIGHLIGHTER && smartHighlight && engine?.isFixed == false && tryTextHighlight(pagePts, n)) return
        val (anchor, pl) = Anchoring.anchorAt(page, (minX + maxX) / 2, (minY + maxY) / 2, s.content, width) ?: return
        val pts = FloatArray(n * 3)
        for (k in 0 until n) {
            pts[k * 3] = (pagePts[k * 3] - pl.ox) / pl.unit
            pts[k * 3 + 1] = (pagePts[k * 3 + 1] - pl.oy) / pl.unit
            pts[k * 3 + 2] = pagePts[k * 3 + 2]
        }
        val w = dp(toolWidthDp) / zoom / pl.unit
        val stroke = InkStroke(Ids.next(), bookId, anchor, tool, toolColor, w, pts, System.currentTimeMillis())
        ann.ink.add(stroke)
        invalidateInk()
        listener?.onStrokeAdded(stroke)
    }

    /** A highlighter stroke across text becomes a proper text highlight. */
    private fun tryTextHighlight(pts: FloatArray, n: Int): Boolean {
        val tol = dp(8f)
        val a = textAt(pts[0], pts[1], tol) ?: return false
        val b = textAt(pts[(n - 1) * 3], pts[(n - 1) * 3 + 1], tol) ?: return false
        var s = Locator(a.first.block, a.second)
        var e = Locator(b.first.block, b.second)
        if (e < s) s = e.also { e = s }
        s = Locator(s.block, wordRange(s.block, s.offset).first)
        e = Locator(e.block, wordRange(e.block, max(e.offset - 1, 0)).second)
        if (e <= s) return false
        listener?.onSmartHighlight(s, e, toolColor)
        return true
    }

    private fun eraseAt(sx: Float, sy: Float) {
        val page = currentPage() ?: return
        val s = spec ?: return
        val p = toPage(sx, sy)
        val r = dp(eraserWidthDp) / 2 / zoom
        val hits = ArrayList<InkStroke>()
        Anchoring.forEachStroke(page, ann.ink, s.content, width) { stroke, pl ->
            val ax = (p.x - pl.ox) / pl.unit
            val ay = (p.y - pl.oy) / pl.unit
            val ar = r / pl.unit
            val b = stroke.bounds
            if (ax < b.left - ar || ax > b.right + ar || ay < b.top - ar || ay > b.bottom + ar) return@forEachStroke
            if (StrokeGeometry.distanceTo(stroke, ax, ay) <= ar + stroke.width / 2) hits += stroke
        }
        for (h in hits) {
            ann.ink.remove(h); erasedNow += h
        }
        if (hits.isNotEmpty()) invalidateInk()
        if (engine?.isFixed == false) {
            val t = textAt(p.x, p.y, 0f)
            if (t != null) {
                val loc = Locator(t.first.block, t.second)
                val h = ann.highlights.firstOrNull { loc >= it.start && loc < it.end }
                if (h != null) {
                    ann.highlights.remove(h); erasedHl += h; invalidateContent()
                }
            }
        }
    }

    private fun insertAt(sx: Float, sy: Float) {
        val page = currentPage() ?: return
        val p = toPage(sx, sy)
        val item = Anchoring.itemAt(page, p.y) ?: return
        val anchor = RectF(sx - 1, sy - 1, sx + 1, sy + 1)
        if (engine?.isFixed == true) {
            when (item) {
                is PdfSliceItem -> {
                    val y = ((p.y - item.pageRect.top) / item.pageRect.height()).coerceIn(0f, 1f)
                    insertMarkY = sy
                    listener?.onInsertSpace(InsertTarget.Pdf(item.page, y, y in 0.03f..0.97f), anchor)
                }
                is SpaceItem -> listener?.onInsertSpace(InsertTarget.Pdf(item.space.loc.block, 1f, false), anchor)
                else -> {}
            }
            invalidate()
            return
        }
        val (target, markY) = flowInsertTarget(page, p) ?: return
        insertMarkY = markY
        invalidate()
        listener?.onInsertSpace(InsertTarget.Flow(target), anchor)
    }

    /** Where a writing space tapped at page point [p] goes, and the y of that spot. */
    private fun flowInsertTarget(page: Page, p: PointF): Pair<Locator, Float>? {
        return when (val item = Anchoring.itemAt(page, p.y) ?: return null) {
            is TextItem -> {
                val line = item.lineAt(p.y)
                val mid = (item.lineTop(line) + item.lineBottom(line)) / 2
                if (p.y < mid) Locator(item.block, item.pieceStart + item.layout.getLineStart(line)) to item.lineTop(line)
                else Locator(item.block, item.pieceStart + item.layout.getLineEnd(line)) to item.lineBottom(line)
            }
            is ImageItem -> {
                val before = p.y < item.rect.centerY()
                Locator(item.block, if (before) 0 else 1) to (if (before) item.rect.top else item.rect.bottom)
            }
            is RuleItem -> Locator(item.block, 1) to item.rect.bottom
            is SpaceItem -> item.space.loc to item.rect.bottom
            is PdfSliceItem -> null
        }
    }

    /** Height (fraction of a page) for a space dragged from [top] to [y]; the very bottom means a full page. */
    private fun dragHeight(top: Float, y: Float): Float {
        val s = spec ?: return 0.5f
        if (y >= s.content.bottom + dp(6f)) return 1f
        return ((y - top) / s.content.height()).coerceIn(MIN_SPACE, 1f)
    }

    /** The writing space whose × (top-right corner) is under (x, y), in view coordinates. */
    private fun spaceCloseAt(x: Float, y: Float): SpaceItem? {
        val page = currentPage() ?: return null
        val p = toPage(x, y)
        for (it in page.items) {
            if (it !is SpaceItem) continue
            val c = PageRenderer.closeCenter(it.rect, density)
            if (hypot(p.x - c.x, p.y - c.y) < dp(24f)) return it
        }
        return null
    }

    /** A flow writing space whose bottom edge (grip) is under the finger. */
    private fun spaceEdgeAt(x: Float, y: Float): SpaceItem? {
        if (engine?.isFixed != false) return null
        val page = currentPage() ?: return null
        // Only the grip itself (bottom centre), so long-presses elsewhere in the space open its menu.
        for (it in page.items) {
            if (it !is SpaceItem || it.space.kind != WritingSpace.FLOW) continue
            if (abs(x - it.rect.centerX()) > dp(70f)) continue
            if (y >= it.rect.bottom - dp(24f) && y <= it.rect.bottom + dp(12f)) return it
        }
        return null
    }

    private fun drawSizing(c: Canvas) {
        val s = spec ?: return
        val top: Float
        val y: Float
        val r = resizing
        when {
            r != null -> {
                top = r.rect.top; y = resizeY
            }
            penInsert && insertDragging -> {
                top = insertDownY; y = insertCurY
            }
            else -> return
        }
        val h = dragHeight(top, y)
        val bottom = minOf(top + h * s.content.height(), s.content.bottom)
        val rect = RectF(s.content.left, top, s.content.right, bottom)
        overlay.style = Paint.Style.FILL
        overlay.color = (colors.faint and 0x00FFFFFF) or 0x55000000
        c.drawRect(rect, overlay)
        overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = dp(2f)
        overlay.color = colors.text
        overlay.pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(5f)), 0f)
        c.drawRect(rect, overlay)
        overlay.pathEffect = null
        val label = if (h >= 0.999f) "Full page" else "${(h * 100).roundToInt()}% of a page"
        textPaint.color = colors.text
        val tw = textPaint.measureText(label)
        val ly = (bottom - dp(10f)).coerceAtLeast(top + dp(18f))
        c.drawText(label, rect.centerX() - tw / 2, ly, textPaint)
    }

    // ----------------------------------------------------------------- fingers

    private fun palmLikely(): Boolean =
        SystemClock.uptimeMillis() - lastStylusTime < 450 || hovering

    private fun cancelFinger() {
        removeCallbacks(longPress)
        fActive = false
        dragHandle = 0
        resizeCandidate = null
        if (resizing != null) {
            resizing = null; invalidate()
        }
    }

    private fun finger(e: MotionEvent, act: Int): Boolean {
        when (act) {
            MotionEvent.ACTION_DOWN -> {
                fActive = true; fMoved = false; fLong = false; fMulti = false; pinched = false
                fDownX = e.x; fDownY = e.y; fLastX = e.x; fLastY = e.y
                fIgnore = palmLikely()
                if (fIgnore) return true
                dragHandle = hitHandle(e.x, e.y)
                if (dragHandle != 0) return true
                if (armedInsert) {
                    startPen(e, 0, false, finger = true)
                    return true
                }
                resizeCandidate = spaceEdgeAt(e.x, e.y)
                resizing = null
                if (fingerDraw && resizeCandidate == null) {
                    startPen(e, 0, false, finger = true)
                    return true
                }
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (fIgnore) return true
                fMulti = true
                if (e.pointerCount >= 2) {
                    pinchDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
                    pinchZoom = zoom
                    pinchScale = 1f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!fActive || fIgnore) return true
                if (e.pointerCount >= 2 && fMulti) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    pinchScale = d / pinchDist
                    if (abs(pinchScale - 1f) > 0.06f) pinched = true
                    if (engine?.isFixed == true) {
                        val fx = (e.getX(0) + e.getX(1)) / 2
                        val fy = (e.getY(0) + e.getY(1)) / 2
                        setZoom((pinchZoom * pinchScale).coerceIn(1f, 5f), fx, fy)
                    }
                    return true
                }
                val dx = e.x - fDownX
                val dy = e.y - fDownY
                if (!fMoved && hypot(dx, dy) > slop) {
                    fMoved = true
                    removeCallbacks(longPress)
                }
                val cand = resizeCandidate
                if (resizing == null && cand != null && abs(dy) > slop * 2) {
                    resizeCandidate = null
                    // Only a clear up/down drag on the grip resizes.
                    if (abs(dy) > abs(dx) * 1.5f) {
                        resizing = cand
                        removeCallbacks(longPress)
                    }
                }
                resizing?.let { r ->
                    // The edge follows the finger from wherever it was grabbed.
                    resizeY = r.rect.bottom + dy
                    invalidate()
                    return true
                }
                if (dragHandle != 0) {
                    dragTo(e.x, e.y); return true
                }
                if (zoom > 1.001f && fMoved && e.pointerCount == 1) {
                    panX += e.x - fLastX
                    panY += e.y - fLastY
                    clampPan()
                    invalidate()
                }
                fLastX = e.x; fLastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (fMulti && e.pointerCount == 2) {
                    if (engine?.isFixed == true) requestHiRes()
                    else if (abs(pinchScale - 1f) > 0.12f) {
                        val steps = (ln(pinchScale.toDouble()) / ln(1.2)).roundToInt()
                        if (steps != 0) listener?.onFontPinch(steps)
                    }
                    pinchScale = 1f
                    // Keep panning smooth with the finger that stays down.
                    val keep = if (e.actionIndex == 0) 1 else 0
                    fLastX = e.getX(keep); fLastY = e.getY(keep)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!fActive) return true
                fActive = false
                if (fIgnore) return true
                resizeCandidate = null
                resizing?.let { sp ->
                    resizing = null
                    invalidate()
                    listener?.onSpaceResized(sp.space, dragHeight(sp.rect.top, sp.rect.bottom + (e.y - fDownY)))
                    return true
                }
                if (dragHandle != 0) {
                    dragHandle = 0
                    finishSelectionDrag()
                    return true
                }
                if (fLong) return true
                if (fMulti) {
                    if (pinched) {
                        if (zoom > 1.001f) requestHiRes()
                        return true
                    }
                    // Two-finger swipe still turns pages (useful with finger drawing on).
                    val dx = e.x - fDownX
                    if (engine?.isFixed != true || zoom <= 1.001f) {
                        if (abs(dx) > dp(60f) && abs(dx) > abs(e.y - fDownY)) swipe(dx)
                    }
                    return true
                }
                if (zoom > 1.001f && fMoved) {
                    requestHiRes(); return true
                }
                val dx = e.x - fDownX
                val dy = e.y - fDownY
                if (fMoved) {
                    if (abs(dx) > dp(40f) && abs(dx) > abs(dy) * 1.2f) swipe(dx)
                    else if (dy > dp(80f) && fDownY < height * 0.2f) listener?.onToggleChrome()
                } else tap(e.x, e.y)
            }
        }
        return true
    }

    private fun swipe(dx: Float) {
        val forward = if (rtl) dx > 0 else dx < 0
        if (forward) next() else prev()
    }

    private fun tap(x: Float, y: Float) {
        if (selection != null || region != null) {
            clearSelection(); listener?.onSelectionCleared(); return
        }
        if (armedInsert) {
            insertAt(x, y); return
        }
        spaceCloseAt(x, y)?.let {
            listener?.onDeleteSpace(it.space); return
        }
        if (x > width - dp(64f) && y < dp(64f)) {
            listener?.onToggleBookmark(); return
        }
        val s = spec
        if (s != null && y > height - dp(40f) && x < width * 0.5f && x > s.content.left - dp(8f)) {
            listener?.onFooterTap(); return
        }
        if (engine?.isFixed == false) {
            val t = textAt(x, y, 0f)
            if (t != null) {
                val (item, off) = t
                val loc = Locator(item.block, off)
                val line = item.lineAt(y)
                val lx = x - item.rect.left
                if (lx >= item.layout.getLineLeft(line) - dp(4f) && lx <= item.layout.getLineRight(line) + dp(4f)) {
                    val h = ann.highlights.firstOrNull { loc >= it.start && loc < it.end }
                    if (h != null) {
                        val page = currentPage()!!
                        PageRenderer.rangePath(page, h.start, h.end, selPath)
                        val b = RectF(); selPath.computeBounds(b, true)
                        listener?.onHighlightTap(h, b); return
                    }
                    val text = blockText(item.block)
                    if (text is Spanned) {
                        val link = text.getSpans(off, (off + 1).coerceAtMost(text.length), LinkSpan::class.java).firstOrNull()
                        val target = link?.let { doc?.resolveLink(it.target) }
                        if (target != null) {
                            listener?.onLink(target); return
                        }
                    }
                }
            }
        }
        if (y < height * 0.14f) {
            listener?.onToggleChrome(); return
        }
        // The back zone is the first third of the visible page (so the pen toolbar can't cover it),
        // plus the margin before it; everything else goes forward.
        val c = spec?.content
        val l = c?.left ?: 0f
        val r = c?.right ?: width.toFloat()
        val zone = (r - l) * 0.35f
        val backZone = if (rtl) x > r - zone else x < l + zone
        if (backZone) prev() else next()
    }

    private fun onLongPress() {
        if (!fActive || fMoved || fIgnore || fMulti) return
        resizeCandidate = null
        val page = currentPage() ?: return
        val p = toPage(fDownX, fDownY)
        val e = engine ?: return
        val item = page.items.firstOrNull { p.y >= it.rect.top && p.y <= it.rect.bottom }
        when {
            item is SpaceItem -> {
                fLong = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener?.onSpaceLongPress(item.space, toScreen(item.rect))
            }
            e.isFixed && item is PdfSliceItem -> {
                fLong = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val pr = item.pageRect
                val cx = ((p.x - pr.left) / pr.width()).coerceIn(0f, 1f)
                val cy = ((p.y - pr.top) / pr.height()).coerceIn(0f, 1f)
                regionPage = item.page
                region = RectF((cx - 0.3f).coerceAtLeast(0.02f), (cy - 0.04f).coerceAtLeast(item.y0), (cx + 0.3f).coerceAtMost(0.98f), (cy + 0.04f).coerceAtMost(item.y1))
                invalidate()
                finishSelectionDrag()
            }
            !e.isFixed -> {
                val t = textAt(p.x, p.y, 0f) ?: return
                fLong = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val (s, en) = wordRange(t.first.block, t.second)
                selection = TextSel(Locator(t.first.block, s), Locator(t.first.block, max(en, s + 1)))
                invalidate()
                finishSelectionDrag()
            }
        }
    }

    /** True if (x, y) is on a selection handle or a PDF region corner. */
    fun hitsHandle(x: Float, y: Float): Boolean = (selection != null || region != null) && hitHandle(x, y) != 0

    private fun hitHandle(x: Float, y: Float): Int {
        val page = currentPage() ?: return 0
        val r = dp(28f)
        selection?.let { sel ->
            handlePoint(page, sel.start, false)?.let { if (hypot(it.x - x, it.y + dp(12f) - y) < r) return 1 }
            handlePoint(page, sel.end, true)?.let { if (hypot(it.x - x, it.y + dp(12f) - y) < r) return 2 }
        }
        region?.let { rg ->
            val sr = regionScreenRect(page, rg)?.let { toScreen(it) } ?: return 0
            val corners = listOf(PointF(sr.left, sr.top), PointF(sr.right, sr.top), PointF(sr.left, sr.bottom), PointF(sr.right, sr.bottom))
            corners.forEachIndexed { i, c -> if (hypot(c.x - x, c.y - y) < r) return 10 + i }
            if (sr.contains(x, y)) {
                fLastX = x; fLastY = y; return 20
            }
        }
        return 0
    }

    private fun dragTo(x: Float, y: Float) {
        val sel = selection
        if (sel != null && (dragHandle == 1 || dragHandle == 2)) {
            val loc = nearestText(x, y - dp(22f)) ?: return
            if (dragHandle == 1) sel.start = loc else sel.end = loc
            if (sel.end < sel.start) {
                val t = sel.start; sel.start = sel.end; sel.end = t
                dragHandle = 3 - dragHandle
            }
            invalidate()
            return
        }
        val rg = region ?: return
        val page = currentPage() ?: return
        val slice = page.items.filterIsInstance<PdfSliceItem>().firstOrNull { it.page == regionPage } ?: return
        val pr = slice.pageRect
        val p = toPage(x, y)
        val nx = ((p.x - pr.left) / pr.width()).coerceIn(0f, 1f)
        val ny = ((p.y - pr.top) / pr.height()).coerceIn(slice.y0, slice.y1)
        when (dragHandle) {
            10 -> { rg.left = nx; rg.top = ny }
            11 -> { rg.right = nx; rg.top = ny }
            12 -> { rg.left = nx; rg.bottom = ny }
            13 -> { rg.right = nx; rg.bottom = ny }
            20 -> {
                val lp = toPage(fLastX, fLastY)
                val ddx = (p.x - lp.x) / pr.width()
                val ddy = (p.y - lp.y) / pr.height()
                rg.offset(ddx, ddy)
                fLastX = x; fLastY = y
            }
        }
        rg.sort()
        invalidate()
    }

    private fun finishSelectionDrag() {
        val page = currentPage() ?: return
        selection?.let { sel ->
            val b = selectionBounds(page)
            listener?.onSelection(sel, toScreen(b))
        }
        region?.let { r ->
            val sr = regionPageRect(page, r) ?: return
            listener?.onRegion(regionPage, RectF(r), toScreen(sr))
        }
    }

    // ----------------------------------------------------------------- zoom

    private fun setZoom(z: Float, fx: Float, fy: Float) {
        val px = (fx - panX) / zoom
        val py = (fy - panY) / zoom
        zoom = z
        panX = fx - px * zoom
        panY = fy - py * zoom
        clampPan()
        invalidate()
    }

    private fun clampPan() {
        panX = panX.coerceIn(width - width * zoom, 0f)
        panY = panY.coerceIn(height - height * zoom, 0f)
        zoomMatrix.setScale(zoom, zoom)
        zoomMatrix.postTranslate(panX, panY)
        zoomMatrix.invert(inverse)
    }

    private fun resetZoom() {
        zoom = 1f; panX = 0f; panY = 0f
        clampPan()
        hiRes = null
        pdfCache?.cancelZoom()
    }

    private fun requestHiRes() {
        if (zoom <= 1.001f) {
            hiRes = null; return
        }
        val page = currentPage() ?: return
        val slice = page.items.filterIsInstance<PdfSliceItem>().firstOrNull() ?: return
        val cache = pdfCache ?: return
        val pr = slice.pageRect
        val m = Matrix()
        val pts = (engine as? com.rotfarmer.ereader.layout.PdfEngine)?.pdf?.pageSize(slice.page) ?: return
        val s = pr.width() / pts.width
        m.setScale(s, s)
        m.postTranslate(pr.left, pr.top)
        m.postConcat(zoomMatrix)
        val key = zoomKey()
        cache.renderZoomed(slice.page, m, width, height, renderer.eink) { bmp ->
            hiRes = bmp
            hiResKey = key
            invalidate()
        }
    }
}
