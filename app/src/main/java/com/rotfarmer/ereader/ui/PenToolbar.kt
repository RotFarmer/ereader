package com.rotfarmer.ereader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import com.rotfarmer.ereader.data.Settings
import com.rotfarmer.ereader.ink.InkPainter
import com.rotfarmer.ereader.ink.Tools
import kotlin.math.PI
import kotlin.math.sin

/** Draws a sample squiggle in a pen's style, used as the pen's button and as a preview. */
@SuppressLint("ViewConstructor")
class PenSample(ctx: Context, var tool: Int, var color: Int, var widthDp: Float, private val c: UiColors, var sensitivity: Float) : View(ctx) {
    var selected2 = false
        set(v) {
            field = v; invalidate()
        }
    var maxWidthDp = 7f
    private val painter = InkPainter()
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        if (selected2) {
            frame.style = Paint.Style.FILL
            frame.color = c.soft
            canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), 6 * d, 6 * d, frame)
            frame.style = Paint.Style.STROKE
            frame.strokeWidth = 2 * d
            frame.color = c.fg
            canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), 6 * d, 6 * d, frame)
        }
        painter.dark = c.dark
        painter.sensitivity = sensitivity
        if (tool == Tools.ERASER) {
            drawEraser(canvas, d); return
        }
        val n = 40
        val pts = FloatArray(n * 3)
        val padX = width * 0.2f
        val w = width - 2 * padX
        for (i in 0 until n) {
            val t = i / (n - 1f)
            pts[i * 3] = padX + t * w
            pts[i * 3 + 1] = height / 2f + sin(t * 2 * PI).toFloat() * height * 0.16f
            pts[i * 3 + 2] = 0.25f + 0.75f * sin(t * PI).toFloat()
        }
        val wpx = minOf(widthDp, maxWidthDp) * d
        painter.drawLive(canvas, pts, n, tool, color, wpx)
    }

    private fun drawEraser(canvas: Canvas, d: Float) {
        canvas.save()
        canvas.rotate(-35f, width / 2f, height / 2f)
        val r = RectF(width / 2f - 11 * d, height / 2f - 6 * d, width / 2f + 11 * d, height / 2f + 6 * d)
        frame.style = Paint.Style.STROKE
        frame.strokeWidth = 1.8f * d
        frame.color = c.fg
        canvas.drawRoundRect(r, 3 * d, 3 * d, frame)
        frame.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(r.left, r.top, r.left + 8 * d, r.bottom), 3 * d, 3 * d, frame)
        canvas.restore()
    }
}

/**
 * The floating pen toolbar: pens, eraser, "insert writing space", color/width, undo/redo.
 * Tapping the selected pen again opens its color and width options.
 */
@SuppressLint("ViewConstructor")
class PenToolbar(ctx: Context, private val c: UiColors, private val settings: Settings, private val cb: Callbacks) : LinearLayout(ctx) {
    interface Callbacks {
        fun onToolChanged()
        fun onToolOptions(anchor: View)
        fun onInsertSpace(armed: Boolean)
        fun onUndo()
        fun onRedo()
        fun onSideChanged()
        fun onExpandedChanged()
        fun onHide()
    }

    private fun hideButton() = IconButton(context, "\ue8f5", c, 40).apply {
        textSize = 19f
        contentDescription = "Hide pen toolbar"
        setOnClickListener { cb.onHide() }
    }

    private val samples = HashMap<Int, PenSample>()
    private var insertBtn: IconButton? = null
    private var undoBtn: IconButton? = null
    private var redoBtn: IconButton? = null
    private var colorDot: ColorDot? = null
    var insertArmed = false
        set(v) {
            field = v; insertBtn?.active = v
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = panelBg(ctx, c, 10f)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        build()
    }

    fun build() {
        removeAllViews()
        samples.clear()
        val expanded = settings.toolbarExpanded
        val toggle = IconButton(context, if (expanded) "" else "", c, 40)
        toggle.setOnClickListener {
            settings.toolbarExpanded = !settings.toolbarExpanded
            build()
            cb.onExpandedChanged()
        }
        addView(toggle)
        if (!expanded) {
            addView(sample(settings.tool))
            addView(hideButton())
            return
        }
        for (t in Tools.DRAWING) addView(sample(t))
        addView(sample(Tools.ERASER))
        addView(sep())
        insertBtn = IconButton(context, "", c, 44).apply {
            active = insertArmed
            contentDescription = "Insert writing space"
            setOnClickListener {
                insertArmed = !insertArmed
                cb.onInsertSpace(insertArmed)
            }
        }
        addView(insertBtn)
        colorDot = ColorDot(context, currentColor(), c).apply {
            layoutParams = LayoutParams(dp(44), dp(40))
            contentDescription = "Color and size"
            setOnClickListener { cb.onToolOptions(this) }
        }
        addView(colorDot)
        addView(sep())
        undoBtn = IconButton(context, Icons.UNDO, c, 44).apply { setOnClickListener { cb.onUndo() } }
        redoBtn = IconButton(context, Icons.REDO, c, 44).apply { setOnClickListener { cb.onRedo() } }
        addView(undoBtn)
        addView(redoBtn)
        addView(IconButton(context, Icons.SWAP, c, 40).apply {
            textSize = 18f
            contentDescription = "Move toolbar to the other side"
            setOnClickListener {
                settings.toolbarRight = !settings.toolbarRight
                cb.onSideChanged()
            }
        })
        addView(hideButton())
    }

    private fun currentColor(): Int {
        val t = settings.tool
        return if (t == Tools.ERASER) c.muted else settings.toolColor(t)
    }

    private fun sep() = View(context).apply {
        setBackgroundColor(c.hairline)
        layoutParams = LayoutParams(dp(28), dp(1)).apply { topMargin = dp(4); bottomMargin = dp(4) }
    }

    private fun sample(t: Int): PenSample {
        val s = PenSample(context, t, if (t == Tools.ERASER) c.fg else settings.toolColor(t), settings.toolWidth(t), c, settings.pressure)
        s.layoutParams = LayoutParams(dp(46), dp(40))
        s.maxWidthDp = if (t == Tools.HIGHLIGHTER) 10f else 5f
        s.selected2 = t == settings.tool
        s.contentDescription = Tools.name(t)
        s.setOnClickListener {
            if (settings.tool == t && settings.toolbarExpanded) {
                cb.onToolOptions(s)
            } else {
                settings.tool = t
                if (insertArmed) {
                    insertArmed = false; cb.onInsertSpace(false)
                }
                refresh()
                cb.onToolChanged()
            }
        }
        samples[t] = s
        return s
    }

    fun refresh() {
        for ((t, s) in samples) {
            s.selected2 = t == settings.tool
            s.color = if (t == Tools.ERASER) c.fg else settings.toolColor(t)
            s.widthDp = settings.toolWidth(t)
            s.sensitivity = settings.pressure
            s.invalidate()
        }
        if (!settings.toolbarExpanded) {
            build(); return
        }
        colorDot?.let { it.color = currentColor(); it.invalidate() }
    }

    fun setUndoState(canUndo: Boolean, canRedo: Boolean) {
        undoBtn?.isEnabled = canUndo
        redoBtn?.isEnabled = canRedo
    }
}

/** Color palette, width and pressure options for one pen. */
@SuppressLint("ViewConstructor")
class ToolOptions(ctx: Context, private val c: UiColors, private val settings: Settings, private val tool: Int, private val onChanged: () -> Unit, onClearPage: (() -> Unit)?) : LinearLayout(ctx) {
    private val preview: PenSample

    init {
        orientation = VERTICAL
        background = panelBg(ctx, c)
        setPadding(dp(16), dp(12), dp(16), dp(14))
        addView(label(ctx, Tools.name(tool), c, 17f, bold = true, serif = true))
        preview = PenSample(ctx, tool, settings.toolColor(tool), settings.toolWidth(tool), c, settings.pressure).apply {
            maxWidthDp = 40f
            layoutParams = LayoutParams(dp(260), dp(70))
        }
        if (tool != Tools.ERASER) {
            addView(preview)
            addView(sectionTitle(ctx, "Color", c))
            val colors = if (tool == Tools.HIGHLIGHTER) Tools.HIGHLIGHTER_COLORS else Tools.INK_COLORS
            val grid = GridLayout(ctx).apply { columnCount = 8 }
            val dots = ArrayList<ColorDot>()
            for (col in colors) {
                val dot = ColorDot(ctx, col, c).apply {
                    selected2 = col == settings.toolColor(tool)
                    layoutParams = GridLayout.LayoutParams().apply { width = dp(34); height = dp(34) }
                }
                dot.setOnClickListener {
                    settings.setToolColor(tool, col)
                    dots.forEach { it.selected2 = it.color == col }
                    preview.color = col; preview.invalidate()
                    onChanged()
                }
                dots += dot
                grid.addView(dot)
            }
            addView(grid)
        }
        addView(sectionTitle(ctx, if (tool == Tools.ERASER) "Eraser size" else "Thickness", c))
        val minW = Tools.minWidth(tool)
        val maxW = Tools.maxWidth(tool)
        val steps = 100
        val cur = ((settings.toolWidth(tool) - minW) / (maxW - minW) * steps).toInt().coerceIn(0, steps)
        addView(slider(ctx, steps, cur, c) { v, _ ->
            val w = minW + (maxW - minW) * v / steps
            settings.setToolWidth(tool, w)
            preview.widthDp = w; preview.invalidate()
            onChanged()
        })
        if (tool != Tools.ERASER && tool != Tools.HIGHLIGHTER && tool != Tools.MARKER) {
            addView(sectionTitle(ctx, "Pressure sensitivity", c))
            addView(slider(ctx, 100, (settings.pressure * 100).toInt(), c) { v, _ ->
                settings.pressure = v / 100f
                preview.sensitivity = settings.pressure; preview.invalidate()
                onChanged()
            })
            addView(label(ctx, "How much pressing harder with the S Pen thickens the line.", c, 12f, c.muted))
        }
        if (tool == Tools.HIGHLIGHTER) {
            addView(divider(ctx, c))
            addView(toggleRow(ctx, "Snap to text", "Highlighting across words selects the words", settings.smartHighlight, c) {
                settings.smartHighlight = it; onChanged()
            })
        }
        if (tool == Tools.ERASER) {
            addView(label(ctx, "Tip: hold the S Pen button to erase with any pen.", c, 12.5f, c.muted).apply { setPadding(0, dp(4), 0, dp(8)) })
            if (onClearPage != null) {
                addView(TextButton(ctx, "Erase all ink on this page", c).apply { setOnClickListener { onClearPage() } })
            }
        }
    }
}

fun roundedBg(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
