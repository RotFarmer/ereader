package com.rotfarmer.ereader.sketch

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.content.res.Configuration
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.rotfarmer.ereader.App
import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkPainter
import com.rotfarmer.ereader.ink.InkStroke
import com.rotfarmer.ereader.ink.Placement
import com.rotfarmer.ereader.ink.Tools
import com.rotfarmer.ereader.ui.IconButton
import com.rotfarmer.ereader.ui.Icons
import com.rotfarmer.ereader.ui.PenToolbar
import com.rotfarmer.ereader.ui.TextButton
import com.rotfarmer.ereader.ui.ToolOptions
import com.rotfarmer.ereader.ui.UiColors
import com.rotfarmer.ereader.ui.dialog
import com.rotfarmer.ereader.ui.dp
import com.rotfarmer.ereader.ui.label
import com.rotfarmer.ereader.render.ReaderColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.abs

/** Full page S Pen canvas. Only the open page and its reference bitmaps are loaded. */
class SketchActivity : Activity(), PenToolbar.Callbacks {
    private val app by lazy { App.of(this) }
    private val store by lazy { SketchStore(this) }
    private val settings get() = app.settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ui get() = UiColors(settings.dark)
    private var bookId = -1L
    private var book: Sketchbook? = null
    private var pageIndex = 0
    private var page = SketchPage(0, emptyList(), emptyList())
    private var generation = 0
    private lateinit var root: FrameLayout
    private lateinit var canvas: SketchCanvasView
    private lateinit var pageLabel: TextView
    private lateinit var pageHint: TextView
    private lateinit var titleLabel: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var arrangeDone: TextButton
    private var penToggle: IconButton? = null
    private var chromeVisible = false
    private var toolbar: PenToolbar? = null
    private val undo = ArrayDeque<InkEdit>()
    private val redo = ArrayDeque<InkEdit>()
    private var arranging = false
    private var selectedRef = -1L
    private var loading = false

    private data class InkEdit(val added: List<InkStroke>, val removed: List<InkStroke>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getLongExtra(EXTRA_BOOK, -1)
        book = store.get(bookId)
        if (book == null) { finish(); return }
        pageIndex = book!!.currentPage
        build()
        loadPage()
    }

    override fun onDestroy() {
        generation++
        scope.cancel()
        if (::canvas.isInitialized) canvas.dispose()
        super.onDestroy()
    }

    override fun onStop() {
        page.references.firstOrNull { it.id == selectedRef }?.let(store::updateReference)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        build()
        canvas.setPage(page)
        toolbar?.setUndoState(undo.isNotEmpty(), redo.isNotEmpty())
    }

    private fun build() {
        if (::canvas.isInitialized) canvas.dispose()
        val colors = ui
        root = FrameLayout(this).apply {
            setBackgroundColor(ReaderColors(settings.dark).paper)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
                insets
            }
        }
        canvas = SketchCanvasView()
        root.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        toolbar = PenToolbar(this, colors, settings, this, showInsertSpace = false).apply { visibility = if (settings.toolbarVisible) View.VISIBLE else View.GONE }
        root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or if (settings.toolbarRight) Gravity.END else Gravity.START).apply {
            marginStart = dp(6); marginEnd = dp(6)
        })

        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundColor(colors.bg)
            visibility = if (chromeVisible) View.VISIBLE else View.GONE
        }
        topBar.addView(IconButton(this, Icons.BACK, colors).apply { contentDescription = "Back to library"; setOnClickListener { finish() } })
        titleLabel = label(this, book!!.title, colors, 17f, serif = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), 0) }
        topBar.addView(titleLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        arrangeDone = TextButton(this, "Done", colors).apply {
            visibility = if (arranging) View.VISIBLE else View.GONE
            setOnClickListener { arranging = false; selectedRef = -1L; visibility = View.GONE; canvas.invalidate(); showChrome(false) }
        }
        topBar.addView(arrangeDone)
        topBar.addView(IconButton(this, Icons.STORIES, colors).apply { contentDescription = "Reference layers"; setOnClickListener { layers() } })
        topBar.addView(IconButton(this, Icons.LIBRARY_ADD, colors).apply { contentDescription = "Import a reference"; setOnClickListener { pickReference() } })
        penToggle = IconButton(this, Icons.DRAW, colors).apply {
            contentDescription = "Show or hide pen tools"
            active = settings.toolbarVisible
            setOnClickListener { settings.toolbarVisible = !settings.toolbarVisible; active = settings.toolbarVisible; toolbar?.visibility = if (settings.toolbarVisible) View.VISIBLE else View.GONE }
        }
        topBar.addView(penToggle)
        topBar.addView(IconButton(this, Icons.MORE, colors).apply { contentDescription = "Sketchbook options"; setOnClickListener { options() } })
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.bg)
            visibility = if (chromeVisible) View.VISIBLE else View.GONE
        }
        bottomBar.addView(View(this).apply { setBackgroundColor(colors.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
        nav.addView(IconButton(this, Icons.CHEV_LEFT, colors).apply { contentDescription = "Previous page"; setOnClickListener { turn(-1) } })
        pageLabel = label(this, "", colors, 15f, bold = true).apply { gravity = Gravity.CENTER; setOnClickListener { goToPage() } }
        nav.addView(pageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(IconButton(this, Icons.CHEV_RIGHT, colors).apply { contentDescription = "Next page"; setOnClickListener { turn(1) } })
        nav.addView(IconButton(this, Icons.ADD, colors).apply { contentDescription = "Add pages"; setOnClickListener { addPagesDialog() } })
        bottomBar.addView(nav)
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        pageHint = label(this, "", colors, 13f, colors.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(10))
            visibility = if (chromeVisible) View.GONE else View.VISIBLE
            setOnClickListener { showChrome(true) }
        }
        root.addView(pageHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        updateLabels()
    }

    private fun showChrome(show: Boolean) {
        chromeVisible = show
        topBar.visibility = if (show) View.VISIBLE else View.GONE
        bottomBar.visibility = if (show) View.VISIBLE else View.GONE
        pageHint.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun updateLabels() {
        book = store.get(bookId) ?: return
        titleLabel.text = book!!.title
        pageLabel.text = "${pageIndex + 1} / ${book!!.pageCount}"
        pageHint.text = "${pageIndex + 1} / ${book!!.pageCount}"
        toolbar?.setUndoState(undo.isNotEmpty(), redo.isNotEmpty())
    }

    private fun loadPage() {
        val request = ++generation
        val current = pageIndex
        loading = true
        page = SketchPage(0, emptyList(), emptyList())
        canvas.setPage(page)
        selectedRef = -1L
        arranging = false
        arrangeDone.visibility = View.GONE
        undo.clear(); redo.clear()
        updateLabels()
        showChrome(false)
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { store.page(bookId, current) }
                if (request != generation) return@launch
                page = loaded
                canvas.setPage(loaded)
                toolbar?.setUndoState(false, false)
            } catch (e: Exception) {
                if (request == generation) Toast.makeText(this@SketchActivity, e.message ?: "Could not open page", Toast.LENGTH_LONG).show()
            } finally {
                if (request == generation) loading = false
            }
        }
    }

    private fun turn(delta: Int) {
        val next = pageIndex + delta
        val count = book?.pageCount ?: return
        if (next !in 0 until count) return
        pageIndex = next
        store.setCurrentPage(bookId, pageIndex)
        loadPage()
    }

    private fun choosePaper() {
        val types = arrayOf("Blank", "Lined", "Grid", "Dotted")
        dialog(this, settings.dark).setTitle("Paper for page ${pageIndex + 1}")
            .setSingleChoiceItems(types, page.paper) { d, choice ->
                store.setPaper(bookId, pageIndex, choice)
                page = page.copy(paper = choice)
                canvas.invalidate()
                d.dismiss()
            }.show()
    }

    private fun options() {
        dialog(this, settings.dark).setItems(arrayOf("Paper for this page", "Reference layers", "Import reference", "Rename sketchbook", "Go to page", "Add pages")) { _, choice ->
            when (choice) { 0 -> choosePaper(); 1 -> layers(); 2 -> pickReference(); 3 -> rename(); 4 -> goToPage(); 5 -> addPagesDialog() }
        }.show()
    }

    private fun rename() {
        val input = android.widget.EditText(this).apply {
            setSingleLine(); setTextColor(ui.fg); setHintTextColor(ui.muted)
            setText(book?.title ?: ""); selectAll()
        }
        dialog(this, settings.dark).setTitle("Rename sketchbook").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { store.rename(bookId, name); updateLabels() }
            }.show()
    }

    private fun addPagesDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(ui.fg); setHintTextColor(ui.muted); setText("50")
        }
        dialog(this, settings.dark).setTitle("Add pages").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ ->
                val count = input.text.toString().toIntOrNull()?.coerceIn(1, 10000) ?: return@setPositiveButton
                store.addPages(bookId, count); updateLabels()
            }.show()
    }

    private fun goToPage() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(ui.fg); setHintTextColor(ui.muted); setText("${pageIndex + 1}")
        }
        dialog(this, settings.dark).setTitle("Go to page").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Go") { _, _ ->
                val target = input.text.toString().toIntOrNull()?.minus(1) ?: return@setPositiveButton
                if (target in 0 until (book?.pageCount ?: 0)) {
                    pageIndex = target; store.setCurrentPage(bookId, target); loadPage()
                }
            }.show()
    }

    private fun pickReference() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain"))
        }
        startActivityForResult(intent, PICK_REFERENCE)
    }

    @Deprecated("Activity result API is sufficient without an additional dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_REFERENCE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val start = pageIndex
        Toast.makeText(this, "Importing reference…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) { SketchImporter(this@SketchActivity, store).import(uri, contentResolver.getType(uri), bookId, start) }
                if (pageIndex == start) loadPage()
                updateLabels()
                Toast.makeText(this@SketchActivity, "Imported $count ${if (count == 1) "page" else "pages"}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SketchActivity, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun layers() {
        if (page.references.isEmpty()) {
            Toast.makeText(this, "No references on this page", Toast.LENGTH_SHORT).show(); return
        }
        val names = page.references.mapIndexed { index, _ -> "Reference ${index + 1}" }.toTypedArray()
        dialog(this, settings.dark).setTitle("Reference layers").setItems(names) { _, choice -> referenceOptions(page.references[choice].id) }.show()
    }

    private fun referenceOptions(id: Long) {
        val ref = page.references.firstOrNull { it.id == id } ?: return
        dialog(this, settings.dark).setTitle("Reference layer")
            .setItems(arrayOf("Move and scale", "Opacity", "Remove reference")) { _, choice ->
                when (choice) {
                    0 -> { arranging = true; selectedRef = id; arrangeDone.visibility = View.VISIBLE; showChrome(true); canvas.invalidate(); Toast.makeText(this, "Drag with a finger to move; pinch to scale. S Pen still draws.", Toast.LENGTH_LONG).show() }
                    1 -> opacity(ref)
                    2 -> dialog(this, settings.dark).setMessage("Remove this reference? Your drawing stays on the page.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                            store.removeReference(bookId, ref)
                            page = page.copy(references = page.references.filterNot { it.id == id })
                            selectedRef = -1L; arranging = false; arrangeDone.visibility = View.GONE; canvas.setPage(page)
                        }.show()
                }
            }.show()
    }

    private fun opacity(ref: ReferenceLayer) {
        val slider = SeekBar(this).apply { max = 100; progress = (ref.opacity * 100).toInt(); setPadding(dp(20), dp(10), dp(20), dp(10)) }
        dialog(this, settings.dark).setTitle("Reference opacity").setView(slider)
            .setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
                changeReference(ref.copy(opacity = (slider.progress / 100f).coerceIn(0.05f, 1f)))
            }.show()
    }

    private fun changeReference(ref: ReferenceLayer, persist: Boolean = true) {
        if (persist) store.updateReference(ref)
        page = page.copy(references = page.references.map { if (it.id == ref.id) ref else it })
        canvas.invalidate()
    }

    private fun addStroke(points: FloatArray, tool: Int) {
        val temp = InkStroke(-1, bookId, Anchor(Anchor.PDF, pageIndex.toLong(), 0), tool,
            settings.toolColor(tool), dp(settings.toolWidth(tool)).toFloat() / canvas.paperRect.width(), points, System.currentTimeMillis())
        val id = store.insertStroke(bookId, pageIndex, temp)
        val stroke = InkStroke(id, bookId, temp.anchor, temp.tool, temp.color, temp.width, points, temp.created)
        page = page.copy(strokes = page.strokes + stroke)
        record(InkEdit(listOf(stroke), emptyList()))
        canvas.invalidate()
    }

    private fun eraseAt(x: Float, y: Float, removed: MutableList<InkStroke>) {
        val r = canvas.paperRect
        val nx = (x - r.left) / r.width()
        val ny = (y - r.top) / r.width()
        val radius = dp(settings.toolWidth(Tools.ERASER)) / r.width()
        val hits = page.strokes.filter { it !in removed && hitStroke(it, nx, ny, radius) }
        if (hits.isEmpty()) return
        hits.forEach { store.deleteStroke(it.id) }
        removed += hits
        page = page.copy(strokes = page.strokes.filterNot { it in hits })
        canvas.invalidate()
    }

    private fun hitStroke(s: InkStroke, x: Float, y: Float, radius: Float): Boolean {
        val p = s.pts
        val tolerance = radius + s.width / 2
        for (i in 0 until p.size / 3) {
            val j = i * 3
            if (i == 0 && hypot(p[j] - x, p[j + 1] - y) <= tolerance) return true
            if (i == 0) continue
            val a = j - 3
            val dx = p[j] - p[a]; val dy = p[j + 1] - p[a + 1]
            val t = if (dx * dx + dy * dy == 0f) 0f else (((x - p[a]) * dx + (y - p[a + 1]) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
            if (hypot(p[a] + t * dx - x, p[a + 1] + t * dy - y) <= tolerance) return true
        }
        return false
    }

    private fun record(edit: InkEdit) { undo.addLast(edit); redo.clear(); toolbar?.setUndoState(true, false) }
    override fun onUndo() {
        val edit = undo.removeLastOrNull() ?: return
        edit.added.forEach { store.deleteStroke(it.id) }
        edit.removed.forEach { store.restoreStroke(bookId, pageIndex, it) }
        page = page.copy(strokes = page.strokes.filterNot { it in edit.added } + edit.removed)
        redo.addLast(edit); canvas.invalidate(); toolbar?.setUndoState(undo.isNotEmpty(), true)
    }
    override fun onRedo() {
        val edit = redo.removeLastOrNull() ?: return
        edit.removed.forEach { store.deleteStroke(it.id) }
        edit.added.forEach { store.restoreStroke(bookId, pageIndex, it) }
        page = page.copy(strokes = page.strokes.filterNot { it in edit.removed } + edit.added)
        undo.addLast(edit); canvas.invalidate(); toolbar?.setUndoState(true, redo.isNotEmpty())
    }

    override fun onToolChanged() { canvas.invalidate() }
    override fun onToolOptions(anchor: View) {
        val panel = ToolOptions(this, ui, settings, settings.tool, { toolbar?.refresh(); canvas.invalidate() }, null)
        dialog(this, settings.dark).setView(panel).setPositiveButton("Done", null).show()
    }
    override fun onInsertSpace(armed: Boolean) { toolbar?.insertArmed = false }
    override fun onSideChanged() { build(); canvas.setPage(page) }
    override fun onExpandedChanged() { toolbar?.setUndoState(undo.isNotEmpty(), redo.isNotEmpty()) }
    override fun onHide() { toolbar?.visibility = View.GONE; settings.toolbarVisible = false; penToggle?.active = false; showChrome(true) }

    private inner class SketchCanvasView : View(this@SketchActivity) {
        val paperRect = RectF()
        private val painter = InkPainter()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val darkReferenceFilter = ReaderColors(true).dimFilter
        private val bitmaps = HashMap<Long, Bitmap>()
        private var bitmapGeneration = 0
        private var live = FloatArray(3 * 128)
        private var liveCount = 0
        private var penPointer = -1
        private var erasing = false
        private val removed = ArrayList<InkStroke>()
        private var dragX = 0f
        private var dragY = 0f
        private var pinchDistance = 0f
        private var pinchWidth = 0f
        private var pinchHeight = 0f
        private var fingerDownX = 0f
        private var fingerDownY = 0f
        private var fingerMulti = false

        fun dispose() { bitmapGeneration++; bitmaps.values.forEach { it.recycle() }; bitmaps.clear() }

        fun setPage(newPage: SketchPage) {
            bitmapGeneration++
            bitmaps.values.forEach { it.recycle() }; bitmaps.clear()
            invalidate()
            val request = bitmapGeneration
            scope.launch {
                for (ref in newPage.references) {
                    val bitmap = withContext(Dispatchers.IO) {
                        val path = File(store.referencesDir, ref.path).path
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                    if (request != bitmapGeneration) { bitmap?.recycle(); return@launch }
                    if (bitmap != null) { bitmaps[ref.id] = bitmap; invalidate() }
                }
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); invalidate() }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val margin = dp(12).toFloat()
            val pageW = minOf(width - margin * 2, (height - margin * 2) * 0.707f).coerceAtLeast(1f)
            val pageH = pageW / 0.707f
            val left = (width - pageW) / 2
            val top = (height - pageH) / 2
            paperRect.set(left, top, left + pageW, top + pageH)
            SketchPaper.draw(c, paperRect, page.paper, resources.displayMetrics.density, settings.dark)
            for (ref in page.references) {
                val b = bitmaps[ref.id] ?: continue
                paint.alpha = (ref.opacity * 255).toInt().coerceIn(0, 255)
                paint.style = Paint.Style.FILL
                paint.colorFilter = if (settings.dark) darkReferenceFilter else null
                c.drawBitmap(b, null, RectF(left + ref.x * pageW, top + ref.y * pageW,
                    left + (ref.x + ref.width) * pageW, top + (ref.y + ref.height) * pageW), paint)
                paint.colorFilter = null
                if (arranging && selectedRef == ref.id) {
                    paint.alpha = 255; paint.color = ui.fg; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat()
                    c.drawRect(RectF(left + ref.x * pageW, top + ref.y * pageW,
                        left + (ref.x + ref.width) * pageW, top + (ref.y + ref.height) * pageW), paint)
                }
            }
            c.save(); c.clipRect(paperRect)
            painter.dark = settings.dark; painter.sensitivity = settings.pressure
            val placement = Placement(left, top, pageW, null)
            page.strokes.forEach { painter.draw(c, it, placement) }
            if (liveCount > 0 && !erasing) painter.drawLive(c, live, liveCount,
                settings.tool, settings.toolColor(settings.tool), dp(settings.toolWidth(settings.tool)).toFloat())
            c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (loading) return true
            val action = e.actionMasked
            val index = e.actionIndex
            val stylus = e.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
            if (action == MotionEvent.ACTION_DOWN && stylus) {
                penPointer = e.getPointerId(index)
                erasing = settings.tool == Tools.ERASER || e.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
                    (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
                liveCount = 0; removed.clear(); addPenPoint(e.x, e.y, e.getPressure(index))
                if (erasing) eraseAt(e.x, e.y, removed)
                invalidate(); return true
            }
            if (penPointer >= 0) {
                val i = e.findPointerIndex(penPointer)
                if (i < 0) return true
                when (action) {
                    MotionEvent.ACTION_MOVE -> {
                        for (h in 0 until e.historySize) {
                            val x = e.getHistoricalX(i, h); val y = e.getHistoricalY(i, h)
                            if (erasing) eraseAt(x, y, removed) else addPenPoint(x, y, e.getHistoricalPressure(i, h))
                        }
                        if (erasing) eraseAt(e.getX(i), e.getY(i), removed)
                        else addPenPoint(e.getX(i), e.getY(i), e.getPressure(i))
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        if (erasing && removed.isNotEmpty()) record(InkEdit(emptyList(), removed.toList()))
                        else if (!erasing && liveCount > 0) {
                            val points = FloatArray(liveCount * 3)
                            for (p in 0 until liveCount) {
                                points[p * 3] = (live[p * 3] - paperRect.left) / paperRect.width()
                                points[p * 3 + 1] = (live[p * 3 + 1] - paperRect.top) / paperRect.width()
                                points[p * 3 + 2] = live[p * 3 + 2]
                            }
                            addStroke(points, settings.tool)
                        }
                        penPointer = -1; liveCount = 0; invalidate()
                    }
                    MotionEvent.ACTION_CANCEL -> { penPointer = -1; liveCount = 0; invalidate() }
                }
                return true
            }
            if (!arranging) {
                when (action) {
                    MotionEvent.ACTION_DOWN -> { fingerDownX = e.x; fingerDownY = e.y; fingerMulti = false }
                    MotionEvent.ACTION_POINTER_DOWN -> fingerMulti = true
                    MotionEvent.ACTION_UP -> if (!fingerMulti) {
                        val dx = e.x - fingerDownX; val dy = e.y - fingerDownY
                        if (abs(dx) > dp(64) && abs(dx) > abs(dy) * 1.3f) turn(if (dx < 0) 1 else -1)
                        else if (abs(dx) < dp(12) && abs(dy) < dp(12)) showChrome(!chromeVisible)
                    }
                }
                return true
            }
            val ref = page.references.firstOrNull { it.id == selectedRef }
            when (action) {
                MotionEvent.ACTION_DOWN -> { dragX = e.x; dragY = e.y; return true }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 2 && ref != null) {
                    pinchDistance = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
                    pinchWidth = ref.width; pinchHeight = ref.height
                }
                MotionEvent.ACTION_MOVE -> if (ref != null) {
                    if (e.pointerCount >= 2 && pinchDistance > 0) {
                        val scale = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) / pinchDistance
                        changeReference(ref.copy(width = (pinchWidth * scale).coerceIn(0.05f, 2f), height = (pinchHeight * scale).coerceIn(0.05f, 3f)), persist = false)
                    } else {
                        changeReference(ref.copy(x = ref.x + (e.x - dragX) / paperRect.width(),
                            y = ref.y + (e.y - dragY) / paperRect.width()), persist = false)
                        dragX = e.x; dragY = e.y
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> { page.references.firstOrNull { it.id == selectedRef }?.let(store::updateReference); pinchDistance = 0f; dragX = e.x; dragY = e.y }
                MotionEvent.ACTION_UP -> {
                    page.references.firstOrNull { it.id == selectedRef }?.let(store::updateReference)
                    val x = (e.x - paperRect.left) / paperRect.width(); val y = (e.y - paperRect.top) / paperRect.width()
                    val selected = page.references.lastOrNull { x in it.x..(it.x + it.width) && y in it.y..(it.y + it.height) }
                    if (selected != null) { selectedRef = selected.id; invalidate() }
                }
                MotionEvent.ACTION_CANCEL -> page.references.firstOrNull { it.id == selectedRef }?.let(store::updateReference)
            }
            return true
        }

        private fun addPenPoint(x: Float, y: Float, pressure: Float) {
            if (!paperRect.contains(x, y)) return
            if ((liveCount + 1) * 3 > live.size) live = live.copyOf(live.size * 2)
            val j = liveCount * 3
            live[j] = x; live[j + 1] = y; live[j + 2] = pressure.coerceIn(0.05f, 1f)
            liveCount++
        }
    }

    companion object {
        const val EXTRA_BOOK = "sketchbook_id"
        private const val PICK_REFERENCE = 702
        fun intent(activity: Activity, id: Long) = Intent(activity, SketchActivity::class.java).putExtra(EXTRA_BOOK, id)
    }
}
