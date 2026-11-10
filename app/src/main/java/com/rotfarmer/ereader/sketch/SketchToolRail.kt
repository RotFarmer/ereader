package com.rotfarmer.ereader.sketch

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.rotfarmer.ereader.ink.Tools
import com.rotfarmer.ereader.ui.ColorDot
import com.rotfarmer.ereader.ui.IconButton
import com.rotfarmer.ereader.ink.InkColors
import com.rotfarmer.ereader.ui.PenSample
import com.rotfarmer.ereader.ui.UiColors
import com.rotfarmer.ereader.ui.dp

/**
 * The sketchbook's tools, always on screen: every pen, the eraser, lasso selection, shapes and
 * the current colour and size. Tapping the selected pen again opens its colour and size.
 */
@SuppressLint("ViewConstructor")
class SketchToolRail(private val host: SketchActivity, private val c: UiColors) : ScrollView(host) {
    private val column = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6), dp(8), dp(6), dp(8))
    }
    private val samples = LinkedHashMap<Int, PenSample>()
    private val lasso: IconButton
    private val shapes: IconButton
    private val colorDot: ColorDot

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(c.bg)
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        for (t in Tools.DRAWING) column.addView(sample(t))
        column.addView(sample(Tools.ERASER))
        column.addView(separator())
        lasso = IconButton(host, ICON_LASSO, c, 46).apply {
            contentDescription = "Select with lasso"
            setOnClickListener { host.setMode(if (host.canvas.mode == SketchCanvas.LASSO) SketchCanvas.DRAW else SketchCanvas.LASSO) }
        }
        column.addView(lasso)
        shapes = IconButton(host, ICON_SHAPES, c, 46).apply {
            contentDescription = "Shapes"
            setOnClickListener { host.showShapes(this) }
        }
        column.addView(shapes)
        column.addView(separator())
        colorDot = ColorDot(host, 0, c).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(42))
            contentDescription = "Colour and size"
            setOnClickListener { host.showPenOptions(this) }
        }
        column.addView(colorDot)
        refresh()
    }

    /** Re-reads the current tool, colours and mode. */
    fun refresh() {
        val s = host.settings
        val mode = host.canvas.mode
        val drawing = mode == SketchCanvas.DRAW || mode in SketchCanvas.SHAPES
        for ((t, v) in samples) {
            v.color = if (t == Tools.ERASER) c.fg else s.toolColor(t)
            v.widthDp = s.toolWidth(t)
            v.sensitivity = s.pressure
            v.selected2 = drawing && t == s.tool
            v.invalidate()
        }
        lasso.active = mode == SketchCanvas.LASSO
        shapes.active = mode in SketchCanvas.SHAPES
        shapes.text = SketchCanvas.shapeIcon(mode) ?: ICON_SHAPES
        colorDot.color = if (s.tool == Tools.ERASER) c.muted else InkColors.display(s.toolColor(s.tool), c.dark)
        colorDot.invalidate()
    }

    private fun sample(t: Int): PenSample {
        val s = host.settings
        val v = PenSample(host, t, if (t == Tools.ERASER) c.fg else s.toolColor(t), s.toolWidth(t), c, s.pressure)
        v.layoutParams = LinearLayout.LayoutParams(dp(48), dp(42)).apply { bottomMargin = dp(2) }
        v.maxWidthDp = when (t) {
            Tools.HIGHLIGHTER -> 10f
            Tools.BRUSH -> 7f
            Tools.SOFT_BRUSH -> 9f
            else -> 5f
        }
        v.contentDescription = Tools.name(t)
        v.setOnClickListener { host.selectTool(t, v) }
        samples[t] = v
        return v
    }

    private fun separator() = View(host).apply {
        setBackgroundColor(c.hairline)
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(1)).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    companion object {
        const val ICON_LASSO = ""   // highlight_alt
        const val ICON_SHAPES = ""  // category
    }
}
