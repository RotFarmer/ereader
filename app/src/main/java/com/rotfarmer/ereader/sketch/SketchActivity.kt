package com.rotfarmer.ereader.sketch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.rotfarmer.ereader.App
import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkStroke
import com.rotfarmer.ereader.ink.Tools
import com.rotfarmer.ereader.ui.IconButton
import com.rotfarmer.ereader.ui.Icons
import com.rotfarmer.ereader.ui.Overlay
import com.rotfarmer.ereader.ui.PenToolbar
import com.rotfarmer.ereader.ui.Segmented
import com.rotfarmer.ereader.ui.TextButton
import com.rotfarmer.ereader.ui.ToolOptions
import com.rotfarmer.ereader.ui.UiColors
import com.rotfarmer.ereader.ui.card
import com.rotfarmer.ereader.ui.dialog
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A sketchbook: a page to draw on with the S Pen, the reader's pen toolbar, and a top bar with
 * the pages and sharing. Every stroke is saved as soon as it is drawn.
 */
class SketchActivity : Activity(), SketchView.Listener, PenToolbar.Callbacks {
    private val app by lazy { App.of(this) }
    private val settings get() = app.settings
    private val store by lazy { SketchStore.of(this) }
    // A storage error shows a message instead of closing the app.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        main.post { toast(e.message ?: "Something went wrong") }
    })

    /** Saves run in the app's scope, so leaving the screen never drops a stroke that is still queued. */
    private fun save(block: () -> Unit) {
        App.io.launch(SketchStore.io) { try { block() } catch (e: Exception) { main.post { toast(e.message ?: "Couldn't save") } } }
    }
    private val main = Handler(Looper.getMainLooper())
    private var ui = UiColors(false)

    private lateinit var bookId: String
    private var book: Sketchbook? = null
    private lateinit var root: FrameLayout
    private lateinit var view: SketchView
    private lateinit var topBar: LinearLayout
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var pageChip: TextView
    private lateinit var penBtn: IconButton
    private var toolbar: PenToolbar? = null
    private lateinit var popups: Overlay
    /** Floating bar for the selected picture, or for an import in progress. */
    private lateinit var bar: LinearLayout
    private var importing: Pair<String, () -> Unit>? = null
    private var syncing = false

    /** Undo and redo, per page, for this visit. */
    private class Op(
        val added: List<InkStroke>, val removed: List<InkStroke>,
        val itemsAdded: List<SketchItem> = emptyList(), val itemsRemoved: List<SketchItem> = emptyList(),
        /** A picture moved or resized: (before, after). */
        val moved: Pair<SketchItem, SketchItem>? = null,
    )
    private val undo = HashMap<Int, ArrayDeque<Op>>()
    private val redo = HashMap<Int, ArrayDeque<Op>>()
    /** Erased stroke id → the copy an undo brought back in its place. */
    private val restoredAs = HashMap<Long, Long>()
    private fun current(id: Long): Long { var x = id; while (true) x = restoredAs[x] ?: return x }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        ui = UiColors(settings.dark)
        setupWindow()
        build()
        scope.launch {
            val b = withContext(SketchStore.io) { store.get(bookId) }
            if (b == null) { toast("This sketchbook is no longer on this device"); finish(); return@launch }
            book = b
            view.paper = b.paper
            showPage(b.currentPage.coerceIn(0, b.pages - 1))
            updateTitle()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        syncNow()
        main.postDelayed(poll, POLL_MS)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(poll)
        main.removeCallbacks(syncSoon)
        // Pass this visit's drawing on to the shared file; finishes even after the screen closes.
        if (book?.shared == true) App.io.launch {
            withContext(SketchStore.io) {} // queued strokes first
            withContext(SketchSync.dispatcher) { SketchSync.sync(app, bookId) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        scope.cancel()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (popups.isShowing) popups.dismissTop() else finish()
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    // ------------------------------------------------------------------ layout

    private fun build() {
        root = FrameLayout(this).apply { setBackgroundColor(SketchRender.deskColor(settings.dark)) }
        view = SketchView(this, settings).apply { dark = settings.dark; listener = this@SketchActivity }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ui.bg); setPadding(dp(6), 0, dp(6), 0)
        }
        topBar.addView(IconButton(this, Icons.BACK, ui).apply { contentDescription = "Back to library"; setOnClickListener { finish() } })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        title = label(this, "", ui, 17f, serif = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        status = label(this, "", ui, 12f, ui.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setOnClickListener { showShare(it) } }
        titles.addView(title); titles.addView(status)
        topBar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(IconButton(this, Icons.CHEV_LEFT, ui).apply { contentDescription = "Previous page"; setOnClickListener { turn(-1) } })
        pageChip = label(this, "", ui, 14f, bold = true).apply {
            gravity = Gravity.CENTER; minWidth = dp(84)
            background = panelBg(this@SketchActivity, ui, 18f, 1f, ui.soft)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            contentDescription = "Go to a page"
            setOnClickListener { showPages(it) }
        }
        topBar.addView(pageChip)
        topBar.addView(IconButton(this, Icons.CHEV_RIGHT, ui).apply { contentDescription = "Next page"; setOnClickListener { turn(1) } })
        topBar.addView(View(this).apply { setBackgroundColor(ui.hairline) }, LinearLayout.LayoutParams(dp(1), dp(26)).apply { marginStart = dp(6); marginEnd = dp(6) })
        penBtn = IconButton(this, Icons.DRAW, ui).apply {
            contentDescription = "Pen toolbar"
            active = settings.sketchToolbarVisible
            setOnClickListener { settings.sketchToolbarVisible = !settings.sketchToolbarVisible; buildToolbar() }
        }
        topBar.addView(penBtn)
        topBar.addView(IconButton(this, ICON_IMPORT, ui).apply { contentDescription = "Import a picture or document"; setOnClickListener { pickImport() } })
        topBar.addView(IconButton(this, Icons.SHARE, ui).apply { contentDescription = "Share and sync"; setOnClickListener { showShare(this) } })
        topBar.addView(IconButton(this, Icons.MORE, ui).apply { contentDescription = "More"; setOnClickListener { showMore(this) } })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // touches on the bar never reach the page
            addView(topBar, LinearLayout.LayoutParams(-1, dp(56)))
            addView(View(this@SketchActivity).apply { setBackgroundColor(ui.line) }, LinearLayout.LayoutParams(-1, dp(1)))
            setBackgroundColor(ui.bg)
            // Stay clear of a camera cutout at the top.
            setOnApplyWindowInsetsListener { v, ins ->
                val top: Int; val left: Int; val right: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val cut = ins.getInsets(WindowInsets.Type.displayCutout()); top = cut.top; left = cut.left; right = cut.right
                } else {
                    val cut = ins.displayCutout; top = cut?.safeInsetTop ?: 0; left = cut?.safeInsetLeft ?: 0; right = cut?.safeInsetRight ?: 0
                }
                v.setPadding(left, top, right, 0)
                view.insetTop = top + dp(57); view.layoutPage()
                ins
            }
        }
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = panelBg(this@SketchActivity, ui, 10f); elevation = dp(3).toFloat()
            setPadding(dp(14), dp(4), dp(4), dp(4)); visibility = View.GONE
            isClickable = true
        }
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(68) })
        view.insetTop = dp(57)
        setContentView(root)
        popups = Overlay(root)
        buildToolbar()
    }

    private fun buildToolbar() {
        toolbar?.let { root.removeView(it.parent as? View ?: it) }
        toolbar = null
        penBtn.active = settings.sketchToolbarVisible
        view.insetLeft = 0; view.insetRight = 0
        if (settings.sketchToolbarVisible) {
            val tb = PenToolbar(this, ui, settings, this, writingSpace = false)
            // Scrolls when the screen is too short for every button (landscape phones).
            val holder = android.widget.ScrollView(this).apply {
                isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
                addView(tb, FrameLayout.LayoutParams(-2, -2))
            }
            root.addView(holder, 1, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or if (settings.toolbarRight) Gravity.END else Gravity.START).apply {
                marginStart = dp(8); marginEnd = dp(8); topMargin = dp(60)
            })
            toolbar = tb
            if (settings.toolbarRight) view.insetRight = dp(66) else view.insetLeft = dp(66)
            updateUndo()
        }
        view.layoutPage()
    }

    private fun updateTitle() {
        val b = book ?: return
        title.text = b.title
        pageChip.text = "${view.page + 1} / ${b.pages}"
        status.text = when {
            !b.shared -> "Only on this device · tap to share"
            syncing -> "Syncing…"
            b.syncError != null -> "Sync problem · tap for details"
            b.syncedAt > 0 -> "Synced " + DateUtils.getRelativeTimeSpanString(b.syncedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
            else -> "Shared"
        }
        status.setTextColor(if (b.syncError != null) 0xFFB03A2E.toInt() else ui.muted)
    }

    // ------------------------------------------------------------------ pages

    private fun showPage(index: Int) {
        scope.launch {
            val (list, items) = withContext(SketchStore.io) { store.strokes(bookId, index) to store.items(bookId, index) }
            view.setPage(index, list)
            view.setItems(items)
            loadPictures(items)
            if (items.isNotEmpty()) hasImports = true
            save { store.setCurrentPage(bookId, index) }
            updateTitle(); updateUndo(); refreshBar()
        }
    }

    private fun turn(step: Int) {
        val b = book ?: return
        val next = view.page + step
        if (next < 0) return
        if (next >= b.pages) { toast("This is the last page. Add pages from the ⋮ menu."); return }
        popups.dismissAll()
        showPage(next)
    }

    override fun onTurn(step: Int) = turn(step)

    // ------------------------------------------------------------------ drawing

    override fun onStrokeAdded(stroke: InkStroke) {
        val page = view.page
        record(page, Op(listOf(stroke), emptyList()))
        save { store.addStrokes(bookId, page, listOf(stroke)) }
        syncLater()
    }

    override fun onStrokesErased(strokes: List<InkStroke>) {
        val page = view.page
        record(page, Op(emptyList(), strokes))
        save { store.eraseStrokes(bookId, strokes.map { it.id }) }
        syncLater()
    }

    private fun record(page: Int, op: Op) {
        undo.getOrPut(page) { ArrayDeque() }.addLast(op)
        redo[page]?.clear()
        updateUndo()
    }

    private fun updateUndo() {
        toolbar?.setUndoState(undo[view.page]?.isNotEmpty() == true, redo[view.page]?.isNotEmpty() == true)
    }

    /**
     * Undoes [op]: erases what it added and draws again what it erased (as new strokes, since an
     * erased stroke stays erased on every device). Returns the operation that undoes this.
     */
    private fun invert(page: Int, op: Op): Op {
        // Strokes restored by an earlier undo have new ids; follow them so older steps still apply.
        val gone = op.added.map { current(it.id) }.toSet()
        val again = op.removed.map {
            InkStroke(SketchStore.newStrokeId(), 0L, Anchor(Anchor.PDF, page.toLong(), 0), it.tool, it.color, it.width, it.pts, it.created)
                .also { copy -> restoredAs[current(it.id)] = copy.id }
        }
        if (gone.isNotEmpty() || again.isNotEmpty()) view.setStrokes(view.pageStrokes.filter { it.id !in gone } + again)
        // Pictures: the same, plus moving one back to where it was.
        val now = System.currentTimeMillis()
        val itemsGone = op.itemsAdded.map { current(it.id) }.toSet()
        val itemsAgain = op.itemsRemoved.map { it.copy(id = SketchStore.newStrokeId(), time = now, deleted = 0L).also { copy -> restoredAs[current(it.id)] = copy.id } }
        val moved = op.moved?.let { (before, after) -> val id = current(after.id); after.copy(id = id) to before.copy(id = id) }
        if (itemsGone.isNotEmpty() || itemsAgain.isNotEmpty() || moved != null) {
            val list = view.pageItems.filter { it.id !in itemsGone }.map { if (moved != null && it.id == moved.second.id) moved.second else it } + itemsAgain
            view.setItems(list.sortedWith(compareByDescending<SketchItem> { it.kind }.thenBy { it.created }))
            loadPictures(itemsAgain + listOfNotNull(moved?.second))
            refreshBar()
        }
        save {
            store.eraseStrokes(bookId, gone)
            store.addStrokes(bookId, page, again)
            store.removeItems(bookId, itemsGone)
            store.addItems(bookId, itemsAgain)
            moved?.let { store.moveItem(bookId, it.second) }
        }
        syncLater()
        return Op(again, op.added, itemsAgain, op.itemsAdded, moved)
    }

    override fun onUndo() {
        val page = view.page
        val op = undo[page]?.removeLastOrNull() ?: return
        redo.getOrPut(page) { ArrayDeque() }.addLast(invert(page, op))
        updateUndo()
    }

    override fun onRedo() {
        val page = view.page
        val op = redo[page]?.removeLastOrNull() ?: return
        undo.getOrPut(page) { ArrayDeque() }.addLast(invert(page, op))
        updateUndo()
    }

    // ------------------------------------------------------------------ pictures and documents

    private val pictureCache = object : android.util.LruCache<String, android.graphics.Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }
    private val pictureJobs = HashSet<String>()

    /** Size a placed item should be loaded at: half again its size on screen, for zooming in. */
    private fun pictureKey(it: SketchItem): Pair<String, Int> {
        val px = (((it.w * view.pageWidthPx * 1.5f).toInt() + 127) / 128 * 128).coerceIn(128, 2000)
        return "${it.asset}:${it.sourcePage}:$px" to px
    }

    private fun loadPictures(items: List<SketchItem>) {
        for (item in items) {
            val (key, px) = pictureKey(item)
            val cached = pictureCache.get(key)
            if (cached != null) { view.setItemBitmap(item.id, cached); continue }
            if (!pictureJobs.add(key)) continue
            scope.launch {
                val bmp = try { withContext(Dispatchers.IO) { SketchAssets.render(store, item, px) } } finally { pictureJobs.remove(key) }
                if (bmp != null) {
                    pictureCache.put(key, bmp)
                    view.pageItems.filter { pictureKey(it).first == key }.forEach { view.setItemBitmap(it.id, bmp) }
                }
            }
        }
    }

    override fun onItemSelected(item: SketchItem?) = refreshBar()

    override fun onItemChanged(before: SketchItem, after: SketchItem) {
        val page = view.page
        record(page, Op(emptyList(), emptyList(), moved = before to after))
        save { store.moveItem(bookId, after) }
        if (after.w > before.w * 1.3f) loadPictures(listOf(after))
        syncLater()
    }

    /** The floating bar: an import in progress, or what can be done with the selected picture. */
    private fun refreshBar() {
        bar.removeAllViews()
        fun text(t: String) = bar.addView(label(this, t, ui, 13.5f).apply { setPadding(0, 0, dp(10), 0); maxLines = 1 })
        fun action(t: String, filled: Boolean = false, f: () -> Unit) = bar.addView(TextButton(this, t, ui, filled).apply { minHeight = dp(36); setOnClickListener { f() } },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        val imp = importing
        val sel = view.selected
        when {
            imp != null -> { text(imp.first); action("Cancel") { imp.second() } }
            sel != null && sel.kind == SketchItem.DOCUMENT -> {
                text("Document page")
                action("Remove") { view.selected?.let(::removeItem) }
                action("Remove whole document") { view.selected?.let(::removeDocument) }
                action("Done", filled = true) { view.select(null); refreshBar() }
            }
            sel != null -> {
                text("Drag to move · drag the corner to resize")
                action("Remove") { view.selected?.let(::removeItem) }
                action("Done", filled = true) { view.select(null); refreshBar() }
            }
            else -> { bar.visibility = View.GONE; return }
        }
        bar.visibility = View.VISIBLE
    }

    private fun removeItem(item: SketchItem) {
        val page = view.page
        view.select(null)
        view.setItems(view.pageItems.filter { it.id != item.id })
        record(page, Op(emptyList(), emptyList(), itemsRemoved = listOf(item)))
        save { store.removeItems(bookId, listOf(item.id)) }
        syncLater(); refreshBar()
    }

    private fun removeDocument(item: SketchItem) {
        dialog(this, settings.dark).setMessage("Remove every page of this document from the sketchbook? Your drawing stays.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                view.select(null)
                scope.launch {
                    val n = withContext(SketchStore.io) {
                        val all = store.itemsWithAsset(bookId, item.asset)
                        store.removeItems(bookId, all.map { it.id }); all.size
                    }
                    view.setItems(view.pageItems.filter { it.asset != item.asset })
                    refreshBar(); syncLater()
                    toast("Removed $n ${if (n == 1) "page" else "pages"}")
                }
            }.show()
    }

    private fun pickImport() {
        pick(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
            "image/*", "application/pdf", "application/epub+zip", "application/x-mobipocket-ebook",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain", "text/html", "application/octet-stream",
        )), REQ_IMPORT)
    }

    private fun importFile(uri: android.net.Uri) {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val stop = { cancelled.set(true); importing = null; refreshBar() }
        importing = "Importing…" to stop; refreshBar()
        scope.launch {
            val prepared = try {
                withContext(Dispatchers.IO) {
                    SketchAssets.prepare(app, uri, { cancelled.get() }) { msg -> main.post { if (importing != null && !cancelled.get()) { importing = msg to stop; refreshBar() } } }
                }
            } catch (e: CancellationException) {
                if (!cancelled.get()) throw e
                null
            } catch (e: Exception) {
                if (!cancelled.get()) toast(e.message ?: "Couldn't import that file")
                null
            }
            // A newer import may have started after this one was cancelled; leave its bar alone.
            if (importing?.second === stop) importing = null
            refreshBar()
            if (cancelled.get()) return@launch
            when (prepared) {
                is SketchAssets.Prepared.Picture -> placePicture(prepared)
                is SketchAssets.Prepared.Document -> chooseDocumentPages(prepared)
                null -> {}
            }
        }
    }

    private fun placePicture(p: SketchAssets.Prepared.Picture) {
        hasImports = true
        val page = view.page
        val r = SketchAssets.fitPicture(p.width, p.height)
        val now = System.currentTimeMillis()
        val item = SketchItem(SketchStore.newStrokeId(), page, SketchItem.PICTURE, p.asset, 0, r.left, r.top, r.width(), r.height(), now, now)
        view.setItems(view.pageItems + item)
        loadPictures(listOf(item))
        record(page, Op(emptyList(), emptyList(), itemsAdded = listOf(item)))
        save { store.addItems(bookId, listOf(item)) }
        syncLater()
        view.select(item); refreshBar()
    }

    private fun chooseDocumentPages(d: SketchAssets.Prepared.Document) {
        popups.dismissAll()
        val n = d.pageSizes.size
        val c = card(this, ui)
        c.addView(label(this, d.title, ui, 19f, bold = true, serif = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        c.addView(label(this, "$n ${if (n == 1) "page" else "pages"}. Each one becomes a sketchbook page you can draw on.", ui, 13.5f, ui.muted))
        val input = EditText(this).apply {
            setSingleLine(); textSize = 16f; setText(if (n == 1) "1" else "1-${minOf(n, 100)}"); hint = "e.g. 1-5, 8"
            setTextColor(ui.fg); setHintTextColor(ui.muted)
            background = panelBg(this@SketchActivity, ui, 8f, 1f, ui.soft); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        if (n > 1) {
            c.addView(sectionTitle(this, "Pages to import", ui))
            c.addView(input)
        }
        c.addView(label(this, "They go on empty pages, starting here if there's room, so nothing you've drawn moves.", ui, 12.5f, ui.muted).apply { setPadding(0, dp(8), 0, 0) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(12), 0, 0) }
        row.addView(TextButton(this, "Cancel", ui).apply { setOnClickListener { popups.dismissAll() } }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        row.addView(TextButton(this, "Import", ui, filled = true).apply {
            setOnClickListener {
                val pages = try { parsePages(input.text.toString(), n) } catch (e: IllegalArgumentException) { toast(e.message ?: "Check the page numbers"); return@setOnClickListener }
                popups.dismissAll()
                placeDocument(d, pages)
            }
        })
        c.addView(row)
        popups.show(c, null, maxWidthDp = 440)
    }

    /** "1-5, 8" → zero-based page numbers, in order. "all" means every page. */
    private fun parsePages(text: String, count: Int): List<Int> {
        if (text.trim().equals("all", ignoreCase = true)) return (0 until count).toList()
        val out = sortedSetOf<Int>()
        for (part in text.split(',')) {
            val t = part.trim()
            if (t.isEmpty()) continue
            val ends = t.split('-', limit = 2).map { it.trim().toIntOrNull() ?: throw IllegalArgumentException("Use page numbers like 1-5, 8") }
            val a = ends[0]; val b = ends.getOrElse(1) { a }
            if (a < 1 || b > count || a > b) throw IllegalArgumentException("Choose pages from 1 to $count")
            for (i in a..b) out += i - 1
        }
        if (out.isEmpty()) throw IllegalArgumentException("Enter the pages to import")
        return out.toList()
    }

    private fun placeDocument(d: SketchAssets.Prepared.Document, pages: List<Int>) {
        hasImports = true
        val here = view.page
        scope.launch {
            val start = withContext(SketchStore.io) {
                val b = store.get(bookId) ?: return@withContext null
                val k = pages.size
                var s = here
                if (s + k > b.pages || !store.pagesFree(bookId, s, k)) s = maxOf(here, (store.usedPages(bookId).maxOrNull() ?: -1) + 1)
                if (s + k > SketchFile.MAX_PAGES) return@withContext -1
                if (s + k > b.pages) store.addPages(bookId, s + k - b.pages)
                val now = System.currentTimeMillis()
                store.addItems(bookId, pages.mapIndexed { j, src ->
                    val r = SketchAssets.fitPage(d.pageSizes[src])
                    SketchItem(SketchStore.newStrokeId(), s + j, SketchItem.DOCUMENT, d.asset, src, r.left, r.top, r.width(), r.height(), now + j, now)
                })
                s
            } ?: return@launch
            if (start < 0) { toast("Not enough room: a sketchbook holds up to ${SketchFile.MAX_PAGES} pages"); return@launch }
            book = withContext(SketchStore.io) { store.get(bookId) }
            showPage(start)
            syncLater()
            val k = pages.size
            toast(if (k == 1) "Placed on page ${start + 1}" else "Placed on pages ${start + 1}–${start + k}")
        }
    }

    private fun clearPage() {
        val all = view.pageStrokes.toList()
        if (all.isEmpty()) return
        view.setStrokes(emptyList())
        onStrokesErased(all)
        toast("Ink erased. Undo brings it back.")
    }

    override fun onToolChanged() { view.invalidate() }

    override fun onToolOptions(anchor: View) {
        popups.dismissAll()
        val tool = settings.tool
        val opts = ToolOptions(this, ui, settings, tool, onChanged = {
            toolbar?.refresh()
            view.refreshPen()
        }, onClearPage = if (tool == Tools.ERASER) ({ popups.dismissAll(); clearPage() }) else null)
        val r = anchorOf(anchor)
        val side = if (settings.toolbarRight) RectF(r.left - dp(8), r.top, r.left - dp(8), r.bottom) else RectF(r.right + dp(8), r.top, r.right + dp(8), r.bottom)
        popups.show(opts, side, below = true, maxWidthDp = 320, alignRight = settings.toolbarRight)
    }

    override fun onInsertSpace(armed: Boolean) {}
    override fun onSideChanged() = buildToolbar()
    override fun onExpandedChanged() = updateUndo()
    override fun onHide() { settings.sketchToolbarVisible = false; buildToolbar() }

    // ------------------------------------------------------------------ sync

    private val syncSoon = Runnable { syncNow() }
    private val poll = object : Runnable {
        override fun run() {
            if (book?.shared == true && !syncing) scope.launch {
                val due = withContext(SketchSync.dispatcher) { SketchSync.due(app, bookId) }
                if (due) syncNow()
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /** Sync a few seconds after drawing stops, rather than after every stroke. */
    private fun syncLater() {
        if (book?.shared != true) return
        main.removeCallbacks(syncSoon)
        // Imported pictures and documents travel in the shared file, so wait longer between saves.
        main.postDelayed(syncSoon, if (hasImports) 20_000L else 4000L)
    }

    private var hasImports = false

    private fun syncNow(force: Boolean = false) {
        val b = book
        if (b != null && !b.shared) return
        scope.launch {
            // Let strokes still being saved reach the database first.
            if (withContext(SketchStore.io) { store.get(bookId)?.shared } != true) return@launch
            syncing = true; updateTitle()
            val result = try { withContext(SketchSync.dispatcher) { SketchSync.sync(app, bookId, force) } } finally { syncing = false }
            afterChanges(result?.changedPages.orEmpty(), result?.metaChanged == true)
        }
    }

    /** Shows what arrived from another device. */
    private suspend fun afterChanges(changedPages: Set<Int>, metaChanged: Boolean) {
        val b = withContext(SketchStore.io) { store.get(bookId) } ?: return
        book = b
        if (metaChanged) view.paper = b.paper
        if (view.page in changedPages) {
            val page = view.page
            val since = System.currentTimeMillis()
            val list = withContext(SketchStore.io) { store.strokes(bookId, page) }
            val items = withContext(SketchStore.io) { store.items(bookId, page) }
            if (view.page == page) {
                // Keep strokes drawn while the page was loading; they are saved right after.
                val ids = list.mapTo(HashSet()) { it.id }
                view.setStrokes(list + view.pageStrokes.filter { it.created >= since && it.id !in ids })
                if (view.selected == null) { view.setItems(items); loadPictures(items) }
            }
        }
        updateTitle()
    }

    // ------------------------------------------------------------------ popups

    private fun anchorOf(v: View): RectF {
        val loc = IntArray(2); v.getLocationInWindow(loc)
        return RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + v.width).toFloat(), (loc[1] + v.height).toFloat())
    }

    private fun showPages(anchor: View) {
        popups.dismissAll()
        val b = book ?: return
        val c = card(this, ui)
        c.addView(label(this, "Pages", ui, 19f, bold = true, serif = true))
        c.addView(sectionTitle(this, "Go to page", ui))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); hint = "1 – ${b.pages}"; textSize = 16f
            setTextColor(ui.fg); setHintTextColor(ui.muted)
            background = panelBg(this@SketchActivity, ui, 8f, 1f, ui.soft); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        row.addView(TextButton(this, "Go", ui, filled = true).apply {
            setOnClickListener {
                val n = input.text.toString().toIntOrNull()
                if (n == null || n !in 1..b.pages) toast("Choose a page from 1 to ${b.pages}") else { popups.dismissAll(); showPage(n - 1) }
            }
        })
        c.addView(row)
        c.addView(sectionTitle(this, "Pages with drawings", ui))
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT; columnWidth = dp(56); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(6); verticalSpacing = dp(6)
            selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }
        val empty = label(this, "None yet.", ui, 14f, ui.muted)
        c.addView(empty)
        c.addView(grid, LinearLayout.LayoutParams(-1, dp(180)))
        grid.visibility = View.GONE
        popups.show(c, anchorOf(anchor), below = true, maxWidthDp = 380)
        scope.launch {
            val used = withContext(SketchStore.io) { store.usedPages(bookId) }
            if (used.isEmpty()) return@launch
            empty.visibility = View.GONE; grid.visibility = View.VISIBLE
            grid.adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = used.size
                override fun getItem(position: Int) = used[position]
                override fun getItemId(position: Int) = used[position].toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                    TextButton(this@SketchActivity, "${used[position] + 1}", ui).apply {
                        active = used[position] == view.page
                        isClickable = false; isFocusable = false
                    }
            }
            grid.setOnItemClickListener { _, _, pos, _ -> popups.dismissAll(); showPage(used[pos]) }
        }
    }

    private fun showMore(anchor: View) {
        popups.dismissAll()
        val b = book ?: return
        val c = card(this, ui)
        c.addView(label(this, "Sketchbook", ui, 19f, bold = true, serif = true))
        c.addView(sectionTitle(this, "Paper", ui))
        c.addView(Segmented(this, listOf("Blank", "Lined", "Grid", "Dots"), b.paper, ui) { p ->
            view.paper = p
            scope.launch {
                withContext(SketchStore.io) { store.setPaper(bookId, p) }
                book = withContext(SketchStore.io) { store.get(bookId) }; syncLater()
            }
        })
        c.addView(sectionTitle(this, "Pages", ui))
        val pagesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pagesRow.addView(TextButton(this, "Add 50 pages", ui).apply {
            setOnClickListener {
                if (b.pages >= SketchFile.MAX_PAGES) { toast("This sketchbook is full"); return@setOnClickListener }
                scope.launch {
                    withContext(SketchStore.io) { store.addPages(bookId, 50) }
                    book = withContext(SketchStore.io) { store.get(bookId) }; updateTitle(); syncLater()
                    toast("Added 50 pages")
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        pagesRow.addView(TextButton(this, "Erase all ink", ui).apply { setOnClickListener { popups.dismissAll(); clearPage() } }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(pagesRow)
        c.addView(sectionTitle(this, "Pen & touch", ui))
        c.addView(toggleRow(this, "Draw with finger", "Two fingers zoom and move the page", settings.fingerDraw, ui) { settings.fingerDraw = it })
        c.addView(label(this, "S Pen pressure", ui, 15f).apply { setPadding(0, dp(6), 0, 0) })
        c.addView(slider(this, 100, (settings.pressure * 100).toInt(), ui) { v, done ->
            if (done) { settings.pressure = v / 100f; view.refreshPen(); toolbar?.refresh() }
        })
        c.addView(TextButton(this, "Rename sketchbook…", ui).apply { setOnClickListener { popups.dismissAll(); rename() } },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        c.addView(label(this, "The S Pen draws; hold its button to erase. Swipe with a finger to turn the page, pinch to zoom.", ui, 12.5f, ui.muted).apply { setPadding(0, dp(10), 0, 0) })
        popups.show(c, anchorOf(anchor), below = true, maxWidthDp = 400, alignRight = true)
    }

    private fun rename() {
        val b = book ?: return
        val input = EditText(this).apply { setText(b.title); setSingleLine(); selectAll(); setTextColor(ui.fg) }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        dialog(this, settings.dark).setTitle("Rename sketchbook").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) scope.launch {
                    withContext(SketchStore.io) { store.rename(bookId, name) }
                    book = withContext(SketchStore.io) { store.get(bookId) }; updateTitle(); syncLater()
                }
            }.show()
    }

    // ------------------------------------------------------------------ sharing

    private fun showShare(anchor: View) {
        popups.dismissAll()
        val b = book ?: return
        val c = card(this, ui)
        c.addView(label(this, "Share", ui, 19f, bold = true, serif = true))
        c.addView(sectionTitle(this, "Keep in sync on your other devices", ui))
        if (!b.shared) {
            c.addView(label(this, "Save this sketchbook as a file in Google Drive (or another cloud folder). On your other device, open E-Reader → Library → Sketchbooks → + → Open shared sketchbook, and pick the same file. Drawing on either device then shows up on both.", ui, 13.5f, ui.muted))
            c.addView(TextButton(this, "Save to a shared folder…", ui, filled = true).apply {
                setOnClickListener {
                    popups.dismissAll()
                    pick(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_TITLE, "${SketchSync.safeName(b.title)}.sketchbook"), REQ_LINK)
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        } else {
            c.addView(label(this, "Synced with “${b.syncName ?: "shared file"}”.", ui, 14.5f))
            if (b.syncError != null) c.addView(label(this, b.syncError, ui, 13f, 0xFFB03A2E.toInt()).apply { setPadding(0, dp(4), 0, 0) })
            else c.addView(label(this, "Changes are saved there a few seconds after you stop drawing, and changes from your other devices appear here.", ui, 13f, ui.muted).apply { setPadding(0, dp(4), 0, 0) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            row.addView(TextButton(this, "Sync now", ui, filled = true).apply { setOnClickListener { popups.dismissAll(); syncNow(force = true) } },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            row.addView(TextButton(this, "Stop syncing", ui).apply {
                setOnClickListener {
                    popups.dismissAll()
                    scope.launch {
                        withContext(SketchStore.io) { store.unlink(bookId) }
                        book = withContext(SketchStore.io) { store.get(bookId) }; updateTitle()
                        toast("This device stopped syncing. The shared file is still there.")
                    }
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            c.addView(row)
        }
        c.addView(sectionTitle(this, "Send a copy", ui))
        c.addView(label(this, "For Quick Share, email or messages. Opening the copy in E-Reader on another device adds the sketchbook there, or merges it into the one already there.", ui, 13.5f, ui.muted))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        row.addView(TextButton(this, "Send sketchbook", ui).apply { setOnClickListener { popups.dismissAll(); sendCopy() } },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(TextButton(this, "Send this page", ui).apply { setOnClickListener { popups.dismissAll(); sendPage() } },
            LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(row)
        c.addView(TextButton(this, "Save as PDF…", ui).apply {
            setOnClickListener {
                popups.dismissAll()
                pick(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
                    .putExtra(Intent.EXTRA_TITLE, "${SketchSync.safeName(b.title)}.pdf"), REQ_PDF)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        popups.show(c, anchorOf(anchor), below = true, maxWidthDp = 440, alignRight = true)
    }

    private fun sendCopy() {
        val b = book ?: return
        scope.launch {
            try {
                val f = withContext(SketchStore.io) { SketchSync.exportCopy(app, bookId) }
                startActivity(SketchSync.shareIntent(this@SketchActivity, f, "application/zip", b.title))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Couldn't make a copy") }
        }
    }

    private fun sendPage() {
        val b = book ?: return
        val strokes = view.pageStrokes.toList()
        val items = view.pageItems.toList()
        val page = view.page
        scope.launch {
            try {
                val f = withContext(Dispatchers.Default) {
                    val bmp = SketchRender.bitmap(1600, b.paper, strokes, false, settings.pressure, items, store)
                    val file = File(SharedFiles.dir(app), "${SketchSync.safeName(b.title)} p${page + 1}.png")
                    try { file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } } finally { bmp.recycle() }
                    file
                }
                startActivity(SketchSync.shareIntent(this@SketchActivity, f, "image/png", "${b.title}, page ${page + 1}"))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Couldn't make a picture") }
        }
    }

    private fun pick(intent: Intent, request: Int) {
        try {
            @Suppress("DEPRECATION") startActivityForResult(intent, request)
        } catch (e: Exception) { toast("No file picker available") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_LINK -> scope.launch {
                syncing = true; updateTitle()
                try {
                    withContext(SketchStore.io) {} // pending strokes first
                    withContext(SketchSync.dispatcher) { SketchSync.linkNewFile(app, bookId, uri) }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Couldn't use that file") }
                finally { syncing = false }
                afterChanges(emptySet(), false)
                if (book?.syncError == null) toast("Saved. Open the same file on your other device to share this sketchbook.")
            }
            REQ_IMPORT -> importFile(uri)
            REQ_PDF -> scope.launch {
                try {
                    toast("Saving the PDF…")
                    // Off the database thread: drawing stays responsive while a long export runs.
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri, "w")?.use { SketchRender.pdf(store, bookId, it, settings.pressure) } ?: error("Couldn't write the file")
                    }
                    toast("PDF saved")
                } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Couldn't save the PDF") }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_ID = "sketchbook"
        private const val REQ_LINK = 61
        private const val REQ_PDF = 62
        private const val REQ_IMPORT = 63
        private const val ICON_IMPORT = "\ue43e" // add_photo_alternate
        private const val POLL_MS = 20_000L

        fun intent(context: Context, id: String) = Intent(context, SketchActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
