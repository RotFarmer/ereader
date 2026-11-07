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
import com.rotfarmer.ereader.data.Db
import com.rotfarmer.ereader.data.Excerpt
import com.rotfarmer.ereader.data.ImportException
import com.rotfarmer.ereader.data.Importer
import com.rotfarmer.ereader.render.ReaderColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class LibraryActivity : Activity() {
    private lateinit var app: App
    private val settings get() = app.settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ui = UiColors(false)

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private var status: TextView? = null
    private var books: List<Book> = emptyList()
    private var excerpts: List<Excerpt> = emptyList()
    private var sortMode = 0
    private val bookAdapter = BookAdapter()
    private val excerptAdapter = ExcerptAdapter()

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
            window.insetsController?.setSystemBarsAppearance(
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
            contentDescription = "Add books"
            setOnClickListener { openPicker() }
        })
        root.addView(header)

        val tabs = Segmented(this, listOf("Books", "Excerpts"), settings.libraryTab, ui) {
            settings.libraryTab = it; showTab()
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
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showTab()
    }

    private fun showTab() {
        content.removeAllViews()
        if (settings.libraryTab == 0) {
            if (books.isEmpty()) {
                content.addView(emptyView("Your library is empty", "Tap + to add EPUB, PDF or Word (.docx) files.\nYou can also open them from your file manager with E-Reader."))
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
        } else {
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
        }
    }

    private fun emptyView(title: String, body: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(40), dp(40), dp(40), dp(80))
        addView(label(this@LibraryActivity, title, ui, 20f, bold = true, serif = true).apply { gravity = Gravity.CENTER })
        addView(label(this@LibraryActivity, body, ui, 15f, ui.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(20)) })
        if (settings.libraryTab == 0) addView(TextButton(this@LibraryActivity, "Add books", ui, filled = true).apply { setOnClickListener { openPicker() } })
    }

    private fun reload() {
        scope.launch {
            val (b, e) = withContext(Db.io) { app.db.books() to app.db.allExcerpts() }
            books = when (sortMode) {
                1 -> b.sortedBy { it.title.lowercase() }
                2 -> b.sortedWith(compareBy({ it.author.lowercase() }, { it.title.lowercase() }))
                else -> b
            }
            excerpts = e
            showTab()
            bookAdapter.notifyDataSetChanged()
            excerptAdapter.notifyDataSetChanged()
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
            .setItems(arrayOf("Open", "Remove from library")) { _, i ->
                when (i) {
                    0 -> open(b)
                    1 -> dialog(this, settings.dark)
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
                } catch (e: ImportException) {
                    errors += e.message ?: "Couldn't add a file."
                } catch (e: Exception) {
                    errors += "Couldn't add a file: ${e.message ?: e.javaClass.simpleName}"
                }
            }
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
