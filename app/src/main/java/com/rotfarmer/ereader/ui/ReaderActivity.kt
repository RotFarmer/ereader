package com.rotfarmer.ereader.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.rotfarmer.ereader.App
import com.rotfarmer.ereader.data.Bookmark
import com.rotfarmer.ereader.data.Db
import com.rotfarmer.ereader.data.Excerpt
import com.rotfarmer.ereader.data.Highlight
import com.rotfarmer.ereader.data.Ids
import com.rotfarmer.ereader.data.Locator
import com.rotfarmer.ereader.data.Settings
import com.rotfarmer.ereader.data.WritingSpace
import com.rotfarmer.ereader.doc.PdfDoc
import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.Anchoring
import com.rotfarmer.ereader.ink.InkStroke
import com.rotfarmer.ereader.ink.Tools
import com.rotfarmer.ereader.layout.Engine
import com.rotfarmer.ereader.layout.FlowEngine
import com.rotfarmer.ereader.layout.LayoutSpec
import com.rotfarmer.ereader.layout.Page
import com.rotfarmer.ereader.layout.PagePos
import com.rotfarmer.ereader.layout.PdfEngine
import com.rotfarmer.ereader.reader.BookSession
import com.rotfarmer.ereader.reader.InsertTarget
import com.rotfarmer.ereader.reader.ReaderView
import com.rotfarmer.ereader.reader.TextSel
import com.rotfarmer.ereader.render.Fonts
import com.rotfarmer.ereader.render.ReaderColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Warm front-light: multiplies the whole screen by an amber tint. */
class WarmthDrawable : Drawable() {
    var warmth = 0f
        set(v) {
            field = v; invalidateSelf()
        }
    private val paint = Paint().apply { blendMode = BlendMode.MULTIPLY }

    override fun draw(canvas: Canvas) {
        if (warmth < 0.01f) return
        val w = warmth.coerceIn(0f, 1f)
        paint.color = Color.rgb(255, (255 - 70 * w).toInt(), (255 - 140 * w).toInt())
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class ReaderActivity : Activity(), ReaderView.Listener, PenToolbar.Callbacks {

    companion object {
        const val EXTRA_BOOK = "book"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
        const val EXTRA_REGION = "region"

        fun intent(ctx: Context, bookId: Long) = Intent(ctx, ReaderActivity::class.java).putExtra(EXTRA_BOOK, bookId)
    }

    private lateinit var app: App
    private val settings: Settings get() = app.settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: BookSession? = null

    private lateinit var root: FrameLayout
    private lateinit var view: ReaderView
    private lateinit var popups: Overlay
    private val warmth = WarmthDrawable()
    private var ui = UiColors(false)

    private var topBar: LinearLayout? = null
    private var bottomBar: LinearLayout? = null
    private var chromeScrim: View? = null
    private var toolbar: PenToolbar? = null
    private var loading: TextView? = null
    private var backChip: TextButton? = null
    private var bookmarkBtn: IconButton? = null
    private var penBtn: IconButton? = null
    private var pageLabel: TextView? = null
    private var chapterLabel: TextView? = null
    private var seek: SeekBar? = null
    private var chromeVisible = false

    private var engineGen = 0
    private var pendingLoc: Locator? = null
    private var pendingLocate: (() -> Unit)? = null
    private var lastPos: PagePos? = null
    private var pageShownAt = 0L
    private var lastPageChars = 0

    // ============================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        setupWindow()
        root = FrameLayout(this)
        root.foreground = warmth
        view = ReaderView(this)
        view.listener = this
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        popups = Overlay(root)
        loading = label(this, "Opening…", UiColors(settings.dark), 16f, serif = true).apply { gravity = Gravity.CENTER }
        root.addView(loading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyAppearance()

        val id = intent.getLongExtra(EXTRA_BOOK, -1)
        scope.launch { load(id) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        view.clearEngine()
        session?.close()
    }

    // Like an e-ink reader, the screen stays on while you read or write, then sleeps when idle.
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun keepAwake() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeCallbacks(releaseScreen)
        root.postDelayed(releaseScreen, 5 * 60_000L)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) keepAwake()
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !chromeVisible) hideSystemBars()
    }

    private suspend fun load(id: Long) {
        val book = withContext(Db.io) { app.db.book(id) }
        if (book == null) {
            finish(); return
        }
        val s = BookSession(app, book)
        try {
            withContext(Dispatchers.IO) { s.open() }
        } catch (e: CancellationException) {
            s.close()
            throw e
        } catch (e: Throwable) {
            s.close()
            if (isFinishing || isDestroyed) return
            dialog(this, settings.dark).setTitle("Can't open this book")
                .setMessage(e.message ?: "Something went wrong while opening the file.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        if (isDestroyed) {
            s.close(); return
        }
        session = s
        view.doc = s.flow
        view.ann = s.ann
        view.bookId = book.id
        view.pdfCache = s.pdfCache
        view.renderer.images = s.images
        view.renderer.pdf = s.pdfCache
        view.rtl = s.flow?.let { isRtlDoc(it) } ?: false
        s.images?.onLoaded = { view.invalidateContent() }
        s.pdfCache?.onLoaded = { view.invalidateContent() }

        val start = intent.getIntArrayExtra(EXTRA_START)?.let { Locator(it[0], it[1]) }
        val end = intent.getIntArrayExtra(EXTRA_END)?.let { Locator(it[0], it[1]) }
        val region = intent.getFloatArrayExtra(EXTRA_REGION)?.let { RectF(it[0], it[1], it[2], it[3]) }
        pendingLoc = start ?: book.loc
        if (start != null && end != null) {
            pendingLocate = {
                if (region != null) view.showLocateRegion(start.block, region) else view.showLocate(start, end)
            }
        }
        buildChrome()
        rebuildEngine()
        maybeShowGuide()
    }

    private fun isRtlDoc(doc: com.rotfarmer.ereader.doc.FlowDocument): Boolean {
        var rtl = 0
        var total = 0
        for (b in doc.blocks) {
            val t = (b as? com.rotfarmer.ereader.doc.TextBlock)?.text ?: continue
            if (t.length < 20) continue
            total++
            if (android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(t, 0, t.length)) rtl++
            if (total >= 40) break
        }
        return total > 0 && rtl * 10 > total * 6
    }

    // ============================================================ window + appearance

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let {
                it.show(WindowInsets.Type.statusBars())
                val light = if (settings.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                it.setSystemBarsAppearance(light, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (settings.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        }
    }

    private fun statusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            root.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.let { return it.top }
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun cutout(): Rect {
        val c = root.rootWindowInsets?.displayCutout ?: return Rect()
        return Rect(c.safeInsetLeft, c.safeInsetTop, c.safeInsetRight, c.safeInsetBottom)
    }

    /** Applies theme, page effects, brightness and warmth; rebuilds chrome if needed. */
    private fun applyAppearance() {
        ui = UiColors(settings.dark)
        root.setBackgroundColor(ReaderColors(settings.dark).paper)
        view.colors = ReaderColors(settings.dark)
        view.paperTexture = settings.paperTexture
        view.flashEvery = settings.flashEvery
        view.ghosting = settings.ghosting
        view.fingerDraw = settings.fingerDraw
        view.smartHighlight = settings.smartHighlight
        view.renderer.eink = settings.einkImages
        view.inkPainter.sensitivity = settings.pressure
        loading?.setTextColor(ui.fg)
        warmth.warmth = settings.warmth
        applyBrightness()
        applyTool()
    }

    private fun applyBrightness() {
        val lp = window.attributes
        val b = settings.brightness
        lp.screenBrightness = if (b < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else b.pow(2.2f).coerceIn(0.004f, 1f)
        window.attributes = lp
    }

    private fun applyTool() {
        val t = settings.tool
        view.tool = t
        view.toolColor = settings.toolColor(t)
        view.toolWidthDp = settings.toolWidth(t)
        view.eraserWidthDp = settings.toolWidth(Tools.ERASER)
        view.invalidate()
    }

    // ============================================================ layout engine

    private fun makeSpec(w: Int, h: Int, s: BookSession): LayoutSpec {
        val cut = cutout()
        val fontKey = settings.font
        val sizeScale = when (fontKey) {
            "fraktur" -> 1.12f; "gothic" -> 1.06f; else -> 1f
        }
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp() * sizeScale, resources.displayMetrics)
        val content = if (s.pdf != null) {
            RectF((dp(6) + cut.left).toFloat(), max(dp(28), cut.top + dp(6)).toFloat(), (w - dp(6) - cut.right).toFloat(), (h - dp(30)).toFloat())
        } else {
            var m = dp(Settings.MARGINS_DP[settings.margins.coerceIn(0, 2)]).toFloat()
            val maxWidth = px * 40f
            if (w - 2 * m > maxWidth) m = (w - maxWidth) / 2f
            RectF(m + cut.left, max(dp(46), cut.top + dp(30)).toFloat(), w - m - cut.right, (h - dp(42)).toFloat())
        }
        // Keep text and pages clear of the pen toolbar while it's showing.
        if (settings.toolbarVisible) {
            val reserve = dp(66).toFloat()
            if (settings.toolbarRight) content.right = minOf(content.right, w - reserve)
            else content.left = maxOf(content.left, reserve)
        }
        return LayoutSpec(
            width = w, height = h, density = resources.displayMetrics.density,
            typeface = Fonts.get(this, fontKey), textSizePx = px, lineSpacing = settings.lineSpacingMult(),
            content = content, justify = settings.justify, indent = settings.indent,
            textColor = ReaderColors(settings.dark).text,
        )
    }

    private fun currentLoc(): Locator? = view.currentPage()?.start

    private fun rebuildEngine() {
        val s = session ?: return
        val w = view.width
        val h = view.height
        if (w == 0 || h == 0) return
        val loc = pendingLoc ?: currentLoc() ?: Locator(0, 0)
        val spec = makeSpec(w, h, s)
        val pdf = s.pdf
        val engine: Engine = if (pdf != null) PdfEngine(pdf, spec, s.ann.spaces) else {
            val images = s.images
            FlowEngine(s.flow!!, spec, ArrayList(s.ann.spaces)) { p -> images?.size(p) }
        }
        val gen = ++engineGen
        scope.launch {
            val pos = withContext(Dispatchers.Default) { engine.position(loc) }
            if (gen != engineGen || isDestroyed) {
                engine.cancel(); return@launch
            }
            loading?.let { root.removeView(it) }
            loading = null
            pendingLoc = null
            view.setEngine(engine, spec, pos)
            engine.startBackground(pos.ch) {
                if (gen == engineGen) {
                    updateFooter(); updateBottomBar()
                }
            }
            pendingLocate?.invoke()
            pendingLocate = null
        }
    }

    private fun goToLoc(loc: Locator) {
        val e = view.engine ?: return
        view.goTo(e.position(loc), flash = false)
    }

    // ============================================================ ReaderView.Listener

    override fun onSizeChanged(w: Int, h: Int) {
        if (session != null) {
            popups.dismissAll()
            rebuildEngine()
        }
    }

    override fun onPageShown(pos: PagePos) {
        val s = session ?: return
        val e = view.engine ?: return
        val page = e.page(pos.ch, pos.idx)
        keepAwake()
        val now = SystemClock.elapsedRealtime()
        val prev = lastPos
        if (prev != null && prev != pos && e.next(prev) == pos) learnSpeed(now - pageShownAt, lastPageChars)
        lastPos = pos
        pageShownAt = now
        lastPageChars = pageChars(page)
        view.header = if (s.flow != null) s.flow?.sectionTitle(page.start) ?: s.book.title else s.book.title
        updateFooter()
        updateBookmarkState(page)
        updateBottomBar()
        val frac = progress(page)
        val loc = page.start
        App.io.launch { app.db.updateProgress(s.book.id, loc, frac, System.currentTimeMillis()) }
    }

    override fun onToggleChrome() = showChrome(!chromeVisible)

    override fun onToggleBookmark() {
        val s = session ?: return
        val page = view.currentPage() ?: return
        val existing = s.ann.bookmarks.filter { inPage(it.loc, page) }
        if (existing.isNotEmpty()) {
            existing.forEach { s.deleteBookmark(it) }
            toast("Bookmark removed")
        } else {
            s.addBookmark(Bookmark(Ids.next(), s.book.id, page.start, labelFor(page, snippet = true), System.currentTimeMillis()))
            toast("Bookmarked")
        }
        updateBookmarkState(page)
    }

    override fun onFooterTap() {
        settings.footerMode = (settings.footerMode + 1) % 4
        updateFooter()
    }

    override fun onSelection(sel: TextSel, anchor: RectF) {
        popups.dismissAll(notify = false)
        val c = card(this, ui, 10)
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (col in Tools.HIGHLIGHTER_COLORS.take(5)) {
            dots.addView(ColorDot(this, col, ui).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener {
                    addHighlight(sel, col)
                    popups.dismissAll()
                }
            })
        }
        c.addView(dots)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = row.addView(TextButton(this, t, ui).apply {
            setOnClickListener { f(); popups.dismissAll() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        val text = session?.flow?.textBetween(sel.start, sel.end).orEmpty()
        btn("Excerpt") { saveExcerpt(sel.start, sel.end, text) }
        btn("Copy") { copy(text) }
        btn("Share") { share(text) }
        btn("Look up") { lookUp(text) }
        c.addView(row)
        popups.show(c, anchor, below = false, onDismiss = { view.clearSelection() }, passThrough = { e -> view.hitsHandle(e.x, e.y) })
    }

    override fun onSelectionCleared() = popups.dismissAll()

    override fun onHighlightTap(h: Highlight, anchor: RectF) {
        val s = session ?: return
        popups.dismissAll()
        val c = card(this, ui, 10)
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (col in Tools.HIGHLIGHTER_COLORS.take(6)) {
            dots.addView(ColorDot(this, col, ui).apply {
                selected2 = col == h.color
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener {
                    h.color = col
                    s.updateHighlight(h)
                    view.invalidateContent()
                    popups.dismissAll()
                }
            })
        }
        c.addView(dots)
        if (!h.note.isNullOrBlank()) c.addView(label(this, "“${h.note}”", ui, 13f, ui.muted).apply { setPadding(dp(4), dp(2), dp(4), dp(6)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = row.addView(TextButton(this, t, ui).apply {
            setOnClickListener { popups.dismissAll(); f() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        btn("Excerpt") { saveExcerpt(h.start, h.end, h.text) }
        btn("Note") { editNote(h.note) { n -> h.note = n; s.updateHighlight(h) } }
        btn("Copy") { copy(h.text) }
        btn("Delete") {
            s.deleteHighlight(h); view.invalidateContent()
        }
        c.addView(row)
        popups.show(c, anchor, below = false)
    }

    override fun onRegion(page: Int, region: RectF, anchor: RectF) {
        popups.dismissAll(notify = false)
        val c = card(this, ui, 10)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = row.addView(TextButton(this, t, ui).apply {
            setOnClickListener { popups.dismissAll(); f() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        btn("Save excerpt") { savePdfExcerpt(page, RectF(region)) }
        if (PdfDoc.textSupported) btn("Copy text") { copyPdfText(page, RectF(region)) }
        btn("Highlight") { highlightRegion(page, RectF(region)) }
        c.addView(label(this, "Drag the corners to adjust", ui, 12f, ui.muted).apply { setPadding(dp(4), 0, 0, dp(6)) }, 0)
        c.addView(row)
        popups.show(c, anchor, below = true, onDismiss = { view.clearSelection() }, passThrough = { e -> view.hitsHandle(e.x, e.y) })
    }

    override fun onSpaceLongPress(space: WritingSpace, anchor: RectF) {
        val s = session ?: return
        popups.dismissAll()
        val c = card(this, ui, 14)
        c.addView(label(this, "Writing space", ui, 16f, bold = true, serif = true))
        c.addView(sectionTitle(this, "Paper", ui))
        c.addView(Segmented(this, listOf("Blank", "Lined", "Grid", "Dots"), space.paper, ui) {
            space.paper = it
            s.updateSpace(space)
            view.invalidateContent()
        })
        if (space.kind == WritingSpace.FLOW) {
            c.addView(sectionTitle(this, "Height (or drag the bottom edge)", ui))
            val sizes = floatArrayOf(0.25f, 0.5f, 0.75f, 1f)
            val cur = sizes.indices.minByOrNull { kotlin.math.abs(sizes[it] - space.height) } ?: 1
            c.addView(Segmented(this, listOf("¼ page", "½ page", "¾ page", "Full"), cur, ui) {
                s.resizeSpace(space, sizes[it])
                spacesChanged(space.loc.block)
            })
        }
        c.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)))
        c.addView(TextButton(this, "Delete space and its writing", ui).apply {
            setOnClickListener { onDeleteSpace(space) }
        })
        popups.show(c, anchor, below = true)
    }

    override fun onInsertSpace(target: InsertTarget, anchor: RectF) {
        val s = session ?: return
        popups.dismissAll()
        val c = card(this, ui, 14)
        c.addView(label(this, "Add writing space", ui, 16f, bold = true, serif = true).apply { setPadding(0, 0, 0, dp(8)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = row.addView(TextButton(this, t, ui).apply {
            setOnClickListener { popups.dismissAll(); f() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        when (target) {
            is InsertTarget.Flow -> {
                for ((name, h) in listOf("Small" to 0.25f, "Half page" to 0.5f, "Large" to 0.75f, "Full page" to 1f)) {
                    btn(name) {
                        val sp = WritingSpace(Ids.next(), s.book.id, WritingSpace.FLOW, target.loc, h, WritingSpace.PAPER_DOTS, System.currentTimeMillis())
                        s.addSpace(sp)
                        spacesChanged(target.loc.block)
                    }
                }
            }
            is InsertTarget.Pdf -> {
                if (target.allowSplit) btn("Split page here") {
                    val sp = WritingSpace(Ids.next(), s.book.id, WritingSpace.PDF_SPLIT, Locator(target.page, (target.y * 10000).toInt()), 1f, WritingSpace.PAPER_DOTS, System.currentTimeMillis())
                    s.addSpace(sp)
                    spacesChanged(-1)
                }
                btn("Blank page after") {
                    val sp = WritingSpace(Ids.next(), s.book.id, WritingSpace.PDF_BLANK, Locator(target.page, 0), 1f, WritingSpace.PAPER_DOTS, System.currentTimeMillis())
                    s.addSpace(sp)
                    spacesChanged(-1)
                    view.next()
                }
            }
        }
        c.addView(row)
        if (target is InsertTarget.Flow) {
            c.addView(label(this, "Tip: press and drag down instead of tapping to size it by hand. Drag a space's bottom edge to resize it later.", ui, 12f, ui.muted).apply { setPadding(0, dp(8), 0, 0) })
        }
        c.addView(label(this, "Long-press a writing space to change its paper or remove it.", ui, 12f, ui.muted).apply { setPadding(0, dp(4), 0, 0) })
        popups.show(c, anchor, below = true, onDismiss = {
            view.clearInsertMark()
            view.armedInsert = false
            toolbar?.insertArmed = false
        })
    }

    override fun onInsertSpaceSized(loc: Locator, height: Float) {
        val s = session ?: return
        popups.dismissAll()
        val sp = WritingSpace(Ids.next(), s.book.id, WritingSpace.FLOW, loc, height, WritingSpace.PAPER_DOTS, System.currentTimeMillis())
        s.addSpace(sp)
        spacesChanged(loc.block)
    }

    override fun onDeleteSpace(space: WritingSpace) {
        val s = session ?: return
        popups.dismissAll()
        s.deleteSpace(space)
        spacesChanged(if (space.kind == WritingSpace.FLOW) space.loc.block else -1)
        showUndoChip("Writing space deleted")
    }

    private var undoChip: TextButton? = null

    /** A temporary "Undo" button at the bottom, for actions that are easy to do by accident. */
    private fun showUndoChip(what: String) {
        undoChip?.let { root.removeView(it) }
        val chip = TextButton(this, "$what  ·  Undo", ui, filled = true)
        chip.setOnClickListener {
            root.removeView(chip); undoChip = null
            onUndo()
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = dp(56)
        root.addView(chip, lp)
        undoChip = chip
        chip.postDelayed({ if (undoChip === chip) { root.removeView(chip); undoChip = null } }, 7_000)
    }

    override fun onSpaceResized(space: WritingSpace, height: Float) {
        val s = session ?: return
        if (kotlin.math.abs(space.height - height) < 0.005f) return
        s.resizeSpace(space, height)
        spacesChanged(space.loc.block)
    }

    private fun spacesChanged(block: Int) {
        val s = session ?: return
        val e = view.engine ?: return
        val loc = currentLoc() ?: Locator(0, 0)
        e.setSpaces(ArrayList(s.ann.spaces), block)
        view.armedInsert = false
        toolbar?.insertArmed = false
        view.clearInsertMark()
        val pos = e.position(loc)
        view.goTo(pos, flash = false)
        view.invalidateContent()
        onPageShown(view.pos)
        updateUndo()
    }

    override fun onStrokeAdded(s: InkStroke) {
        session?.strokeAdded(s)
        updateUndo()
    }

    override fun onStrokesErased(list: List<InkStroke>, highlights: List<Highlight>) {
        session?.erased(list, highlights)
        updateUndo()
    }

    override fun onSmartHighlight(start: Locator, end: Locator, color: Int) {
        val s = session ?: return
        val page = view.currentPage() ?: return
        val text = s.flow?.textBetween(start, end).orEmpty()
        s.smartHighlight(Highlight(Ids.next(), s.book.id, start, end, color, text, null, labelFor(page), System.currentTimeMillis()))
        view.invalidateContent()
        updateUndo()
    }

    override fun onLink(target: Locator) {
        val back = currentLoc() ?: return
        goToLoc(target)
        showBackChip(back)
    }

    override fun onFontPinch(steps: Int) {
        val n = (settings.fontSize + steps).coerceIn(0, Settings.FONT_SIZES.size - 1)
        if (n != settings.fontSize) {
            settings.fontSize = n
            rebuildEngine()
            toast("Text size ${n + 1}")
        }
    }

    override fun onPenUsed() {
        if (popups.isShowing) popups.dismissAll()
        if (chromeVisible) showChrome(false)
    }

    // ============================================================ PenToolbar.Callbacks

    override fun onToolChanged() = applyTool()

    override fun onToolOptions(anchor: View) {
        popups.dismissAll()
        val tool = settings.tool
        val opts = ToolOptions(this, ui, settings, tool, onChanged = {
            applyTool()
            view.smartHighlight = settings.smartHighlight
            if (view.inkPainter.sensitivity != settings.pressure) {
                view.inkPainter.sensitivity = settings.pressure
                session?.ann?.ink?.all()?.forEach { it.shape = null }
                view.invalidateInk()
            }
            toolbar?.refresh()
        }, onClearPage = if (tool == Tools.ERASER) ({ clearPageInk() }) else null)
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val r = RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + anchor.width).toFloat(), (loc[1] + anchor.height).toFloat())
        val side = if (settings.toolbarRight) RectF(r.left - dp(8), r.top, r.left - dp(8), r.bottom) else RectF(r.right + dp(8), r.top, r.right + dp(8), r.bottom)
        popups.show(opts, side, below = true, maxWidthDp = 320, alignRight = settings.toolbarRight)
    }

    override fun onInsertSpace(armed: Boolean) {
        view.armedInsert = armed
        if (armed) toast("Tap where you want the writing space")
    }

    override fun onUndo() {
        afterUndo(session?.undo() ?: return)
    }

    override fun onRedo() {
        afterUndo(session?.redo() ?: return)
    }

    private fun afterUndo(r: BookSession.UndoResult) {
        val sp = r.space
        if (sp != null) spacesChanged(if (sp.kind == WritingSpace.FLOW) sp.loc.block else -1)
        else view.invalidateContent()
        updateUndo()
    }

    override fun onSideChanged() {
        buildToolbar()
        rebuildEngine()
    }

    override fun onExpandedChanged() {}

    override fun onHide() {
        settings.toolbarVisible = false
        penBtn?.active = false
        if (view.armedInsert) {
            view.armedInsert = false; view.clearInsertMark()
        }
        popups.dismissAll()
        buildToolbar()
        rebuildEngine()
        Toast.makeText(this, "Pen bar hidden. To show it: tap the top of the page, then the pen icon.", Toast.LENGTH_LONG).show()
    }

    private fun updateUndo() {
        val s = session ?: return
        toolbar?.setUndoState(s.canUndo, s.canRedo)
    }

    private fun clearPageInk() {
        val s = session ?: return
        val page = view.currentPage() ?: return
        val spec = view.spec ?: return
        val list = ArrayList<InkStroke>()
        Anchoring.forEachStroke(page, s.ann.ink, spec.content, view.width) { st, _ -> list += st }
        if (list.isEmpty()) {
            toast("No ink on this page"); return
        }
        popups.dismissAll()
        s.clearInk(list)
        view.invalidateInk()
        updateUndo()
        toast("Page ink erased (Undo to restore)")
    }

    // ============================================================ footer, progress, reading speed

    private fun pageChars(page: Page): Int {
        val doc = session?.flow ?: return 1500
        return (doc.charPos(page.end) - doc.charPos(page.start)).coerceAtLeast(1)
    }

    private fun progress(page: Page): Float {
        val s = session ?: return 0f
        val doc = s.flow
        if (doc != null) return (doc.charPos(page.end).toFloat() / doc.totalChars.coerceAtLeast(1)).coerceIn(0f, 1f)
        val e = view.engine ?: return 0f
        val total = e.totalPages().coerceAtLeast(1)
        return ((e.globalIndex(view.pos) + 1).toFloat() / total).coerceIn(0f, 1f)
    }

    private fun learnSpeed(ms: Long, chars: Int) {
        if (ms < 2500 || ms > 10 * 60_000 || chars < 50) return
        val rate = (ms / 1000f) / chars
        val old = settings.secPerChar
        settings.secPerChar = if (old <= 0f) rate else old * 0.85f + rate * 0.15f
        settings.speedSamples = settings.speedSamples + 1
    }

    private fun timeText(chars: Int, what: String): String {
        if (settings.speedSamples < 5 || settings.secPerChar <= 0f) return "Learning reading speed"
        val mins = (chars * settings.secPerChar / 60f).roundToInt()
        return when {
            mins < 1 -> "Less than a minute left in $what"
            mins < 60 -> "$mins min left in $what"
            else -> "${mins / 60} h ${mins % 60} min left in $what"
        }
    }

    private fun updateFooter() {
        val s = session ?: return
        val e = view.engine ?: return
        val page = view.currentPage() ?: return
        val pos = view.pos
        val doc = s.flow
        val total = e.totalPages()
        val g = e.globalIndex(pos)
        view.footerRight = "${(progress(page) * 100).roundToInt()}%"
        view.footerLeft = when (settings.footerMode % 4) {
            0 -> if (total > 0 && g >= 0) "Page ${g + 1} of $total" else "Page ${pos.idx + 1} · Chapter ${pos.ch + 1}"
            1 -> if (doc != null) {
                val (_, chEnd) = doc.chapterChars(pos.ch)
                timeText(chEnd - doc.charPos(page.end), "chapter")
            } else timeText((total - g - 1).coerceAtLeast(0) * 1500, "book")
            2 -> if (doc != null) timeText(doc.totalChars - doc.charPos(page.end), "book")
            else timeText((total - g - 1).coerceAtLeast(0) * 1500, "book")
            else -> if (doc != null) "Location ${doc.charPos(page.start) / 128 + 1} of ${doc.totalChars / 128 + 1}"
            else "Page ${g + 1} of $total"
        }
        view.invalidate()
    }

    private fun inPage(loc: Locator, page: Page) =
        loc >= page.start && (loc < page.end || (page.start == page.end && loc == page.start))

    private fun updateBookmarkState(page: Page) {
        val b = session?.ann?.bookmarks?.any { inPage(it.loc, page) } == true
        view.bookmarked = b
        bookmarkBtn?.text = if (b) Icons.BOOKMARK else Icons.BOOKMARK_BORDER
        view.invalidate()
    }

    private fun labelFor(page: Page, snippet: Boolean = false): String {
        val s = session ?: return ""
        val e = view.engine
        val g = e?.globalIndex(PagePos(page.chapter, page.index)) ?: -1
        val pageNo = if (g >= 0) "p. ${g + 1}" else ""
        val doc = s.flow ?: return "Page ${(g + 1).coerceAtLeast(1)}"
        val section = doc.sectionTitle(page.start).orEmpty()
        if (snippet) {
            val text = doc.textBetween(page.start, Locator(page.start.block, page.start.offset + 140))
                .replace('\n', ' ').take(90)
            return listOf(section, pageNo).filter { it.isNotEmpty() }.joinToString(" · ") + "\n" + text
        }
        return listOf(section, pageNo).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    // ============================================================ chrome (menus)

    private fun buildChrome() {
        val s = session ?: return
        topBar?.let { root.removeView(it) }
        bottomBar?.let { root.removeView(it) }
        chromeScrim?.let { root.removeView(it) }

        val scrim = object : View(this) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                val tt = e.getToolType(0)
                if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) return false
                if (e.actionMasked == MotionEvent.ACTION_UP) showChrome(false)
                return true
            }
        }
        scrim.visibility = View.GONE
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chromeScrim = scrim

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ui.bg)
            setPadding(dp(6), statusBarHeight(), dp(6), 0)
            elevation = 0f
            visibility = View.GONE
            isClickable = true
        }
        top.addView(IconButton(this, Icons.BACK, ui).apply { setOnClickListener { finish() } })
        top.addView(label(this, s.book.title, ui, 17f, serif = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(IconButton(this, Icons.TOC, ui).apply { setOnClickListener { showToc(this) } })
        top.addView(IconButton(this, Icons.STORIES, ui).apply { setOnClickListener { showNotebook(this) } })
        bookmarkBtn = IconButton(this, Icons.BOOKMARK_BORDER, ui).apply { setOnClickListener { onToggleBookmark() } }
        top.addView(bookmarkBtn)
        top.addView(IconButton(this, Icons.TEXT, ui).apply { setOnClickListener { showTextPanel(this) } })
        top.addView(IconButton(this, Icons.BRIGHTNESS, ui).apply { setOnClickListener { showLightPanel(this) } })
        penBtn = IconButton(this, Icons.DRAW, ui).apply {
            active = settings.toolbarVisible
            setOnClickListener {
                settings.toolbarVisible = !settings.toolbarVisible
                active = settings.toolbarVisible
                buildToolbar()
                rebuildEngine()
            }
        }
        top.addView(penBtn)
        top.addView(IconButton(this, Icons.MORE, ui).apply { setOnClickListener { showMore(this) } })
        val topWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(top.apply { visibility = View.VISIBLE }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeight() + dp(56)))
            addView(View(this@ReaderActivity).apply { setBackgroundColor(ui.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        root.addView(topWrap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        topBar = topWrap

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            isClickable = true
        }
        bottom.addView(View(this).apply { setBackgroundColor(ui.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.bg)
            setPadding(dp(16), dp(8), dp(16), dp(14))
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chapterLabel = label(this, "", ui, 13f, ui.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        pageLabel = label(this, "", ui, 13f, ui.fg)
        labels.addView(chapterLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        labels.addView(pageLabel)
        inner.addView(labels)
        seek = slider(this, 1000, 0, ui) { v, done -> onScrub(v, done) }
        inner.addView(seek)
        bottom.addView(inner)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        bottomBar = bottom

        buildToolbar()
        view.currentPage()?.let { updateBookmarkState(it) }
        if (chromeVisible) showChrome(true)
    }

    private fun buildToolbar() {
        toolbar?.let { root.removeView(it) }
        toolbar = null
        if (!settings.toolbarVisible) return
        val tb = PenToolbar(this, ui, settings, this)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or if (settings.toolbarRight) Gravity.END else Gravity.START)
        lp.marginStart = dp(6); lp.marginEnd = dp(6)
        val idx = topBar?.let { root.indexOfChild(chromeScrim) } ?: root.childCount
        root.addView(tb, idx.coerceAtMost(root.childCount), lp)
        tb.insertArmed = view.armedInsert
        toolbar = tb
        updateUndo()
    }

    private fun showChrome(show: Boolean) {
        chromeVisible = show
        val v = if (show) View.VISIBLE else View.GONE
        topBar?.visibility = v
        bottomBar?.visibility = v
        chromeScrim?.visibility = v
        if (show) {
            showSystemBars(); updateBottomBar()
        } else {
            popups.dismissAll(); hideSystemBars()
        }
    }

    private fun updateBottomBar() {
        if (!chromeVisible) return
        val s = session ?: return
        val e = view.engine ?: return
        val page = view.currentPage() ?: return
        val total = e.totalPages()
        val g = e.globalIndex(view.pos)
        chapterLabel?.text = s.flow?.sectionTitle(page.start) ?: s.book.title
        pageLabel?.text = if (total > 0 && g >= 0) "Page ${g + 1} of $total" else "${(progress(page) * 100).roundToInt()}%"
        seek?.let {
            if (total > 0 && g >= 0) {
                it.max = (total - 1).coerceAtLeast(1); it.progress = g
            } else {
                it.max = 1000; it.progress = (progress(page) * 1000).toInt()
            }
        }
    }

    private fun onScrub(v: Int, done: Boolean) {
        val e = view.engine ?: return
        val total = e.totalPages()
        if (!done) {
            pageLabel?.text = if (total > 0) "Page ${v + 1} of $total" else "${v / 10}%"
            return
        }
        if (total > 0) {
            e.posForGlobal(v)?.let { view.goTo(it, flash = false) }
        } else {
            val doc = session?.flow ?: return
            val target = (v / 1000f * doc.blocks.size).toInt().coerceIn(0, doc.blocks.size - 1)
            goToLoc(Locator(target, 0))
        }
    }

    private fun anchorOf(v: View): RectF {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + v.width).toFloat(), (loc[1] + v.height).toFloat())
    }

    private fun scrollable(content: View, maxHeightFraction: Float = 0.72f): ScrollView = object : ScrollView(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxH = (root.height * maxHeightFraction).toInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }.apply { addView(content) }

    // ------------------------------------------------------------ table of contents

    private fun showToc(anchor: View) {
        val s = session ?: return
        val doc = s.flow
        if (doc == null) {
            goToPageDialog(); return
        }
        popups.dismissAll()
        val c = card(this, ui)
        c.addView(label(this, "Contents", ui, 19f, bold = true, serif = true).apply { setPadding(0, 0, 0, dp(8)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val here = currentLoc() ?: Locator(0, 0)
        val entries = doc.toc.ifEmpty {
            doc.chapters.mapIndexed { i, ch -> com.rotfarmer.ereader.doc.TocEntry("Section ${i + 1}", 1, Locator(ch.start, 0)) }
        }
        val current = entries.lastOrNull { it.loc <= here }
        for (e in entries) {
            val row = label(this, e.title, ui, if (e.level <= 1) 16f else 14.5f, if (e == current) ui.fg else if (e.level <= 1) ui.fg else ui.muted, bold = e == current, serif = true)
            row.setPadding(dp(4 + (e.level - 1).coerceIn(0, 4) * 18), dp(10), dp(4), dp(10))
            row.setOnClickListener {
                popups.dismissAll(); showChrome(false); goToLoc(e.loc)
            }
            list.addView(row)
            list.addView(View(this).apply { setBackgroundColor(ui.hairline) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
        c.addView(scrollable(list))
        popups.show(c, anchorOf(anchor), below = true, maxWidthDp = 460)
    }

    private fun goToPageDialog() {
        val e = view.engine ?: return
        val total = e.totalPages()
        if (total <= 0) {
            toast("Still counting pages…"); return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 – $total"
            setText((e.globalIndex(view.pos) + 1).toString())
            selectAll()
        }
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        dialog(this, settings.dark).setTitle("Go to page").setView(wrap)
            .setPositiveButton("Go") { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                e.posForGlobal((n - 1).coerceIn(0, total - 1))?.let { view.goTo(it, flash = false) }
                showChrome(false)
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------ notebook

    private var notebookTab = 0

    private fun showNotebook(anchor: View) {
        val s = session ?: return
        popups.dismissAll()
        val c = card(this, ui)
        c.addView(label(this, "Notebook", ui, 19f, bold = true, serif = true).apply { setPadding(0, 0, 0, dp(8)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = Segmented(
            this,
            listOf("Bookmarks ${s.ann.bookmarks.size}", "Highlights ${s.ann.highlights.size}", "Excerpts ${s.ann.excerpts.size}"),
            notebookTab, ui
        ) { notebookTab = it; fillNotebook(list) }
        c.addView(tabs)
        c.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        fillNotebook(list)
        c.addView(scrollable(list, 0.66f))
        popups.show(c, anchorOf(anchor), below = true, maxWidthDp = 500)
    }

    private fun fillNotebook(list: LinearLayout) {
        val s = session ?: return
        list.removeAllViews()
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        fun empty(t: String) = list.addView(label(this, t, ui, 14f, ui.muted).apply { setPadding(dp(4), dp(16), dp(4), dp(16)) })
        fun sep() = list.addView(View(this).apply { setBackgroundColor(ui.hairline) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        when (notebookTab) {
            0 -> {
                if (s.ann.bookmarks.isEmpty()) empty("No bookmarks yet. Tap the top-right corner of a page to add one.")
                for (b in s.ann.bookmarks.toList()) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
                    val lines = b.label.split('\n')
                    row.addView(label(this, lines.first().ifBlank { "Bookmark" }, ui, 15f, bold = true))
                    if (lines.size > 1) row.addView(label(this, lines[1], ui, 14f, ui.muted, serif = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    row.addView(label(this, df.format(Date(b.created)), ui, 11.5f, ui.muted))
                    row.setOnClickListener { popups.dismissAll(); showChrome(false); goToLoc(b.loc) }
                    row.setOnLongClickListener {
                        confirm("Remove this bookmark?") {
                            s.deleteBookmark(b); fillNotebook(list); view.currentPage()?.let { p -> updateBookmarkState(p) }
                        }; true
                    }
                    list.addView(row); sep()
                }
            }
            1 -> {
                if (s.ann.highlights.isEmpty()) empty("No highlights yet. Use the S Pen highlighter, or long-press text with your finger.")
                for (h in s.ann.highlights.sortedBy { it.start }) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
                    row.addView(View(this).apply { setBackgroundColor(h.color) }, LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(10) })
                    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(label(this, h.text, ui, 15f, serif = true).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END })
                    if (!h.note.isNullOrBlank()) col.addView(label(this, "Note: ${h.note}", ui, 13f, ui.fg).apply { setPadding(0, dp(4), 0, 0) })
                    col.addView(label(this, h.label, ui, 11.5f, ui.muted).apply { setPadding(0, dp(4), 0, 0) })
                    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.setOnClickListener {
                        popups.dismissAll(); showChrome(false); goToLoc(h.start); view.showLocate(h.start, h.end)
                    }
                    row.setOnLongClickListener {
                        menu(listOf("Save as excerpt", "Copy", "Delete")) { i ->
                            when (i) {
                                0 -> saveExcerpt(h.start, h.end, h.text)
                                1 -> copy(h.text)
                                2 -> {
                                    s.deleteHighlight(h); view.invalidateContent(); fillNotebook(list)
                                }
                            }
                        }; true
                    }
                    list.addView(row); sep()
                }
            }
            else -> {
                if (s.ann.excerpts.isEmpty()) {
                    empty("No excerpts yet. Long-press text (or a region of a PDF) and choose Excerpt.")
                } else {
                    list.addView(TextButton(this, "Share all excerpts", ui).apply {
                        setOnClickListener { share(s.ann.excerpts.joinToString("\n\n") { excerptText(it) }) }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
                }
                for (x in s.ann.excerpts.toList()) {
                    list.addView(excerptRow(x, list)); sep()
                }
            }
        }
    }

    private fun excerptRow(x: Excerpt, list: LinearLayout): View {
        val s = session!!
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
        x.imagePath?.let { p ->
            BitmapFactory.decodeFile(p)?.let { bmp ->
                row.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_START
                    colorFilter = ReaderColors(settings.dark).pdfFilter
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            }
        }
        if (x.text.isNotBlank()) row.addView(label(this, "“${x.text}”", ui, 15f, serif = true).apply { maxLines = 6; ellipsize = TextUtils.TruncateAt.END })
        if (!x.note.isNullOrBlank()) row.addView(label(this, "Note: ${x.note}", ui, 13f).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(label(this, x.label.ifBlank { "Excerpt" }, ui, 11.5f, ui.muted).apply { setPadding(0, dp(4), 0, 0) })
        row.setOnClickListener {
            popups.dismissAll(); showChrome(false)
            goToLoc(x.start)
            val r = x.region
            if (r != null) view.showLocateRegion(x.start.block, r) else view.showLocate(x.start, x.end)
        }
        row.setOnLongClickListener {
            menu(listOf("Edit note", "Copy", "Share", "Delete")) { i ->
                when (i) {
                    0 -> editNote(x.note) { n -> x.note = n; s.updateExcerpt(x); fillNotebook(list) }
                    1 -> copy(excerptText(x))
                    2 -> share(excerptText(x))
                    3 -> confirm("Delete this excerpt?") { s.deleteExcerpt(x); fillNotebook(list) }
                }
            }; true
        }
        return row
    }

    private fun excerptText(x: Excerpt): String {
        val title = session?.book?.title.orEmpty()
        val body = if (x.text.isNotBlank()) "“${x.text}”" else "(picture excerpt)"
        val note = if (!x.note.isNullOrBlank()) "\nNote: ${x.note}" else ""
        return "$body$note\n— $title${if (x.label.isNotBlank()) ", ${x.label}" else ""}"
    }

    // ------------------------------------------------------------ text & e-ink settings

    private fun showTextPanel(anchor: View) {
        val s = session ?: return
        popups.dismissAll()
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (s.flow != null) {
            c.addView(sectionTitle(this, "Font", ui))
            val fams = Fonts.families
            for (rowStart in fams.indices step 3) {
                val chunk = fams.subList(rowStart, minOf(rowStart + 3, fams.size))
                val seg = Segmented(this, chunk.map { it.label }, chunk.indexOfFirst { it.key == settings.font }, ui) { i ->
                    settings.font = chunk[i].key
                    showTextPanelAgain(anchor)
                    rebuildEngine()
                }
                seg.setTypefaces(chunk.map { Fonts.get(this, it.key) })
                c.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
            }
            c.addView(sectionTitle(this, "Size", ui))
            val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val sizeLabel = label(this, "${settings.fontSize + 1}", ui, 16f, bold = true).apply { gravity = Gravity.CENTER }
            sizeRow.addView(TextButton(this, "A−", ui).apply {
                setOnClickListener {
                    if (settings.fontSize > 0) {
                        settings.fontSize--; sizeLabel.text = "${settings.fontSize + 1}"; rebuildEngine()
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sizeRow.addView(sizeLabel, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            sizeRow.addView(TextButton(this, "A+", ui).apply {
                textSize = 17f
                setOnClickListener {
                    if (settings.fontSize < Settings.FONT_SIZES.size - 1) {
                        settings.fontSize++; sizeLabel.text = "${settings.fontSize + 1}"; rebuildEngine()
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(sizeRow)
            c.addView(sectionTitle(this, "Line spacing", ui))
            c.addView(Segmented(this, listOf("Tight", "Normal", "Loose"), settings.lineSpacing, ui) { settings.lineSpacing = it; rebuildEngine() })
            c.addView(sectionTitle(this, "Margins", ui))
            c.addView(Segmented(this, listOf("Narrow", "Normal", "Wide"), settings.margins, ui) { settings.margins = it; rebuildEngine() })
            c.addView(divider(this, ui))
            c.addView(toggleRow(this, "Justify text", null, settings.justify, ui) { settings.justify = it; rebuildEngine() })
            c.addView(toggleRow(this, "Indent paragraphs", "Book-style first-line indents", settings.indent, ui) { settings.indent = it; rebuildEngine() })
        }
        c.addView(sectionTitle(this, "E-ink effects", ui))
        c.addView(toggleRow(this, "Paper grain", "Subtle texture of an e-ink screen", settings.paperTexture, ui) {
            settings.paperTexture = it; applyAppearance()
        })
        c.addView(label(this, "Page refresh flash", ui, 15f).apply { setPadding(0, dp(8), 0, dp(6)) })
        val flashOpts = intArrayOf(0, 1, 6)
        c.addView(Segmented(this, listOf("Off", "Every page", "Every 6 pages"), flashOpts.indexOf(settings.flashEvery).coerceAtLeast(0), ui) {
            settings.flashEvery = flashOpts[it]; applyAppearance()
        })
        c.addView(toggleRow(this, "Ghosting", "Faint trace of the previous page, cleared by a refresh", settings.ghosting, ui) {
            settings.ghosting = it; applyAppearance()
        })
        c.addView(toggleRow(this, "E-ink pictures", "Show images${if (s.pdf != null) " and PDF pages" else ""} in 16 grays", settings.einkImages, ui) {
            settings.einkImages = it; applyAppearance(); view.invalidateContent()
        })
        val card = card(this, ui)
        card.addView(label(this, "Display", ui, 19f, bold = true, serif = true))
        card.addView(scrollable(c, 0.75f))
        popups.show(card, anchorOf(anchor), below = true, maxWidthDp = 400, alignRight = true)
    }

    private fun showTextPanelAgain(anchor: View) {
        popups.dismissAll()
        anchor.post { showTextPanel(anchor) }
    }

    // ------------------------------------------------------------ light

    private fun showLightPanel(anchor: View) {
        popups.dismissAll()
        val c = card(this, ui)
        c.addView(label(this, "Light", ui, 19f, bold = true, serif = true))
        c.addView(sectionTitle(this, "Theme", ui))
        c.addView(Segmented(this, listOf("Light", "Dark"), if (settings.dark) 1 else 0, ui) {
            val dark = it == 1
            if (dark != settings.dark) {
                settings.dark = dark
                popups.dismissAll()
                applyAppearance()
                buildChrome()
                rebuildEngine()
            }
        })
        c.addView(sectionTitle(this, "Brightness", ui))
        val bright = slider(this, 100, ((if (settings.brightness < 0) 0.6f else settings.brightness) * 100).toInt(), ui) { v, _ ->
            settings.brightness = (v / 100f).coerceAtLeast(0.01f)
            applyBrightness()
        }
        c.addView(bright)
        c.addView(toggleRow(this, "Use system brightness", null, settings.brightness < 0, ui) { on ->
            settings.brightness = if (on) -1f else bright.progress / 100f
            applyBrightness()
        })
        c.addView(sectionTitle(this, "Warmth", ui))
        c.addView(slider(this, 100, (settings.warmth * 100).toInt(), ui) { v, _ ->
            settings.warmth = v / 100f
            warmth.warmth = settings.warmth
        })
        popups.show(c, anchorOf(anchor), below = true, maxWidthDp = 360, alignRight = true)
    }

    // ------------------------------------------------------------ more

    private fun showMore(anchor: View) {
        popups.dismissAll()
        val c = card(this, ui, 8)
        fun item(t: String, f: () -> Unit) = c.addView(label(this, t, ui, 15.5f).apply {
            setPadding(dp(12), dp(12), dp(24), dp(12))
            setOnClickListener { popups.dismissAll(); f() }
        })
        item("Go to page…") { goToPageDialog() }
        item("Pen & touch settings") { showInputSettings() }
        item("Gesture guide") { showGuide() }
        item("Book details") { showDetails() }
        popups.show(c, anchorOf(anchor), below = true, maxWidthDp = 280, alignRight = true)
    }

    private fun showInputSettings() {
        val c = card(this, ui)
        c.addView(label(this, "Pen & touch", ui, 19f, bold = true, serif = true))
        c.addView(toggleRow(this, "Draw with finger", "For devices without a stylus. Two-finger swipe turns pages.", settings.fingerDraw, ui) {
            settings.fingerDraw = it; applyAppearance()
        })
        c.addView(toggleRow(this, "Volume keys turn pages", null, settings.volumeKeys, ui) { settings.volumeKeys = it })
        c.addView(toggleRow(this, "Highlighter snaps to text", null, settings.smartHighlight, ui) {
            settings.smartHighlight = it; applyAppearance()
        })
        c.addView(sectionTitle(this, "S Pen pressure sensitivity", ui))
        c.addView(slider(this, 100, (settings.pressure * 100).toInt(), ui) { v, done ->
            settings.pressure = v / 100f
            if (done) {
                view.inkPainter.sensitivity = settings.pressure
                session?.ann?.ink?.all()?.forEach { it.shape = null }
                view.invalidateInk()
                toolbar?.refresh()
            }
        })
        c.addView(label(this, "The S Pen always writes and draws; your finger turns pages, selects text and opens menus. Hold the S Pen button to erase.", ui, 12.5f, ui.muted).apply { setPadding(0, dp(8), 0, 0) })
        popups.show(c, null, maxWidthDp = 420)
    }

    private fun showDetails() {
        val s = session ?: return
        val e = view.engine
        val total = e?.totalPages() ?: -1
        val msg = buildString {
            append(s.book.title)
            if (s.book.author.isNotBlank()) append("\nby ${s.book.author}")
            append("\n\nFormat: ${s.book.format.label}")
            if (total > 0) append("\nPages: $total (at the current text size)")
            append("\nBookmarks: ${s.ann.bookmarks.size}")
            append("\nHighlights: ${s.ann.highlights.size}")
            append("\nExcerpts: ${s.ann.excerpts.size}")
            append("\nPen strokes: ${s.ann.ink.all().size}")
            append("\nWriting spaces: ${s.ann.spaces.size}")
        }
        dialog(this, settings.dark).setTitle("Book details").setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------ guide

    private fun maybeShowGuide() {
        if (!settings.prefs.getBoolean("guideShown", false)) {
            settings.prefs.edit().putBoolean("guideShown", true).apply()
            root.post { showGuide() }
        }
    }

    private fun showGuide() {
        val c = card(this, ui, 20)
        c.addView(label(this, "How to read", ui, 20f, bold = true, serif = true).apply { setPadding(0, 0, 0, dp(10)) })
        val lines = listOf(
            "Tap the right side or swipe left" to "next page",
            "Tap the left side or swipe right" to "previous page",
            "Tap the top of the page" to "menu",
            "Tap the top-right corner" to "bookmark",
            "Long-press text with your finger" to "highlight, excerpt, copy",
            "S Pen" to "write, draw and highlight — it never turns pages",
            "Hold the S Pen button" to "erase",
            "Pen toolbar ⇕ button" to "insert space to write in",
            "Tap the page number" to "time left, location",
        )
        for ((a, b) in lines) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
            row.addView(label(this, a, ui, 15f, bold = true))
            row.addView(label(this, b, ui, 14f, ui.muted))
            c.addView(row)
        }
        c.addView(TextButton(this, "Got it", ui, filled = true).apply {
            setOnClickListener { popups.dismissAll() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        popups.show(scrollable(c, 0.9f), null, maxWidthDp = 420)
    }

    // ============================================================ actions

    private fun addHighlight(sel: TextSel, color: Int) {
        val s = session ?: return
        val page = view.currentPage() ?: return
        val text = s.flow?.textBetween(sel.start, sel.end).orEmpty()
        s.addHighlight(Highlight(Ids.next(), s.book.id, sel.start, sel.end, color, text, null, labelFor(page), System.currentTimeMillis()))
        view.clearSelection()
        view.invalidateContent()
    }

    private fun saveExcerpt(start: Locator, end: Locator, text: String) {
        val s = session ?: return
        val page = view.currentPage() ?: return
        if (text.isBlank()) return
        s.addExcerpt(Excerpt(Ids.next(), s.book.id, start, end, text, null, null, null, labelFor(page), System.currentTimeMillis()))
        view.clearSelection()
        toast("Saved to excerpts")
    }

    private fun savePdfExcerpt(page: Int, region: RectF) {
        val s = session ?: return
        val pdf = s.pdf ?: return
        val id = Ids.next()
        val label = "Page ${page + 1}"
        view.clearSelection()
        scope.launch {
            val (path, text) = withContext(Dispatchers.IO) {
                val f = File(app.excerptsDir, "$id.png")
                try {
                    val bmp = pdf.renderRegion(page, region, 1100)
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                } catch (_: Throwable) {
                }
                (if (f.exists()) f.absolutePath else null) to (pdf.textIn(page, region) ?: "")
            }
            s.addExcerpt(
                Excerpt(id, s.book.id, Locator(page, (region.top * 10000).toInt()), Locator(page, (region.bottom * 10000).toInt()),
                    text, null, path, RectF(region), label, System.currentTimeMillis())
            )
            toast("Saved to excerpts")
        }
    }

    private fun copyPdfText(page: Int, region: RectF) {
        val pdf = session?.pdf ?: return
        view.clearSelection()
        scope.launch {
            val t = withContext(Dispatchers.IO) { pdf.textIn(page, region) }
            if (t.isNullOrBlank()) toast("No text found there") else copy(t)
        }
    }

    private fun highlightRegion(page: Int, region: RectF) {
        val s = session ?: return
        val pdf = s.pdf ?: return
        val size = pdf.pageSize(page)
        val aspect = size.height / size.width
        // A flat highlighter stroke across the region's middle, as tall as the region.
        val cy = (region.top + region.bottom) / 2 * aspect
        val h = (region.bottom - region.top) * aspect
        val pts = floatArrayOf(region.left, cy, 0.6f, region.right, cy, 0.6f)
        val color = settings.toolColor(Tools.HIGHLIGHTER)
        val st = InkStroke(Ids.next(), s.book.id, Anchor(Anchor.PDF, page.toLong(), 0), Tools.HIGHLIGHTER, color, h, pts, System.currentTimeMillis())
        s.ann.ink.add(st)
        s.strokeAdded(st)
        view.clearSelection()
        view.invalidateInk()
        updateUndo()
    }

    private fun showBackChip(back: Locator) {
        backChip?.let { root.removeView(it) }
        val chip = TextButton(this, "↩  Back to where you were", ui, filled = true)
        chip.setOnClickListener {
            goToLoc(back); root.removeView(chip); backChip = null
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = dp(56)
        root.addView(chip, lp)
        backChip = chip
        chip.postDelayed({ if (backChip === chip) { root.removeView(chip); backChip = null } }, 10_000)
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Excerpt", text))
        if (Build.VERSION.SDK_INT < 33) toast("Copied")
    }

    private fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(i, "Share"))
    }

    private fun lookUp(text: String) {
        val word = text.trim()
        val processText = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, word).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        try {
            if (packageManager.queryIntentActivities(processText, 0).isNotEmpty()) {
                startActivity(Intent.createChooser(processText, "Look up")); return
            }
        } catch (_: Exception) {
        }
        try {
            startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", "define $word"))
        } catch (_: Exception) {
            toast("No dictionary app found")
        }
    }

    private fun editNote(current: String?, save: (String?) -> Unit) {
        val input = EditText(this).apply {
            setText(current.orEmpty())
            minLines = 3
            gravity = Gravity.TOP
        }
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        dialog(this, settings.dark).setTitle("Note").setView(wrap)
            .setPositiveButton("Save") { _, _ -> save(input.text.toString().trim().ifEmpty { null }) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirm(msg: String, yes: () -> Unit) {
        dialog(this, settings.dark).setMessage(msg).setPositiveButton("OK") { _, _ -> yes() }.setNegativeButton("Cancel", null).show()
    }

    private fun menu(items: List<String>, pick: (Int) -> Unit) {
        dialog(this, settings.dark).setItems(items.toTypedArray()) { _, i -> pick(i) }.show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ============================================================ keys + back

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (settings.volumeKeys && session != null) {
                view.next(); return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> if (settings.volumeKeys && session != null) {
                view.prev(); return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SPACE -> {
                if (view.rtl && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) view.prev() else view.next(); return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (view.rtl && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) view.next() else view.prev(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && settings.volumeKeys) return true
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            popups.isShowing -> popups.dismissTop()
            view.selection != null -> view.clearSelection()
            chromeVisible -> showChrome(false)
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }
}
