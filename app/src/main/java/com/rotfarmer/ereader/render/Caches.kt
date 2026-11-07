package com.rotfarmer.ereader.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import com.rotfarmer.ereader.doc.PdfDoc
import com.rotfarmer.ereader.doc.ResourceSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val main = Handler(Looper.getMainLooper())

private fun bitmapLru(fraction: Int) = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / fraction).toInt()) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

private fun worker(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, name).apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
}

/** Pictures inside EPUB / Word files, decoded off the main thread at display size. */
class ImageCache(private val res: ResourceSource) {
    var onLoaded: (() -> Unit)? = null
    private val lru = bitmapLru(8)
    private val sizes = ConcurrentHashMap<String, Size>()
    private val pending = HashSet<String>()
    private val exec = worker("images")

    /** Intrinsic size, read from the file header. Safe to call from any thread. */
    fun size(path: String): Size? {
        sizes[path]?.let { return if (it.width > 0) it else null }
        val s = try {
            res.open(path)?.use { input ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, o)
                Size(o.outWidth, o.outHeight)
            }
        } catch (e: Exception) {
            null
        } ?: Size(0, 0)
        sizes[path] = s
        return if (s.width > 0) s else null
    }

    fun get(path: String, w: Int, h: Int, eink: Boolean): Bitmap? {
        val key = "$path|$w|$h|$eink"
        lru.get(key)?.let { return it }
        if (pending.add(key)) {
            exec.execute {
                val bmp = decode(path, w, h, eink)
                main.post {
                    pending.remove(key)
                    if (bmp != null) {
                        lru.put(key, bmp)
                        onLoaded?.invoke()
                    }
                }
            }
        }
        return null
    }

    private fun decode(path: String, w: Int, h: Int, eink: Boolean): Bitmap? = try {
        val src = size(path) ?: return null
        var sample = 1
        while (src.width / (sample * 2) >= w && src.height / (sample * 2) >= h) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = res.open(path)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        var bmp = if (raw.width != w || raw.height != h) Bitmap.createScaledBitmap(raw, w.coerceAtLeast(1), h.coerceAtLeast(1), true).also { if (it !== raw) raw.recycle() } else raw
        if (eink) {
            if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
            Eink.process(bmp)
        }
        bmp
    } catch (e: Throwable) {
        null
    }

    fun close() {
        exec.shutdownNow()
        lru.evictAll()
    }
}

/** Rendered PDF pages (and sharper zoomed-in views), produced off the main thread. */
class PdfCache(private val pdf: PdfDoc) {
    var onLoaded: (() -> Unit)? = null
    private val lru = bitmapLru(5)
    private val pending = HashSet<String>()
    private val exec = worker("pdf")
    @Volatile private var zoomToken = 0

    fun get(page: Int, width: Int, eink: Boolean): Bitmap? {
        val key = "$page|$width|$eink"
        lru.get(key)?.let { return it }
        request(key) {
            val bmp = pdf.renderPage(page, width)
            if (eink) Eink.process(bmp)
            bmp
        }
        return null
    }

    fun prefetch(page: Int, width: Int, eink: Boolean) {
        if (page in 0 until pdf.pageCount) get(page, width, eink)
    }

    private fun request(key: String, work: () -> Bitmap?) {
        if (!pending.add(key)) return
        exec.execute {
            val bmp = try {
                work()
            } catch (e: Throwable) {
                null
            }
            main.post {
                pending.remove(key)
                if (bmp != null) {
                    lru.put(key, bmp)
                    onLoaded?.invoke()
                }
            }
        }
    }

    /**
     * Renders exactly what is visible on screen at the current zoom. [m] maps PDF points to
     * screen pixels. Calls [done] on the main thread unless a newer request came in.
     */
    fun renderZoomed(page: Int, m: Matrix, w: Int, h: Int, eink: Boolean, done: (Bitmap) -> Unit) {
        val token = ++zoomToken
        val matrix = Matrix(m)
        exec.execute {
            if (token != zoomToken) return@execute
            val bmp = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    pdf.render(page, it, matrix)
                    if (eink) Eink.process(it)
                }
            } catch (e: Throwable) {
                null
            } ?: return@execute
            main.post { if (token == zoomToken) done(bmp) }
        }
    }

    fun cancelZoom() {
        zoomToken++
    }

    fun close() {
        exec.shutdownNow()
        lru.evictAll()
    }
}
