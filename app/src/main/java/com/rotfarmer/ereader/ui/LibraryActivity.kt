package com.rotfarmer.ereader.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.rotfarmer.ereader.App
import com.rotfarmer.ereader.data.Book
import com.rotfarmer.ereader.data.BookFormat
import com.rotfarmer.ereader.data.Covers
import com.rotfarmer.ereader.data.Db
import com.rotfarmer.ereader.data.Excerpt
import com.rotfarmer.ereader.data.ImportException
import com.rotfarmer.ereader.data.Importer
import com.rotfarmer.ereader.render.ReaderColors
import com.rotfarmer.ereader.sketch.SketchActivity
import com.rotfarmer.ereader.sketch.SketchStore
import com.rotfarmer.ereader.sketch.Sketchbook
import com.rotfarmer.ereader.sketch.SketchPage
import com.rotfarmer.ereader.sketch.SketchPaper
import com.rotfarmer.ereader.ink.InkPainter
import com.rotfarmer.ereader.ink.Placement
import com.rotfarmer.ereader.update.UpdateException
import com.rotfarmer.ereader.update.Updater
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.widget.EditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class LibraryActivity : Activity() {
    private data class LibraryData(
        val books: List<Book>, val excerpts: List<Excerpt>, val sketches: List<Sketchbook>,
        val previews: Map<Long, SketchPage>
    )
    private lateinit var app: App
    private val settings get() = app.settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ui = UiColors(false)

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private var status: TextView? = null
    private var books: List<Book> = emptyList()
    private var excerpts: List<Excerpt> = emptyList()
    private val sketches by lazy { SketchStore(this) }
    private var sketchbooks: List<Sketchbook> = emptyList()
    private var sketchPreviews: Map<Long, SketchPage> = emptyMap()
    private var sortMode = 0
    private val bookAdapter = BookAdapter()
    private val excerptAdapter = ExcerptAdapter()
    private val sketchAdapter = SketchAdapter()

    private val covers = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val coverExec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        sortMode = settings.prefs.getInt("sortMode", 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        build()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        reload()
        val last = settings.prefs.getLong("lastUpdateCheck", 0)
        if (System.currentTimeMillis() - last > 6 * 3600_000L) checkForUpdates(manual = false)
        else pendingUpdate?.let { showUpdateBanner(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        coverExec.shutdownNow()
    }

    // ------------------------------------------------------------------ UI

    @Suppress("DEPRECATION")
    private fun build() {
        ui = UiColors(settings.dark)
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.setSystemBarsAppearance(
                if (settings.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or (if (settings.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderColors(settings.dark).paper)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val s = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(s.left, s.top, s.right, s.bottom)
            } else {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(8), dp(4))
        }
        header.addView(label(this, "Library", ui, 28f, bold = true, serif = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(IconButton(this, Icons.SORT, ui).apply {
            contentDescription = "Sort"
            setOnClickListener {
                sortMode = (sortMode + 1) % 3
                settings.prefs.edit().putInt("sortMode", sortMode).apply()
                Toast.makeText(this@LibraryActivity, listOf("Most recent", "Title", "Author")[sortMode], Toast.LENGTH_SHORT).show()
                reload()
            }
        })
        header.addView(IconButton(this, if (settings.dark) Icons.LIGHT else Icons.DARK, ui).apply {
            contentDescription = "Light or dark"
            setOnClickListener {
                settings.dark = !settings.dark
                build(); reload()
            }
        })
        header.addView(IconButton(this, Icons.ADD, ui).apply {
            contentDescription = "Add books or sketchbooks"
            setOnClickListener { if (settings.libraryTab == 2) createSketchbook() else openPicker() }
        })
        header.addView(IconButton(this, Icons.MORE, ui).apply {
            contentDescription = "More"
            setOnClickListener {
                val items = arrayListOf("Check for updates", "Update settings", "About")
                if (com.rotfarmer.ereader.CrashActivity.hasReport(this@LibraryActivity)) items += "Last crash report"
                dialog(this@LibraryActivity, settings.dark)
                    .setItems(items.toTypedArray()) { _, i ->
                        when (i) {
                            0 -> checkForUpdates(manual = true)
                            1 -> tokenDialog()
                            2 -> about()
                            3 -> startActivity(Intent(this@LibraryActivity, com.rotfarmer.ereader.CrashActivity::class.java))
                        }
                    }.show()
            }
        })
        root.addView(header)

        val tabs = Segmented(this, listOf("Books", "Excerpts", "Sketchbooks"), settings.libraryTab.coerceIn(0, 2), ui) {
            settings.libraryTab = it; showTab(); if (it == 2) reload()
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(20); rightMargin = dp(20); topMargin = dp(6); bottomMargin = dp(10)
        })
        root.addView(View(this).apply { setBackgroundColor(ui.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        status = label(this, "", ui, 13f, ui.muted).apply {
            visibility = View.GONE
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setBackgroundColor(ui.soft)
        }
        root.addView(status)
        pendingUpdate?.let { showUpdateBanner(it) }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showTab()
    }

    private fun showTab() {
        content.removeAllViews()
        if (settings.libraryTab == 0) {
            if (books.isEmpty()) {
                content.addView(emptyView("Your library is empty", "Tap + to add EPUB, MOBI, PDF or Word (.docx) files.\nYou can also open them from your file manager with E-Reader."))
                return
            }
            val grid = GridView(this).apply {
                numColumns = GridView.AUTO_FIT
                columnWidth = dp(150)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                horizontalSpacing = dp(14)
                verticalSpacing = dp(18)
                setPadding(dp(16), dp(14), dp(16), dp(24))
                clipToPadding = false
                selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                adapter = bookAdapter
                setOnItemClickListener { _, _, pos, _ -> open(books[pos]) }
                setOnItemLongClickListener { _, _, pos, _ -> bookMenu(books[pos]); true }
            }
            content.addView(grid)
        } else if (settings.libraryTab == 1) {
            if (excerpts.isEmpty()) {
                content.addView(emptyView("No excerpts yet", "While reading, long-press text (or a part of a PDF page) and choose Excerpt. Your excerpts from every book appear here."))
                return
            }
            val list = ListView(this).apply {
                divider = android.graphics.drawable.ColorDrawable(ui.hairline)
                dividerHeight = 1
                selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                adapter = excerptAdapter
                setPadding(dp(8), 0, dp(8), dp(24))
                clipToPadding = false
                setOnItemClickListener { _, _, pos, _ -> openExcerpt(excerpts[pos]) }
                setOnItemLongClickListener { _, _, pos, _ -> excerptMenu(excerpts[pos]); true }
            }
            content.addView(list)
        } else {
            showSketchbooks()
        }
    }

    private fun showSketchbooks() {
        if (sketchbooks.isEmpty()) {
            content.addView(emptyView("No sketchbooks yet", "Tap + to create a sketchbook with 350 pages."))
            return
        }
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(150)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(14)
            verticalSpacing = dp(18)
            setPadding(dp(16), dp(14), dp(16), dp(24))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = sketchAdapter
            setOnItemClickListener { _, _, pos, _ -> startActivity(SketchActivity.intent(this@LibraryActivity, sketchbooks[pos].id)) }
            setOnItemLongClickListener { _, _, pos, _ -> sketchMenu(sketchbooks[pos]); true }
        }
        content.addView(grid)
    }

    private fun createSketchbook() {
        val input = EditText(this).apply { setSingleLine(); hint = "Sketchbook name" }
        dialog(this, settings.dark).setTitle("New sketchbook · 350 pages").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Create") { _, _ ->
                val title = input.text.toString().trim().ifEmpty { "Untitled sketchbook" }
                scope.launch {
                    val id = withContext(Db.io) { sketches.create(title) }
                    reload()
                    startActivity(SketchActivity.intent(this@LibraryActivity, id))
                }
            }.show()
    }

    private fun sketchMenu(sketch: Sketchbook) {
        dialog(this, settings.dark).setTitle(sketch.title)
            .setItems(arrayOf("Open", "Rename", "Delete")) { _, choice ->
                when (choice) {
                    0 -> startActivity(SketchActivity.intent(this, sketch.id))
                    1 -> {
                        val input = EditText(this).apply { setSingleLine(); setText(sketch.title); selectAll() }
                        dialog(this, settings.dark).setTitle("Rename sketchbook").setView(input)
                            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                                val name = input.text.toString().trim()
                                if (name.isNotEmpty()) scope.launch { withContext(Db.io) { sketches.rename(sketch.id, name) }; reload() }
                            }.show()
                    }
                    2 -> dialog(this, settings.dark).setMessage("Delete “${sketch.title}” and all its pages, drawings and references?")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                            scope.launch { withContext(Db.io) { sketches.delete(sketch.id) }; reload() }
                        }.show()
                }
            }.show()
    }

    private fun emptyView(title: String, body: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(40), dp(40), dp(40), dp(80))
        addView(label(this@LibraryActivity, title, ui, 20f, bold = true, serif = true).apply { gravity = Gravity.CENTER })
        addView(label(this@LibraryActivity, body, ui, 15f, ui.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(20)) })
        if (settings.libraryTab == 0) addView(TextButton(this@LibraryActivity, "Add books", ui, filled = true).apply { setOnClickListener { openPicker() } })
        if (settings.libraryTab == 2) addView(TextButton(this@LibraryActivity, "New sketchbook", ui, filled = true).apply { setOnClickListener { createSketchbook() } })
    }

    private fun reload() {
        scope.launch {
            val (b, e, s, previews) = withContext(Db.io) {
                val b = app.db.books(); val e = app.db.allExcerpts(); val s = sketches.list()
                val p = if (settings.libraryTab == 2) s.associate { sketch ->
                    sketch.id to runCatching { sketches.page(sketch.id, sketch.currentPage) }
                        .getOrDefault(SketchPage(0, emptyList(), emptyList()))
                } else emptyMap()
                LibraryData(b, e, s, p)
            }
            books = when (sortMode) {
                1 -> b.sortedBy { it.title.lowercase() }
                2 -> b.sortedWith(compareBy({ it.author.lowercase() }, { it.title.lowercase() }))
                else -> b
            }
            excerpts = e
            sketchbooks = s
            sketchPreviews = previews
            showTab()
            bookAdapter.notifyDataSetChanged()
            excerptAdapter.notifyDataSetChanged()
            sketchAdapter.notifyDataSetChanged()
            checkCovers()
        }
    }

    // ------------------------------------------------------------------ books

    private fun open(b: Book) {
        startActivity(ReaderActivity.intent(this, b.id))
    }

    private fun openExcerpt(x: Excerpt) {
        val i = ReaderActivity.intent(this, x.bookId)
            .putExtra(ReaderActivity.EXTRA_START, intArrayOf(x.start.block, x.start.offset))
            .putExtra(ReaderActivity.EXTRA_END, intArrayOf(x.end.block, x.end.offset))
        x.region?.let { i.putExtra(ReaderActivity.EXTRA_REGION, floatArrayOf(it.left, it.top, it.right, it.bottom)) }
        startActivity(i)
    }

    private fun bookMenu(b: Book) {
        dialog(this, settings.dark).setTitle(b.title)
            .setItems(arrayOf("Open", "Change cover…", "Remove from library")) { _, i ->
                when (i) {
                    0 -> open(b)
                    1 -> changeCover(b)
                    2 -> dialog(this, settings.dark)
                        .setMessage("Remove “${b.title}” and all its highlights, notes and drawings from this device?")
                        .setPositiveButton("Remove") { _, _ ->
                            scope.launch {
                                withContext(Db.io) {
                                    app.db.deleteBook(b.id)
                                    File(b.path).delete()
                                    b.coverPath?.let { File(it).delete() }
                                }
                                reload()
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    // ------------------------------------------------------------------ covers

    private var coverCheckRunning = false

    /** Once per book: if its cover picture doesn't show the title, use the title page. */
    private fun checkCovers() {
        if (coverCheckRunning) return
        val todo = books.filter { (it.format == BookFormat.EPUB || it.format == BookFormat.MOBI || it.format == BookFormat.PDF) && !Covers.isChecked(app, it.id) }
        if (todo.isEmpty()) return
        coverCheckRunning = true
        scope.launch {
            for (b in todo) {
                val changed = withContext(Dispatchers.IO) {
                    try {
                        val better = Covers.betterThan(app, b, Covers.coverPicture(b))
                        if (better != null) Covers.save(app, b, better)
                        better != null
                    } catch (e: Throwable) {
                        false
                    }
                }
                Covers.markChecked(app, b.id)
                if (changed) {
                    b.coverPath?.let { covers.remove(it) }
                    bookAdapter.notifyDataSetChanged()
                }
            }
            coverCheckRunning = false
        }
    }

    private fun changeCover(b: Book) {
        val canPick = b.format == BookFormat.PDF || b.format == BookFormat.EPUB || b.format == BookFormat.MOBI
        val options = if (canPick) arrayOf("The book's cover picture", "The title page", "Plain: title and author", "Pick a page…")
        else arrayOf("The book's cover picture", "The title page", "Plain: title and author")
        dialog(this, settings.dark).setTitle("Cover for “${b.title}”")
            .setItems(options) { _, i ->
                if (i == 3) {
                    pickPage(b); return@setItems
                }
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        try {
                            when (i) {
                                0 -> Covers.coverPicture(b)
                                1 -> Covers.titlePage(app, b)
                                else -> Importer.generateCover(app, b.title, b.author, b.format)
                            }
                        } catch (e: Throwable) {
                            null
                        }
                    }
                    if (bmp == null) {
                        Toast.makeText(this@LibraryActivity, if (i == 0) "This book has no cover picture" else "Couldn't find a title page", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    withContext(Dispatchers.IO) { Covers.save(app, b, bmp) }
                    Covers.markChecked(app, b.id)
                    b.coverPath?.let { covers.remove(it) }
                    bookAdapter.notifyDataSetChanged()
                }
            }.show()
    }

    /** A grid of the book's first pages / pictures; tap one to make it the cover. */
    private fun pickPage(b: Book) {
        val grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        val thumbs = ArrayList<Bitmap?>()
        var source: Covers.PictureSource? = null
        var picked = false
        val adapter = object : BaseAdapter() {
            override fun getCount() = thumbs.size
            override fun getItem(position: Int) = thumbs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val iv = (convertView as? ImageView) ?: CoverView(this@LibraryActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = panelBg(context, ui, 2f, 1f, ui.soft)
                }
                iv.setImageBitmap(thumbs[position])
                return iv
            }
        }
        grid.adapter = adapter
        val d = dialog(this, settings.dark).setTitle("Tap the page to use as the cover").setView(grid)
            .setNegativeButton("Cancel", null).create()
        val job = scope.launch {
            val src = withContext(Dispatchers.IO) { Covers.pictures(b) }
            if (src == null) {
                d.dismiss()
                Toast.makeText(this@LibraryActivity, "Couldn't read this book's pages", Toast.LENGTH_SHORT).show()
                return@launch
            }
            source = src
            for (i in 0 until src.count) {
                val bmp = withContext(Dispatchers.IO) { src.render(i, 220) }
                thumbs += bmp
                adapter.notifyDataSetChanged()
            }
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            val src = source ?: return@setOnItemClickListener
            picked = true
            job.cancel()
            d.dismiss()
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { src.render(pos, 600) }
                if (bmp != null) {
                    withContext(Dispatchers.IO) { Covers.save(app, b, bmp) }
                    Covers.markChecked(app, b.id)
                    b.coverPath?.let { covers.remove(it) }
                    bookAdapter.notifyDataSetChanged()
                }
                withContext(Dispatchers.IO) { src.close() }
            }
        }
        d.setOnDismissListener {
            job.cancel()
            // When a page was picked, the picking code closes the book after rendering it.
            if (!picked) scope.launch {
                job.join()
                withContext(Dispatchers.IO) {
                    try {
                        source?.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        d.show()
    }

    private fun excerptText(x: Excerpt) = (if (x.text.isNotBlank()) "“${x.text}”" else "(picture excerpt)") +
        (if (!x.note.isNullOrBlank()) "\nNote: ${x.note}" else "") + "\n— ${x.bookTitle}${if (x.label.isNotBlank()) ", ${x.label}" else ""}"

    private fun excerptMenu(x: Excerpt) {
        dialog(this, settings.dark).setItems(arrayOf("Show in book", "Copy", "Share", "Delete")) { _, i ->
            when (i) {
                0 -> openExcerpt(x)
                1 -> {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Excerpt", excerptText(x)))
                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
                2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, excerptText(x)), "Share"))
                3 -> scope.launch {
                    withContext(Db.io) {
                        app.db.deleteExcerpt(x.id); x.imagePath?.let { File(it).delete() }
                    }
                    reload()
                }
            }
        }.show()
    }

    // ------------------------------------------------------------------ import

    private val reqOpen = 41

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/epub+zip", "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword",
                    "text/plain", "text/html", "application/zip", "application/octet-stream",
                    "application/x-mobipocket-ebook", "application/vnd.amazon.ebook", "application/vnd.palm",
                )
            )
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, reqOpen)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqOpen || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris += cd.getItemAt(k).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        importAll(uris, openAfter = uris.size == 1)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri != null) {
            intent.action = null
            importAll(listOf(uri), openAfter = true)
        }
    }

    private fun importAll(uris: List<Uri>, openAfter: Boolean) {
        if (uris.isEmpty()) return
        scope.launch {
            val errors = ArrayList<String>()
            var last: Book? = null
            uris.forEachIndexed { i, uri ->
                status?.text = if (uris.size > 1) "Adding ${i + 1} of ${uris.size}…" else "Adding book…"
                status?.visibility = View.VISIBLE
                try {
                    last = withContext(Dispatchers.IO) { Importer.import(app, uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImportException) {
                    errors += e.message ?: "Couldn't add a file."
                } catch (e: Exception) {
                    errors += "Couldn't add a file: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (isFinishing || isDestroyed) return@launch
            status?.visibility = View.GONE
            settings.libraryTab = 0
            build()
            reload()
            if (errors.isNotEmpty()) {
                dialog(this@LibraryActivity, settings.dark).setTitle("Some files weren't added")
                    .setMessage(errors.joinToString("\n\n")).setPositiveButton("OK", null).show()
            } else if (openAfter) {
                last?.let { open(it) }
            }
        }
    }

    // ------------------------------------------------------------------ updates

    private var pendingUpdate: Updater.Release? = null
    private var updating = false
    private val token: String? get() = settings.prefs.getString("ghToken", null)?.ifBlank { null }

    private fun checkForUpdates(manual: Boolean) {
        if (updating) return
        scope.launch {
            if (manual) {
                status?.text = "Checking for updates…"; status?.visibility = View.VISIBLE; status?.setOnClickListener(null)
            }
            try {
                val rel = withContext(Dispatchers.IO) { Updater.check(token) }
                settings.prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
                val cur = Updater.installedBuild(this@LibraryActivity)
                if (rel.build > cur) {
                    pendingUpdate = rel
                    showUpdateBanner(rel)
                    if (manual) confirmUpdate(rel)
                } else {
                    pendingUpdate = null
                    status?.visibility = View.GONE
                    if (manual) dialog(this@LibraryActivity, settings.dark).setTitle("You're up to date")
                        .setMessage("You have the newest build ($cur).").setPositiveButton("OK", null).show()
                }
            } catch (e: UpdateException) {
                if (isFinishing || isDestroyed) return@launch
                status?.visibility = View.GONE
                if (manual) {
                    val d = dialog(this@LibraryActivity, settings.dark).setTitle("Couldn't check for updates")
                        .setMessage(e.message).setPositiveButton("OK", null)
                    if (e.message == Updater.PRIVATE_REPO_MESSAGE || e.message?.contains("token") == true) {
                        d.setNeutralButton("Add token") { _, _ -> tokenDialog() }
                    }
                    d.show()
                }
            }
        }
    }

    private fun showUpdateBanner(rel: Updater.Release) {
        if (updating) return
        status?.apply {
            text = "A new version is available (build ${rel.build}). Tap to update."
            setTextColor(ui.fg)
            visibility = View.VISIBLE
            setOnClickListener { confirmUpdate(rel) }
        }
    }

    private fun confirmUpdate(rel: Updater.Release) {
        val cur = Updater.installedBuild(this)
        val notes = rel.notes.lines().filter { it.isNotBlank() && !it.startsWith("versionCode") }.joinToString("\n").take(600)
        dialog(this, settings.dark).setTitle("Update to build ${rel.build}?")
            .setMessage("You have build $cur. Your books, highlights and drawings are kept.\n\n$notes")
            .setPositiveButton("Update") { _, _ -> startUpdate(rel) }
            .setNegativeButton("Later", null).show()
    }

    private fun startUpdate(rel: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            dialog(this, settings.dark).setTitle("One-time permission")
                .setMessage("To update itself, E-Reader needs permission to install apps. On the next screen, switch on “Allow from this source”, then come back and tap the update banner again.")
                .setPositiveButton("Continue") { _, _ ->
                    try {
                        startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Open Settings › Apps › E-Reader › Install unknown apps", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        updating = true
        status?.apply { visibility = View.VISIBLE; setOnClickListener(null); text = "Downloading update…" }
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    Updater.download(this@LibraryActivity, rel, token) { pct ->
                        main.post { status?.text = if (pct >= 0) "Downloading update… $pct%" else "Downloading update…" }
                    }
                }
                status?.text = "Installing… confirm the update when Android asks."
                withContext(Dispatchers.IO) { Updater.install(this@LibraryActivity, file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                if (isFinishing || isDestroyed) return@launch
                status?.visibility = View.GONE
                dialog(this@LibraryActivity, settings.dark).setTitle("Update failed").setMessage(e.message).setPositiveButton("OK", null).show()
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                status?.visibility = View.GONE
                dialog(this@LibraryActivity, settings.dark).setTitle("Update failed").setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton("OK", null).show()
            } finally {
                updating = false
            }
        }
    }

    private fun tokenDialog() {
        val input = EditText(this).apply {
            hint = "github_pat_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(token.orEmpty())
        }
        val msg = label(this, "The app downloads updates from the GitHub repository ${Updater.REPO}. " +
            "If that repository is private, the app needs a read-only token to see the updates.\n\n" +
            "To make one: github.com › Settings › Developer settings › Fine-grained tokens › Generate. " +
            "Choose only the ereader repository and set Contents to Read-only.\n\n" +
            "If the repository is public, leave this empty. The token is stored only on this device.", ui, 14f, ui.muted)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(msg)
            addView(input)
        }
        dialog(this, settings.dark).setTitle("Update settings").setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                settings.prefs.edit().putString("ghToken", input.text.toString().trim()).apply()
                checkForUpdates(manual = true)
            }
            .setNeutralButton("Clear") { _, _ -> settings.prefs.edit().remove("ghToken").apply() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun about() {
        dialog(this, settings.dark).setTitle("E-Reader")
            .setMessage("Version ${Updater.installedVersion(this)} (build ${Updater.installedBuild(this)})\n\nUpdates come from github.com/${Updater.REPO}.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Check for updates") { _, _ -> checkForUpdates(manual = true) }
            .show()
    }

    // ------------------------------------------------------------------ adapters

    private fun loadCover(path: String?, into: ImageView) {
        into.tag = path
        if (path == null) {
            into.setImageDrawable(null); return
        }
        covers.get(path)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        coverExec.execute {
            val o = BitmapFactory.Options().apply { inSampleSize = 1 }
            val bmp = try {
                BitmapFactory.decodeFile(path, o)
            } catch (e: Throwable) {
                null
            } ?: return@execute
            main.post {
                covers.put(path, bmp)
                if (into.tag == path) into.setImageBitmap(bmp)
            }
        }
    }

    private class CoverView(ctx: Context) : ImageView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (w * 1.5f).toInt())
        }
    }

    private class ProgressLine(ctx: Context, val fg: Int, val bg: Int) : View(ctx) {
        var progress = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            p.color = bg
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.color = fg
            canvas.drawRect(0f, 0f, width * progress, height.toFloat(), p)
        }
    }

    private inner class SketchCoverView(ctx: Context) : View(ctx) {
        var preview: SketchPage? = null
            set(value) { field = value; invalidate() }
        private val painter = InkPainter()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (w * 1.5f).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val paperW = w - dp(2)
            val paperH = paperW / 0.707f
            val top = (height - paperH) / 2f
            val r = android.graphics.RectF(dp(1).toFloat(), top, w - dp(1), top + paperH)
            SketchPaper.draw(canvas, r, preview?.paper ?: 0, resources.displayMetrics.density)
            val page = preview ?: return
            painter.dark = false
            painter.sensitivity = settings.pressure
            canvas.save()
            canvas.clipRect(r)
            val placement = Placement(r.left, r.top, r.width(), null)
            page.strokes.take(200).forEach { painter.draw(canvas, it, placement) }
            canvas.restore()
        }
    }

    private inner class SketchAdapter : BaseAdapter() {
        override fun getCount() = sketchbooks.size
        override fun getItem(position: Int) = sketchbooks[position]
        override fun getItemId(position: Int) = sketchbooks[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val sketch = sketchbooks[position]
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(SketchCoverView(context))
                addView(ProgressLine(context, ui.fg, ui.hairline), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(6) })
                addView(label(context, "", ui, 14.5f, serif = true).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
                })
                addView(label(context, "", ui, 12f, ui.muted).apply { maxLines = 1 })
            }
            (cell.getChildAt(0) as SketchCoverView).preview = sketchPreviews[sketch.id]
            (cell.getChildAt(1) as ProgressLine).apply { progress = (sketch.currentPage + 1f) / sketch.pageCount; invalidate() }
            (cell.getChildAt(2) as TextView).text = sketch.title
            (cell.getChildAt(3) as TextView).text = "Page ${sketch.currentPage + 1} · ${sketch.pageCount} pages"
            return cell
        }
    }

    private inner class BookAdapter : BaseAdapter() {
        override fun getCount() = books.size
        override fun getItem(position: Int) = books[position]
        override fun getItemId(position: Int) = books[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val b = books[position]
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val cover = CoverView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = panelBg(context, ui, 2f, 1f, ui.soft)
                    setPadding(dp(1), dp(1), dp(1), dp(1))
                    tag = "cover"
                }
                addView(cover)
                addView(ProgressLine(context, ui.fg, ui.hairline).apply { tag = "bar" }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(6) })
                addView(label(context, "", ui, 14.5f, serif = true).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0); tag = "title"
                })
                addView(label(context, "", ui, 12f, ui.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; tag = "sub" })
            }
            val cover = cell.getChildAt(0) as ImageView
            loadCover(b.coverPath, cover)
            (cell.getChildAt(1) as ProgressLine).apply { progress = b.progress; invalidate() }
            (cell.getChildAt(2) as TextView).text = b.title
            val pct = (b.progress * 100).toInt()
            val state = when {
                b.opened == 0L -> "New"
                pct >= 99 -> "Finished"
                else -> "$pct%"
            }
            (cell.getChildAt(3) as TextView).text = listOf(b.author, state).filter { it.isNotBlank() }.joinToString(" · ")
            return cell
        }
    }

    private inner class ExcerptAdapter : BaseAdapter() {
        override fun getCount() = excerpts.size
        override fun getItem(position: Int) = excerpts[position]
        override fun getItemId(position: Int) = excerpts[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val x = excerpts[position]
            val row = LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            x.imagePath?.let { p ->
                val iv = ImageView(this@LibraryActivity).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_START
                    maxHeight = dp(220)
                    colorFilter = ReaderColors(settings.dark).pdfFilter
                }
                row.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
                loadCover(p, iv)
            }
            if (x.text.isNotBlank()) row.addView(label(this@LibraryActivity, "“${x.text}”", ui, 16f, serif = true).apply { maxLines = 8; ellipsize = TextUtils.TruncateAt.END })
            if (!x.note.isNullOrBlank()) row.addView(label(this@LibraryActivity, "Note: ${x.note}", ui, 14f).apply { setPadding(0, dp(4), 0, 0) })
            row.addView(label(this@LibraryActivity, listOf(x.bookTitle, x.label).filter { it.isNotBlank() }.joinToString(" · "), ui, 12.5f, ui.muted).apply { setPadding(0, dp(6), 0, 0) })
            return row
        }
    }
}
