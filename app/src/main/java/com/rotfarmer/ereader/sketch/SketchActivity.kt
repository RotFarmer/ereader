package com.rotfarmer.ereader.sketch

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.rotfarmer.ereader.App
import com.rotfarmer.ereader.ink.Tools
import com.rotfarmer.ereader.render.Fonts
import com.rotfarmer.ereader.ui.IconButton
import com.rotfarmer.ereader.ui.Icons
import com.rotfarmer.ereader.ui.Overlay
import com.rotfarmer.ereader.ui.ReaderActivity
import com.rotfarmer.ereader.ui.Segmented
import com.rotfarmer.ereader.ui.TextButton
import com.rotfarmer.ereader.ui.ToolOptions
import com.rotfarmer.ereader.ui.UiColors
import com.rotfarmer.ereader.ui.card
import com.rotfarmer.ereader.ui.dialog
import com.rotfarmer.ereader.ui.divider
import com.rotfarmer.ereader.ui.dp
import com.rotfarmer.ereader.ui.label
import com.rotfarmer.ereader.ui.panelBg
import com.rotfarmer.ereader.ui.sectionTitle
import com.rotfarmer.ereader.ui.slider
import com.rotfarmer.ereader.ui.toggleRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A sketchbook: the page in the middle, the tools down one side and the sketchbook's controls
 * along the top, in the same style as the reader. Popups float over the page like the reader's.
 */
class SketchActivity : Activity() {
    private val app by lazy { App.of(this) }
    internal val settings get() = app.settings
    internal val colors get() = UiColors(settings.dark)
    internal val store by lazy { SketchStore.of(this) }
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal var bookId = -1L; private set
    internal var book: Sketchbook? = null; private set
    internal var info: PageInfo? = null; private set
    internal var page = SketchPage(0, emptyList(), emptyList()); private set
    private lateinit var writer: SketchWriter

    private lateinit var root: FrameLayout
    private lateinit var header: View
    private lateinit var workspace: LinearLayout
    private lateinit var frame: FrameLayout
    internal lateinit var canvas: SketchCanvas; private set
    internal lateinit var popups: Overlay; private set
    private var rail: SketchToolRail? = null
    private var railLine: View? = null
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var pageChip: TextView
    private var undoBtn: IconButton? = null
    private var redoBtn: IconButton? = null
    private var contextBar: LinearLayout? = null
    private var exitFocus: View? = null
    private var dock: LinearLayout? = null
    private var dockKey = ""
    private var dockRefId = -1L
    private var historyGeneration = 0
    private var canUndo = false
    private var canRedo = false
    private var focus = false
    private var loading = false
    private var exportKind = "pdf"
    private var exportIds = emptyList<Long>()
    private var exportDark = false
    private var pendingImport: SketchImportSession? = null
    /** True until the excerpt this window was opened with has been offered for import. */
    private var initial = true
    private val writerListener: (String) -> Unit = { state -> showStatus(state); if (!writer.busy) refreshHistory() }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getLongExtra(EXTRA_BOOK, -1)
        savedInstanceState?.let { st ->
            initial = st.getBoolean("initial", true); focus = st.getBoolean("focus", false)
            exportKind = st.getString("exportKind") ?: "pdf"
            exportIds = st.getLongArray("exportIds")?.toList().orEmpty()
            exportDark = st.getBoolean("exportDark", false)
        }
        // A tool id the sketchbook doesn't know would make the pen seem to do nothing.
        if (settings.tool !in Tools.DRAWING && settings.tool != Tools.ERASER) settings.tool = Tools.PEN
        writer = SketchWriter.get(this, bookId)
        setupWindow()
        build()
        writer.addListener(writerListener)
        showStatus(writer.state)
        io({
            val b = store.get(bookId) ?: error("This sketchbook is no longer in the library")
            Triple(b, store.info(bookId, b.currentPage), store.page(bookId, b.currentPage))
        }, failed = { finish() }) {
            book = it.first; show(it.second, it.third)
            if (initial) { initial = false; receiveExcerpt() }
        }
    }

    /** Full screen like the reader: the system bars slide in with a swipe from the edge. */
    @Suppress("DEPRECATION")
    private fun setupWindow() {
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("initial", initial); out.putBoolean("focus", focus)
        out.putString("exportKind", exportKind); out.putLongArray("exportIds", exportIds.toLongArray())
        out.putBoolean("exportDark", exportDark)
    }

    /** Another window (the reader, or this sketchbook opened again) may have changed the page meanwhile. */
    override fun onRestart() { super.onRestart(); refreshFromStore() }

    override fun onDestroy() {
        writer.removeListener(writerListener)
        scope.cancel()
        if (::canvas.isInitialized) canvas.dispose()
        pendingImport?.close()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        build(); refreshHistory()
    }

    @Deprecated("Activity back callback")
    override fun onBackPressed() {
        if (::popups.isInitialized && popups.isShowing) { popups.dismissTop(); return }
        if (focus) { focus = false; applyFocus(); return }
        if (writer.error != null) { toast("Your last change couldn't be saved yet. It will keep retrying."); finish(); return }
        writer.afterSaved { if (!isDestroyed) finish() }
    }

    // ------------------------------------------------------------------ layout

    private fun build() {
        if (::popups.isInitialized) popups.dismissAll(false)
        dock = null; dockKey = ""
        val ui = colors
        root = FrameLayout(this).apply { setBackgroundColor(ui.bg) }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Only keep clear of a camera cutout; hidden system bars slide in over the page.
            setOnApplyWindowInsetsListener { v, ins ->
                val cut = ins.getInsets(WindowInsets.Type.displayCutout()); v.setPadding(cut.left, cut.top, cut.right, cut.bottom); ins
            }
        }
        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar(ui), LinearLayout.LayoutParams(-1, dp(56)))
            addView(View(this@SketchActivity).apply { setBackgroundColor(ui.line) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        main.addView(header, LinearLayout.LayoutParams(-1, -2))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        main.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        workspace = LinearLayout(this).apply {
            orientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        frame = FrameLayout(this)
        // The canvas lives as long as the window: rotation keeps its tool, selection and clipboard.
        if (!::canvas.isInitialized) {
            canvas = SketchCanvas(this, settings, store.referencesDir, { before, after -> edit(before, after) }, { z, x, y, r ->
                info = info?.copy(zoom = z, panX = x, panY = y, rotation = r); info?.let { writer.viewport(it) }
            })
            canvas.partialEraser = settings.prefs.getBoolean("sketchPartialEraser", false)
            canvas.onSwipe = { step -> turn(step) }
            canvas.onSelectionChanged = { updateContextBar() }
        } else (canvas.parent as? ViewGroup)?.removeView(canvas)
        frame.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        contextBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = panelBg(this@SketchActivity, ui, 10f)
            setPadding(dp(12), dp(4), dp(4), dp(4)); elevation = dp(2).toFloat(); visibility = View.GONE
        }
        frame.addView(contextBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
        exitFocus = IconButton(this, ICON_EXIT_FOCUS, ui).apply {
            contentDescription = "Show the toolbars"
            background = panelBg(this@SketchActivity, ui, 10f)
            setOnClickListener { focus = false; applyFocus() }
        }
        frame.addView(exitFocus, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply { topMargin = dp(10); marginEnd = dp(10) })
        workspace.addView(frame, if (workspace.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))

        val railView = SketchToolRail(this, ui); rail = railView
        val line = View(this).apply { setBackgroundColor(ui.hairline) }; railLine = line
        if (settings.toolbarRight) {
            body.addView(workspace, LinearLayout.LayoutParams(0, -1, 1f))
            body.addView(line, LinearLayout.LayoutParams(dp(1), -1))
            body.addView(railView, LinearLayout.LayoutParams(dp(62), -1))
        } else {
            body.addView(railView, LinearLayout.LayoutParams(dp(62), -1))
            body.addView(line, LinearLayout.LayoutParams(dp(1), -1))
            body.addView(workspace, LinearLayout.LayoutParams(0, -1, 1f))
        }
        setContentView(root)
        popups = Overlay(root)
        updateLabels(); showStatus(writer.state); updateHistoryButtons(); refreshDock(); updateContextBar(); applyFocus()
    }

    private fun topBar(ui: UiColors) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(ui.bg); setPadding(dp(6), 0, dp(6), 0)
        addView(IconButton(context, Icons.BACK, ui).apply { contentDescription = "Back to library"; setOnClickListener { onBackPressed() } })
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        title = label(context, book?.title ?: "Sketchbook", ui, 17f, serif = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        status = label(context, "", ui, 12f, ui.muted).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { if (writer.error != null) writer.retry() }
        }
        titles.addView(title); titles.addView(status)
        addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        undoBtn = IconButton(context, Icons.UNDO, ui).apply { contentDescription = "Undo"; setOnClickListener { history(false) } }
        redoBtn = IconButton(context, Icons.REDO, ui).apply { contentDescription = "Redo"; setOnClickListener { history(true) } }
        addView(undoBtn); addView(redoBtn)
        addView(separator(ui))
        val wide = resources.configuration.screenWidthDp >= 600
        if (wide) addView(IconButton(context, Icons.CHEV_LEFT, ui).apply { contentDescription = "Previous page"; setOnClickListener { turn(-1) } })
        pageChip = label(context, "", ui, 14f, bold = true).apply {
            gravity = Gravity.CENTER; minWidth = dp(76)
            background = panelBg(context, ui, 18f, 1f, ui.soft)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            contentDescription = "All pages"
            setOnClickListener { browse() }
        }
        addView(pageChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(2); marginEnd = dp(2) })
        if (wide) addView(IconButton(context, Icons.CHEV_RIGHT, ui).apply { contentDescription = "Next page"; setOnClickListener { turn(1) } })
        addView(separator(ui))
        addView(IconButton(context, ICON_ADD_PICTURE, ui).apply { contentDescription = "Import a picture or document to trace"; setOnClickListener { pickReference() } })
        addView(IconButton(context, ICON_LAYERS, ui).apply { contentDescription = "References on this page"; setOnClickListener { showReferences(this) } })
        addView(IconButton(context, Icons.MORE, ui).apply { contentDescription = "Sketchbook menu"; setOnClickListener { showMenu(this) } })
    }

    private fun separator(ui: UiColors) = View(this).apply {
        setBackgroundColor(ui.hairline)
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(26)).apply { marginStart = dp(6); marginEnd = dp(6) }
    }

    private fun applyFocus() {
        val v = if (focus) View.GONE else View.VISIBLE
        header.visibility = v; rail?.visibility = v; railLine?.visibility = v
        exitFocus?.visibility = if (focus) View.VISIBLE else View.GONE
    }

    private fun updateLabels() {
        title.text = book?.title ?: "Sketchbook"
        pageChip.text = info?.let { "${it.index + 1} / ${book?.pageCount ?: 1}" } ?: "…"
    }

    private fun showStatus(state: String) {
        val p = info
        val where = listOfNotNull(p?.section?.takeIf { it.isNotBlank() }, p?.title?.takeIf { it.isNotBlank() }).joinToString(" · ")
        status.text = if (where.isEmpty()) state else "$where · $state"
        status.setTextColor(if (writer.error != null) 0xFFC0392B.toInt() else colors.muted)
    }

    private fun updateHistoryButtons() { undoBtn?.isEnabled = canUndo; redoBtn?.isEnabled = canRedo }

    /** The small floating bar over the page for lasso selections, shapes and arranging a reference. */
    private fun updateContextBar() {
        val bar = contextBar ?: return
        bar.removeAllViews()
        val ui = colors
        fun text(t: String) = bar.addView(label(this, t, ui, 13.5f).apply { setPadding(0, 0, dp(10), 0); maxLines = 1 })
        fun action(t: String, f: () -> Unit) = bar.addView(TextButton(this, t, ui).apply { minHeight = dp(36); setOnClickListener { f() } },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        fun close(f: () -> Unit) = bar.addView(IconButton(this, Icons.CLOSE, ui, 38).apply { contentDescription = "Done"; setOnClickListener { f() } })
        val mode = canvas.mode
        when {
            canvas.referenceId != null -> {
                text("Drag the reference · pinch to resize and turn")
                action("Done") { finishArranging() }
            }
            mode == SketchCanvas.LASSO -> {
                val n = canvas.selectionCount
                text(if (n == 0) "Circle strokes with the S Pen" else "$n selected · drag to move")
                if (n > 0) {
                    action("Copy") { canvas.copySelection(); toast("Copied"); updateContextBar() }
                    action("Duplicate") { canvas.duplicateSelection() }
                    action("Delete") { canvas.deleteSelection() }
                }
                if (canvas.canPaste) action("Paste") { canvas.paste() }
                close { setMode(SketchCanvas.DRAW) }
            }
            mode in SketchCanvas.SHAPES -> {
                text("${shapeName(mode)} · ${Tools.name(settings.tool)}")
                close { setMode(SketchCanvas.DRAW) }
            }
            else -> { bar.visibility = View.GONE; return }
        }
        bar.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ tools

    internal fun selectTool(t: Int, anchor: View) {
        val drawing = canvas.mode == SketchCanvas.DRAW || canvas.mode in SketchCanvas.SHAPES
        if (t == settings.tool && drawing) { showPenOptions(anchor); return }
        settings.tool = t
        if (canvas.mode == SketchCanvas.LASSO || (t == Tools.ERASER && canvas.mode in SketchCanvas.SHAPES)) setMode(SketchCanvas.DRAW)
        else { rail?.refresh(); updateContextBar(); canvas.invalidate() }
    }

    internal fun setMode(mode: String) {
        if (mode in SketchCanvas.SHAPES && settings.tool == Tools.ERASER) settings.tool = Tools.PEN
        canvas.mode = mode
        rail?.refresh(); updateContextBar()
    }

    internal fun showShapes(anchor: View) {
        popups.dismissAll()
        val c = card(this, colors, 8)
        for (m in SketchCanvas.SHAPES) c.addView(menuRow(SketchCanvas.shapeIcon(m), shapeName(m), canvas.mode == m) { setMode(m) })
        if (canvas.mode in SketchCanvas.SHAPES) c.addView(menuRow(Icons.DRAW, "Freehand") { setMode(SketchCanvas.DRAW) })
        popups.show(c, beside(anchor), below = true, maxWidthDp = 260, alignRight = settings.toolbarRight, alignLeft = !settings.toolbarRight)
    }

    internal fun showPenOptions(anchor: View) {
        popups.dismissAll()
        val tool = settings.tool
        val opts = ToolOptions(this, colors, settings, tool, onChanged = {
            page.strokes.forEach { it.shape = null }
            rail?.refresh(); canvas.invalidate()
        }, onClearPage = if (tool == Tools.ERASER) ({
            popups.dismissAll()
            if (page.strokes.isNotEmpty()) { edit(page.copy(strokes = emptyList())); toast("Page cleared. Undo brings it back.") }
        }) else null)
        if (tool == Tools.ERASER) {
            opts.addView(sectionTitle(this, "Erase", colors))
            opts.addView(Segmented(this, listOf("Whole strokes", "Just what I touch"), if (canvas.partialEraser) 1 else 0, colors) {
                canvas.partialEraser = it == 1
                settings.prefs.edit().putBoolean("sketchPartialEraser", it == 1).apply()
            })
        }
        popups.show(opts, beside(anchor), below = true, maxWidthDp = 320, alignRight = settings.toolbarRight, alignLeft = !settings.toolbarRight)
    }

    private fun shapeName(mode: String) = when (mode) {
        SketchCanvas.LINE -> "Straight line"; SketchCanvas.ARROW -> "Arrow"
        SketchCanvas.RECTANGLE -> "Rectangle"; SketchCanvas.ELLIPSE -> "Ellipse"; else -> mode
    }

    // ------------------------------------------------------------------ storage work

    /** Storage work waiting for the one before it to finish. */
    private val ioQueue = ArrayDeque<() -> Unit>()

    /**
     * Runs [work] off the main thread once every queued save is on disk, then [done] on the main
     * thread. Requests run one at a time, in order, and drawing is paused while one runs, so the
     * page on screen never falls behind the stored page.
     */
    internal fun <T> io(work: () -> T, failed: () -> Unit = {}, done: (T) -> Unit = {}) {
        val start: () -> Unit = {
            loading = true; canvas.enabledForInput = false
            writer.afterSaved {
                if (isDestroyed) return@afterSaved
                scope.launch {
                    val result = try { Result.success(withContext(Dispatchers.IO) { work() }) }
                    catch (e: CancellationException) { if (!isActive) throw e; Result.failure(e) }
                    catch (e: Exception) { Result.failure(e) }
                    loading = false; canvas.enabledForInput = true
                    result.onSuccess { done(it) }.onFailure { e ->
                        toast(if (e is CancellationException) "Canceled" else e.message ?: "Could not complete this action"); failed()
                    }
                    if (!loading) ioQueue.removeFirstOrNull()?.invoke()
                }
            }
        }
        if (loading) ioQueue.addLast(start) else start()
        if (writer.error != null) toast("Waiting for your last change to save…")
    }

    private fun show(p: PageInfo, content: SketchPage) {
        info = p; page = content; canvas.setPage(content, p)
        updateLabels(); showStatus(writer.state); refreshDock(); refreshHistory(); updateContextBar()
    }

    internal fun reload() {
        val id = info?.id ?: return
        io({ Triple(store.get(bookId)!!, store.infoById(bookId, id), store.pageById(bookId, id)) }) { book = it.first; show(it.second, it.third) }
    }

    /** Shows the stored page again without resetting the view, tool or reference being arranged. */
    private fun refreshFromStore() {
        val current = info ?: return
        io({
            val b = store.get(bookId) ?: error("This sketchbook is no longer in the library")
            val p = runCatching { store.infoById(bookId, current.id) }.getOrNull()
            if (p != null) Triple(b, p, store.pageById(bookId, p.id)) else Triple(b, store.info(bookId, b.currentPage), store.page(bookId, b.currentPage))
        }, failed = { finish() }) { (b, p, content) ->
            book = b
            if (p.id == current.id) {
                info = p.copy(zoom = current.zoom, panX = current.panX, panY = current.panY, rotation = current.rotation); page = content
                canvas.setPage(content); updateLabels(); refreshDock(); refreshHistory()
            } else show(p, content)
        }
    }

    private fun turn(step: Int) = navigate((info?.index ?: 0) + step)

    internal fun navigate(index: Int) {
        if (index !in 0 until (book?.pageCount ?: 0)) return
        io({ store.setCurrentPage(bookId, index); Triple(store.get(bookId)!!, store.info(bookId, index), store.page(bookId, index)) }) { book = it.first; show(it.second, it.third) }
    }

    internal fun navigateId(id: Long) {
        io({ store.setCurrentId(bookId, id); Triple(store.get(bookId)!!, store.infoById(bookId, id), store.pageById(bookId, id)) }) { book = it.first; show(it.second, it.third) }
    }

    private fun edit(before: SketchPage, after: SketchPage) {
        val id = info?.id ?: return
        page = after; writer.enqueue(id, before, after); refreshDock()
        canUndo = true; canRedo = false; updateHistoryButtons()
    }

    internal fun edit(after: SketchPage) { val before = page; page = after; canvas.setPage(after); edit(before, after) }

    private fun refreshHistory() {
        val id = info?.id ?: return
        val request = ++historyGeneration
        scope.launch {
            val state = withContext(Dispatchers.IO) { runCatching { store.history(id) }.getOrNull() } ?: return@launch
            if (info?.id == id && request == historyGeneration) { canUndo = state.first; canRedo = state.second; updateHistoryButtons() }
        }
    }

    private fun history(redo: Boolean) {
        val id = info?.id ?: return
        io({ store.undo(bookId, id, redo) }) { page = it; canvas.setPage(it); refreshDock(); refreshHistory() }
    }

    // ------------------------------------------------------------------ popups

    private fun anchorOf(v: View): RectF {
        val loc = IntArray(2); v.getLocationInWindow(loc)
        return RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + v.width).toFloat(), (loc[1] + v.height).toFloat())
    }

    /** Just beside a tool on the rail, on the page side. */
    private fun beside(v: View): RectF {
        val r = anchorOf(v)
        return if (settings.toolbarRight) RectF(r.left - dp(8), r.top, r.left - dp(8), r.top) else RectF(r.right + dp(8), r.top, r.right + dp(8), r.top)
    }

    /** One row of a popup menu: an optional icon, then the label. */
    private fun menuRow(icon: String?, text: String, active: Boolean = false, onClick: () -> Unit): View {
        val ui = colors
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(20), dp(10))
            if (active) background = panelBg(context, ui, 6f, 0f, ui.soft)
            addView(TextView(context).apply {
                this.text = icon ?: ""; typeface = Fonts.icons(context); textSize = 20f; setTextColor(if (active) ui.fg else ui.muted)
                gravity = Gravity.CENTER; includeFontPadding = false
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
            addView(label(context, text, ui, 15.5f, bold = active))
            isClickable = true
            setOnClickListener { popups.dismissAll(); onClick() }
        }
    }

    private fun popupMenu(anchor: View, rows: List<Triple<String?, String, () -> Unit>>, heading: String? = null) {
        popups.dismissAll()
        val c = card(this, colors, 8)
        if (heading != null) c.addView(label(this, heading, colors, 13f, colors.muted, bold = true).apply { setPadding(dp(12), dp(6), dp(12), dp(4)) })
        rows.forEach { (icon, text, f) -> c.addView(menuRow(icon, text, onClick = f)) }
        popups.show(c, anchorOf(anchor), below = true, maxWidthDp = 320, alignRight = true)
    }

    /** A titled card, scrolling if it is taller than most of the screen. */
    internal fun panel(titleText: String, fill: (LinearLayout) -> Unit, anchor: View?, maxWidthDp: Int = 400, onDismiss: (() -> Unit)? = null) {
        // Popups size themselves from the screen, so wait until it has been laid out.
        if (root.width == 0) { root.post { if (!isDestroyed) panel(titleText, fill, anchor, maxWidthDp, onDismiss) }; return }
        popups.dismissAll()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fill(body)
        val c = card(this, colors)
        c.addView(label(this, titleText, colors, 19f, bold = true, serif = true))
        c.addView(object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec((root.height * 0.72f).toInt(), View.MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false; addView(body) })
        popups.show(c, anchor?.let { anchorOf(it) }, below = true, maxWidthDp = maxWidthDp, alignRight = true, onDismiss = onDismiss)
    }

    private fun showMenu(anchor: View) {
        val p = info
        popupMenu(anchor, listOf(
            Triple(ICON_PAPER, "Paper") { showPaper(anchor) },
            Triple(Icons.BOOKMARK_BORDER, if (p != null) "Page ${p.index + 1}…" else "This page…") { p?.let { pageMenu(it, anchor) } },
            Triple(Icons.ADD, "Add pages…") { addPages() },
            Triple(ICON_FIT, "Fit page to screen") { canvas.fit() },
            Triple(ICON_FOCUS, "Focus mode") { focus = true; applyFocus() },
            Triple(Icons.SETTINGS_TUNE, "Pen & touch") { touchOptions() },
            Triple(Icons.EDIT, "Pen presets") { presets(anchor) },
            Triple(ICON_EXPORT, "Export or back up…") { exportOptions(anchor) },
            Triple(ICON_RESTORE, "Restore a backup…") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/zip"; addCategory(Intent.CATEGORY_OPENABLE) }, RESTORE) },
            Triple(Icons.DELETE, "Trash") { trash(anchor) },
            Triple(Icons.TEXT, "Rename sketchbook…") {
                input("Rename sketchbook", book?.title.orEmpty()) { name -> if (name.isNotBlank()) io({ store.rename(bookId, name); store.get(bookId) }) { book = it; updateLabels() } }
            },
        ))
    }

    private fun showPaper(anchor: View) = panel("Paper", { b ->
        val ui = colors
        b.addView(sectionTitle(this, "Style", ui))
        b.addView(Segmented(this, listOf("Blank", "Lined", "Grid", "Dotted"), page.paper, ui) { edit(page.copy(paper = it)) })
        b.addView(sectionTitle(this, "Spacing", ui))
        b.addView(slider(this, 100, ((page.spacing - .5f) / 2.5f * 100).toInt(), ui) { v, done -> if (done) edit(page.copy(spacing = .5f + 2.5f * v / 100f)) })
        b.addView(TextButton(this, "Use for new pages", ui).apply {
            setOnClickListener { io({ store.defaults(bookId, page.paper, page.spacing) }) { toast("New pages will use this paper") } }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
    }, anchor, 380)

    private fun touchOptions() = panel("Pen & touch", { b ->
        val ui = colors
        b.addView(toggleRow(this, "Draw with finger", "Two fingers move and zoom the page", settings.fingerDraw, ui) { settings.fingerDraw = it })
        b.addView(toggleRow(this, "Tools on the right", null, settings.toolbarRight, ui) { settings.toolbarRight = it; build() })
        b.addView(sectionTitle(this, "S Pen pressure", ui))
        b.addView(slider(this, 100, (settings.pressure * 100).toInt(), ui) { v, done ->
            if (done) { settings.pressure = v / 100f; page.strokes.forEach { it.shape = null }; rail?.refresh(); canvas.invalidate() }
        })
        b.addView(label(this, "The S Pen draws. A finger swipe turns the page; pinch to zoom, twist to rotate. Hold the S Pen button to erase.", ui, 12.5f, ui.muted).apply { setPadding(0, dp(8), 0, 0) })
    }, null, 420)

    private fun presets(anchor: View) {
        val data = runCatching { JSONArray(settings.prefs.getString("sketchPresets", "[]")) }.getOrDefault(JSONArray())
        val rows = (0 until data.length()).map { i ->
            val p = data.getJSONObject(i)
            Triple<String?, String, () -> Unit>(Icons.DRAW, p.getString("name")) {
                settings.tool = p.getInt("tool")
                settings.setToolColor(settings.tool, p.getInt("color"))
                settings.setToolWidth(settings.tool, p.getDouble("width").toFloat())
                setMode(SketchCanvas.DRAW)
            }
        } + Triple<String?, String, () -> Unit>(Icons.ADD, "Save the current pen…") {
            input("Preset name", "${Tools.name(settings.tool)} ${data.length() + 1}") { name ->
                if (name.isNotBlank()) {
                    data.put(JSONObject().put("name", name).put("tool", settings.tool).put("color", settings.toolColor(settings.tool)).put("width", settings.toolWidth(settings.tool)))
                    settings.prefs.edit().putString("sketchPresets", data.toString()).apply()
                }
            }
        }
        popupMenu(anchor, rows, "Pen presets")
    }

    private fun addPages() = input("Add pages at the end", "50", true) { n ->
        val room = SketchStore.MAX_PAGES - (book?.pageCount ?: 0)
        val count = n.toIntOrNull()
        when {
            count == null || count < 1 -> toast("Enter how many pages to add")
            count > room -> toast(if (room <= 0) "This sketchbook is full (${"%,d".format(SketchStore.MAX_PAGES)} pages)" else "You can add up to $room more pages")
            else -> io({ store.addPages(bookId, count); store.get(bookId) }) { book = it; updateLabels(); toast("Added $count pages") }
        }
    }

    private fun browse() { io({ store.pages(bookId) }) { pages -> SketchPageBrowser(this, pages).show() } }

    internal fun pageMenu(p: PageInfo, anchor: View? = null) {
        val rows = listOf<Triple<String?, String, () -> Unit>>(
            Triple(Icons.TEXT, "Title…") { input("Page title", p.title) { name -> io({ store.metadata(p.copy(title = name)) }) { if (info?.id == p.id) { info = info?.copy(title = name); showStatus(writer.state) } } } },
            Triple(Icons.TOC, "Section…") { input("Section name", p.section) { name -> io({ store.metadata(p.copy(section = name)) }) { if (info?.id == p.id) { info = info?.copy(section = name); showStatus(writer.state) } } } },
            Triple(if (p.bookmarked) Icons.BOOKMARK else Icons.BOOKMARK_BORDER, if (p.bookmarked) "Remove bookmark" else "Bookmark") {
                io({ store.metadata(p.copy(bookmarked = !p.bookmarked)) }) { if (info?.id == p.id) info = info?.copy(bookmarked = !p.bookmarked) }
            },
            Triple(Icons.SWAP, "Move to page…") {
                input("Move to page", "${p.index + 1}", true) { n ->
                    val dest = n.toIntOrNull()?.minus(1)
                    if (dest != null && dest in 0 until (book?.pageCount ?: 0)) io({ store.movePage(bookId, p.id, dest); Triple(store.get(bookId)!!, store.infoById(bookId, p.id), store.pageById(bookId, p.id)) }) { book = it.first; show(it.second, it.third) }
                }
            },
            Triple(Icons.COPY, "Duplicate page") { io({ val id = store.duplicatePage(bookId, p.id); Triple(store.get(bookId)!!, store.infoById(bookId, id), store.pageById(bookId, id)) }) { book = it.first; show(it.second, it.third) } },
            Triple(Icons.ADD, "Insert a blank page before") { io({ val id = store.insertPages(bookId, p.index, 1).single(); Triple(store.get(bookId)!!, store.infoById(bookId, id), store.pageById(bookId, id)) }) { book = it.first; show(it.second, it.third) } },
            Triple(Icons.DELETE, "Move page to Trash") {
                io({ store.deletePage(bookId, p.id); val b = store.get(bookId)!!; val n = minOf(p.index, b.pageCount - 1); Triple(b, store.info(bookId, n), store.page(bookId, n)) }) { book = it.first; show(it.second, it.third); toast("Page moved to Trash") }
            },
        )
        if (anchor != null) popupMenu(anchor, rows, "Page ${p.index + 1}")
        else {
            // From the page browser: centred on screen.
            popups.dismissAll()
            val c = card(this, colors, 8)
            c.addView(label(this, "Page ${p.index + 1}", colors, 13f, colors.muted, bold = true).apply { setPadding(dp(12), dp(6), dp(12), dp(4)) })
            rows.forEach { (icon, text, f) -> c.addView(menuRow(icon, text, onClick = f)) }
            popups.show(c, null, maxWidthDp = 320)
        }
    }

    private fun trash(anchor: View) {
        io({ store.trash(bookId) }) { items ->
            if (items.isEmpty()) toast("Trash is empty")
            else popupMenu(anchor, items.map { item ->
                Triple<String?, String, () -> Unit>(ICON_RESTORE, item.title) { io({ store.restore(item.id, bookId); store.get(bookId) }) { book = it; updateLabels(); toast("Restored at the end") } }
            }, "Tap a page to restore it")
        }
    }

    // ------------------------------------------------------------------ references

    private fun showReferences(anchor: View) {
        if (page.references.isEmpty()) { toast("Nothing to trace yet. Use the picture button to import one."); return }
        SketchReferencePanel(this, anchor).show()
    }

    internal fun arrangeReference(id: Long) {
        val r = page.references.firstOrNull { it.id == id } ?: return
        popups.dismissAll()
        edit(page.copy(references = page.references.map { if (it.id == id) r.copy(locked = false, docked = false, visible = true) else it }))
        canvas.referenceId = id; canvas.invalidate(); updateContextBar()
    }

    private fun finishArranging() {
        val id = canvas.referenceId
        edit(page.copy(references = page.references.map { if (it.id == id) it.copy(locked = true) else it }))
        canvas.referenceId = null; canvas.invalidate(); updateContextBar()
    }

    internal fun openSource(ref: ReferenceLayer) {
        io({ app.db.book(ref.sourceBook) }) { source ->
            if (source == null) toast("That book is no longer in the library. The reference is still here.")
            else {
                val launch = ReaderActivity.intent(this, ref.sourceBook)
                runCatching { val p = JSONObject(ref.sourceLocation); launch.putExtra(ReaderActivity.EXTRA_START, intArrayOf(p.getInt("block"), p.getInt("offset"))) }
                startActivity(launch)
            }
        }
    }

    /** References pinned beside the page, in a resizable side (or bottom) panel. */
    private fun refreshDock() {
        if (!::workspace.isInitialized) return
        val refs = page.references.filter { it.docked && it.visible }
        val key = "${info?.id}|$dockRefId|$refs"; if (key == dockKey) return; dockKey = key
        dock?.let { workspace.removeView(it); (it.tag as? View)?.let { edge -> workspace.removeView(edge) } }; dock = null
        if (refs.isEmpty()) return
        val ui = colors
        val landscape = workspace.orientation == LinearLayout.HORIZONTAL
        val ref = refs.firstOrNull { it.id == dockRefId } ?: refs.first()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.bg) }
        dock = panel
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
        val grip = label(this, if (landscape) "⋮" else "⋯", ui, 18f, ui.muted).apply { gravity = Gravity.CENTER; contentDescription = "Drag to resize" }
        head.addView(grip, LinearLayout.LayoutParams(dp(28), dp(44)))
        head.addView(label(this, ref.name, ui, 14f, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            if (refs.size > 1) setOnClickListener { v -> popupMenu(v, refs.map { r -> Triple<String?, String, () -> Unit>(ICON_LAYERS, r.name) { dockRefId = r.id; refreshDock() } }, "Pinned references") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconButton(this, Icons.CLOSE, ui, 40).apply {
            contentDescription = "Unpin"
            setOnClickListener { edit(page.copy(references = page.references.map { if (it.id == ref.id) it.copy(docked = false, visible = false) else it })) }
        })
        panel.addView(head)
        panel.addView(divider(this, ui).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
        val view = SketchReferenceView(this)
        panel.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        val edge = View(this).apply { setBackgroundColor(ui.hairline) }
        if (landscape) {
            workspace.addView(edge, LinearLayout.LayoutParams(dp(1), -1))
            workspace.addView(panel, LinearLayout.LayoutParams(dp(300), -1))
        } else {
            workspace.addView(edge, LinearLayout.LayoutParams(-1, dp(1)))
            workspace.addView(panel, LinearLayout.LayoutParams(-1, dp(280)))
        }
        // Removing the panel later must take its edge line too.
        panel.tag = edge
        var initialSize = 0; var initialTouch = 0f
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { initialSize = if (landscape) panel.width else panel.height; initialTouch = if (landscape) e.rawX else e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (initialTouch - (if (landscape) e.rawX else e.rawY)).toInt()
                    val cap = ((if (landscape) workspace.width else workspace.height) * 2 / 3).coerceAtLeast(dp(180))
                    panel.layoutParams = panel.layoutParams.apply { if (landscape) width = (initialSize + delta).coerceIn(dp(180), cap) else height = (initialSize + delta).coerceIn(dp(150), cap) }
                }
            }
            true
        }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { SketchRenderer.decode(store.referencesDir, ref, 1600) }.getOrNull() }
            if (dock === panel && bmp != null) view.display(bmp, ref, settings.dark) else bmp?.recycle()
        }
    }

    // ------------------------------------------------------------------ import and export

    private fun pickReference() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/epub+zip",
                "application/x-mobipocket-ebook", "application/octet-stream", "text/plain"))
        }, IMPORT)
    }

    private fun exportOptions(anchor: View) {
        io({ store.pages(bookId) }) { pages ->
            fun go() {
                if (exportIds.isEmpty()) { toast("No used pages to export"); return }
                if (exportKind == "zip") createExport()
                else popupMenu(anchor, listOf(
                    Triple<String?, String, () -> Unit>(Icons.LIGHT, "Light paper, original colours") { exportDark = false; createExport() },
                    Triple<String?, String, () -> Unit>(Icons.DARK, "Dark, as on screen") { exportDark = true; createExport() },
                ), "Appearance")
            }
            fun pick(kind: String, ids: List<Long>) { exportKind = kind; exportIds = ids; go() }
            popupMenu(anchor, listOf(
                Triple(ICON_PDF, "This page as PDF") { pick("pdf", listOfNotNull(info?.id)) },
                Triple(ICON_PDF, "Pages I've used as PDF") { pick("pdf", pages.filter { it.used }.map { it.id }) },
                Triple(ICON_PDF, "Choose pages as PDF…") {
                    input("Pages to export, e.g. 1-5, 8", "${(info?.index ?: 0) + 1}") { value ->
                        val picked = runCatching { SketchImportPanel.parse(value, pages.size) }.getOrElse { toast(it.message ?: "Use pages such as 1-5, 8"); return@input }
                        pick("pdf", picked.map { pages[it].id })
                    }
                },
                Triple(ICON_PDF, "Every page as PDF") { pick("pdf", pages.map { it.id }) },
                Triple(ICON_IMAGE, "This page as a picture (PNG)") { pick("png", listOfNotNull(info?.id)) },
                Triple(ICON_BACKUP, "Backup to restore later (.zip)") { pick("zip", pages.map { it.id }) },
            ), "Export")
        }
    }

    private fun createExport() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when (exportKind) { "zip" -> "application/zip"; "png" -> "image/png"; else -> "application/pdf" }
            putExtra(Intent.EXTRA_TITLE, "${book?.title ?: "Sketchbook"}.$exportKind")
        }, EXPORT)
    }

    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            IMPORT -> prepareImport(uri)
            EXPORT -> io({
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    when (exportKind) {
                        "zip" -> SketchArchive.write(store, bookId, out)
                        "png" -> SketchExport.png(store, bookId, exportIds.single(), out, exportDark)
                        else -> SketchExport.pdf(store, bookId, exportIds, out, exportDark)
                    }
                } ?: error("Could not write the selected file")
            }) { toast("Export saved") }
            RESTORE -> io({ contentResolver.openInputStream(uri)?.use { SketchArchive.restore(store, it) } ?: error("Could not open backup") }) { startActivity(intent(this, it)); finish() }
        }
    }

    private fun prepareImport(uri: Uri) {
        // Converting a long book or document into pages can take a while: show progress and allow cancel.
        @Suppress("DEPRECATION") val progress = android.app.ProgressDialog(this, com.rotfarmer.ereader.ui.dialogTheme(settings.dark)).apply {
            setTitle("Preparing import"); setMessage("Reading the file…"); setCancelable(false)
        }
        val cancelled = AtomicBoolean(false)
        @Suppress("DEPRECATION") progress.setButton(android.app.AlertDialog.BUTTON_NEGATIVE, "Cancel") { _, _ -> cancelled.set(true) }
        progress.show()
        io({
            try { SketchImportSession.prepare(this, store, uri, contentResolver.getType(uri), { cancelled.get() }) { msg -> runOnUiThread { if (progress.isShowing) progress.setMessage(msg) } } }
            finally { runOnUiThread { if (progress.isShowing) progress.dismiss() } }
        }) { session ->
            if (isDestroyed) { session.close(); return@io }
            pendingImport = session; SketchImportPanel(this, session) { pendingImport = null }.show()
        }
    }

    private fun receiveExcerpt() {
        val path = intent.getStringExtra("excerpt_path"); val text = intent.getStringExtra("excerpt_text")
        if (path == null && text == null) return
        io({
            SketchImportSession.excerpt(this, store, path, text, intent.getLongExtra("source_book", -1),
                intent.getStringExtra("source_location").orEmpty(), intent.getStringExtra("source_label").orEmpty())
        }) { session -> pendingImport = session; SketchImportPanel(this, session) { pendingImport = null }.show() }
    }

    // ------------------------------------------------------------------ small helpers

    internal fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    internal fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
    internal fun button(text: String, action: () -> Unit) = TextButton(this, text, colors).apply { minHeight = dp(44); setOnClickListener { action() } }

    internal fun input(title: String, value: String, numeric: Boolean = false, action: (String) -> Unit) {
        val input = EditText(this).apply {
            setTextColor(colors.fg); setHintTextColor(colors.muted); setSingleLine(); setText(value); selectAll()
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        dialog(this, settings.dark).setTitle(title).setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> action(input.text.toString().trim()) }.show()
    }

    companion object {
        private const val EXTRA_BOOK = "sketchbook"
        private const val IMPORT = 51
        private const val EXPORT = 52
        private const val RESTORE = 53
        private const val ICON_ADD_PICTURE = ""  // add_photo_alternate
        private const val ICON_LAYERS = ""       // layers
        private const val ICON_PAPER = ""        // grid_on
        private const val ICON_FIT = ""          // fit_screen
        private const val ICON_FOCUS = ""        // fullscreen
        private const val ICON_EXIT_FOCUS = ""   // close_fullscreen
        private const val ICON_EXPORT = ""       // ios_share
        private const val ICON_RESTORE = ""      // restore
        private const val ICON_PDF = ""          // picture_as_pdf
        private const val ICON_IMAGE = ""        // image
        private const val ICON_BACKUP = ""       // cloud_done

        fun intent(context: android.content.Context, id: Long) = Intent(context, SketchActivity::class.java).putExtra(EXTRA_BOOK, id)
    }
}
