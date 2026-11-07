package com.rotfarmer.ereader.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.rotfarmer.ereader.render.Fonts

object Icons {
    const val BACK = ""
    const val TOC = ""
    const val BOOKMARK = ""
    const val BOOKMARK_BORDER = ""
    const val BOOKMARKS = ""
    const val TEXT = ""
    const val BRIGHTNESS = ""
    const val LIGHT = ""
    const val DARK = ""
    const val EDIT = ""
    const val UNDO = ""
    const val REDO = ""
    const val MORE = ""
    const val ADD = ""
    const val CLOSE = ""
    const val DELETE = ""
    const val COPY = ""
    const val SHARE = ""
    const val QUOTE = ""
    const val SWAP = ""
    const val CHEV_LEFT = ""
    const val CHEV_RIGHT = ""
    const val LIBRARY = ""
    const val NOTE = ""
    const val CHECK = ""
    const val INSERT_SPACE = ""
    const val SEARCH = ""
    const val SORT = ""
    const val INFO = ""
    const val HIGHLIGHT = ""
    const val LIBRARY_ADD = ""
    const val STORIES = ""
    const val DRAW = ""
    const val TRANSLATE = ""
    const val SETTINGS_TUNE = ""
    const val CROP = ""
}

/** Colors for the app's own controls (menus, panels, library). */
class UiColors(val dark: Boolean) {
    val bg = if (dark) 0xFF151515.toInt() else 0xFFF5F3ED.toInt()
    val fg = if (dark) 0xFFE6E6E3.toInt() else 0xFF141414.toInt()
    val muted = if (dark) 0xFF9A9A97.toInt() else 0xFF6A6964.toInt()
    val line = if (dark) 0xFF4A4A4A.toInt() else 0xFF1C1C1C.toInt()
    val soft = if (dark) 0xFF262626.toInt() else 0xFFE6E3D9.toInt()
    val hairline = if (dark) 0xFF333333.toInt() else 0xFFCFCBC0.toInt()
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Number): Int = context.dp(v)

fun panelBg(ctx: Context, c: UiColors, radius: Float = 8f, stroke: Float = 1.5f, fill: Int = c.bg): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = ctx.dp(radius).toFloat()
        setStroke(ctx.dp(stroke).coerceAtLeast(1), c.line)
    }

/** A square icon button; pressed or [active] buttons are drawn inverted, like e-ink UIs. */
@SuppressLint("ViewConstructor")
class IconButton(ctx: Context, glyph: String, private val c: UiColors, sizeDp: Int = 46) : TextView(ctx) {
    // Android can call setPressed/setEnabled from the super constructor, before fields exist.
    private var ready = false
    var active = false
        set(v) {
            field = v; refresh()
        }

    init {
        text = glyph
        typeface = Fonts.icons(ctx)
        textSize = 24f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        includeFontPadding = false
        ready = true
        refresh()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        refresh()
    }

    private fun refresh() {
        if (!ready) return
        val inv = isPressed || active
        setTextColor(if (inv) c.bg else if (isEnabled) c.fg else c.muted)
        background = if (inv) GradientDrawable().apply { setColor(c.fg); cornerRadius = dp(6).toFloat() } else null
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        refresh()
    }
}

/** A rectangular bordered button with a label. */
@SuppressLint("ViewConstructor")
class TextButton(ctx: Context, label: String, private val c: UiColors, private val filled: Boolean = false) : TextView(ctx) {
    private var ready = false
    var active = false
        set(v) {
            field = v; refresh()
        }

    init {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minHeight = dp(40)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        ready = true
        refresh()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        refresh()
    }

    private fun refresh() {
        if (!ready) return
        val inv = isPressed || active || filled
        setTextColor(if (inv) c.bg else c.fg)
        background = panelBg(context, c, 6f, 1.2f, if (inv) c.fg else c.bg)
    }
}

/** A row of mutually exclusive choices. */
@SuppressLint("ViewConstructor")
class Segmented(ctx: Context, labels: List<String>, selected: Int, c: UiColors, private val onSelect: (Int) -> Unit) : LinearLayout(ctx) {
    private val buttons = ArrayList<TextButton>()
    var selected = selected
        set(v) {
            field = v
            buttons.forEachIndexed { i, b -> b.active = i == v }
        }

    init {
        orientation = HORIZONTAL
        labels.forEachIndexed { i, l ->
            val b = TextButton(ctx, l, c)
            b.setOnClickListener { this.selected = i; onSelect(i) }
            b.active = i == selected
            addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = if (i < labels.size - 1) dp(6) else 0 })
            buttons += b
        }
    }

    fun setTypefaces(faces: List<Typeface>) = buttons.forEachIndexed { i, b -> faces.getOrNull(i)?.let { b.typeface = it } }
}

fun label(ctx: Context, text: String, c: UiColors, size: Float = 14f, color: Int = c.fg, bold: Boolean = false, serif: Boolean = false): TextView =
    TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = when {
            serif -> Fonts.get(ctx, "literata", if (bold) Typeface.BOLD else Typeface.NORMAL)
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

fun sectionTitle(ctx: Context, text: String, c: UiColors): TextView =
    label(ctx, text.uppercase(), c, 11.5f, c.muted, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(14), 0, dp(6))
    }

fun slider(ctx: Context, max: Int, value: Int, c: UiColors, onChange: (Int, Boolean) -> Unit): SeekBar =
    SeekBar(ctx).apply {
        this.max = max
        progress = value
        progressTintList = ColorStateList.valueOf(c.fg)
        progressBackgroundTintList = ColorStateList.valueOf(c.muted)
        thumbTintList = ColorStateList.valueOf(c.fg)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p, false)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = onChange(s.progress, true)
        })
    }

fun toggleRow(ctx: Context, title: String, subtitle: String?, checked: Boolean, c: UiColors, onChange: (Boolean) -> Unit): LinearLayout {
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ctx.dp(6), 0, ctx.dp(6))
    }
    val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(label(ctx, title, c, 15f))
    if (subtitle != null) texts.addView(label(ctx, subtitle, c, 12.5f, c.muted))
    row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    @Suppress("UseSwitchCompatOrMaterialCode")
    val sw = Switch(ctx).apply {
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(c.fg, c.bg))
        trackTintList = ColorStateList(states, intArrayOf(c.muted, c.hairline))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    row.addView(sw)
    row.setOnClickListener { sw.toggle() }
    return row
}

fun divider(ctx: Context, c: UiColors): View = View(ctx).apply {
    setBackgroundColor(c.hairline)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)).apply {
        topMargin = ctx.dp(6); bottomMargin = ctx.dp(6)
    }
}

fun dialogTheme(dark: Boolean) =
    if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

fun dialog(ctx: Context, dark: Boolean): AlertDialog.Builder = AlertDialog.Builder(ctx, dialogTheme(dark))

/** A small filled circle showing a color. */
class ColorDot(ctx: Context, var color: Int, private val c: UiColors) : View(ctx) {
    var selected2 = false
        set(v) {
            field = v; invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        p.style = Paint.Style.FILL
        p.color = color
        canvas.drawCircle(cx, cy, r * 0.62f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1).toFloat()
        p.color = if (Color.luminance(color) > 0.85f || Color.luminance(color) < 0.05f) c.muted else Color.TRANSPARENT
        if (p.color != Color.TRANSPARENT) canvas.drawCircle(cx, cy, r * 0.62f, p)
        if (selected2) {
            p.color = c.fg
            p.strokeWidth = dp(2).toFloat()
            canvas.drawCircle(cx, cy, r * 0.9f, p)
        }
    }
}

/**
 * Floating panels shown above the page. A transparent scrim behind the panel closes it when
 * tapped with a finger; S Pen touches pass straight through to the page.
 */
class Overlay(private val root: FrameLayout) {
    private class Entry(val scrim: View, val content: View, val onDismiss: (() -> Unit)?)

    private val stack = ArrayList<Entry>()
    val isShowing: Boolean get() = stack.isNotEmpty()

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        content: View, anchor: RectF?, below: Boolean = true, onDismiss: (() -> Unit)? = null,
        maxWidthDp: Int = 420, alignRight: Boolean = false, passThrough: ((MotionEvent) -> Boolean)? = null,
    ) {
        val ctx = root.context
        val scrim = object : View(ctx) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                val tt = e.getToolType(0)
                if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) return false
                // Let touches that start on something underneath (like selection handles) through.
                if (e.actionMasked == MotionEvent.ACTION_DOWN && passThrough?.invoke(e) == true) return false
                if (e.actionMasked == MotionEvent.ACTION_UP) dismissTop()
                return true
            }
        }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val maxW = minOf(ctx.dp(maxWidthDp), root.width - ctx.dp(16))
        // Never let a panel collapse to a sliver (a vertical LinearLayout sizes itself only from
        // its fixed-width children when it has any).
        if (content.minimumWidth == 0) content.minimumWidth = minOf(ctx.dp(260), maxW)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(root.height - ctx.dp(32), View.MeasureSpec.AT_MOST)
        )
        val w = content.measuredWidth
        val h = content.measuredHeight
        val lp = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        val m = ctx.dp(8)
        if (anchor == null) {
            lp.leftMargin = (root.width - w) / 2
            lp.topMargin = ((root.height - h) / 2).coerceAtLeast(m)
        } else {
            val x = if (alignRight) anchor.right - w else anchor.centerX() - w / 2f
            lp.leftMargin = x.toInt().coerceIn(m, (root.width - w - m).coerceAtLeast(m))
            var y = if (below) anchor.bottom + m else anchor.top - h - m
            if (below && y + h > root.height - m) y = anchor.top - h - m
            if (!below && y < m) y = anchor.bottom + m
            lp.topMargin = y.toInt().coerceIn(m, (root.height - h - m).coerceAtLeast(m))
        }
        root.addView(content, lp)
        stack += Entry(scrim, content, onDismiss)
    }

    fun dismissTop(notify: Boolean = true) {
        val e = stack.removeLastOrNull() ?: return
        root.removeView(e.content)
        root.removeView(e.scrim)
        if (notify) e.onDismiss?.invoke()
    }

    /** [notify] = false when a popup is being replaced rather than closed by the user. */
    fun dismissAll(notify: Boolean = true) {
        while (stack.isNotEmpty()) dismissTop(notify)
    }
}

fun card(ctx: Context, c: UiColors, padDp: Int = 16): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    background = panelBg(ctx, c)
    setPadding(ctx.dp(padDp), ctx.dp(padDp - 4), ctx.dp(padDp), ctx.dp(padDp))
    elevation = 0f
}
