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
    private val lineArt = ConcurrentHashMap<String, Boolean>()

    /**
     * True for drawings / line art on a light or transparent background (as opposed to photos).
     * These are inverted in dark mode so they look like the text. Known once the picture has loaded.
     */
    fun isLineArt(path: String): Boolean = lineArt[path] == true
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

    /** Like [get] but decodes right away on the calling thread (for drawing thumbnails off-screen). */
    fun getBlocking(path: String, w: Int, h: Int, eink: Boolean): Bitmap? {
        val key = "$path|$w|$h|$eink"
        lru.get(key)?.let { return it }
        return decode(path, w, h, eink)?.also { lru.put(key, it) }
    }

    private fun decode(path: String, w: Int, h: Int, eink: Boolean): Bitmap? = try {
        val src = size(path) ?: return null
        var sample = 1
        while (src.width / (sample * 2) >= w && src.height / (sample * 2) >= h) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = res.open(path)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        var bmp = if (raw.width != w || raw.height != h) Bitmap.createScaledBitmap(raw, w.coerceAtLeast(1), h.coerceAtLeast(1), true).also { if (it !== raw) raw.recycle() } else raw
        lineArt[path] = looksLikeLineArt(bmp)
        if (eink) {
            if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
            Eink.process(bmp)
        }
        bmp
    } catch (e: Throwable) {
        null
    }

    /** Mostly light or transparent, with little colour: a drawing rather than a photo. */
    private fun looksLikeLineArt(b: Bitmap): Boolean {
        val w = b.width
        val h = b.height
        if (w <= 0 || h <= 0) return false
        val step = maxOf(1, kotlin.math.sqrt(w.toDouble() * h / 5000.0).toInt())
        var n = 0
        var light = 0
        var satSum = 0f
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = b.getPixel(x, y)
                val a = c ushr 24
                n++
                if (a < 60) {
                    light++
                } else {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val bl = c and 0xFF
                    val mx = maxOf(r, g, bl)
                    val mn = minOf(r, g, bl)
                    if ((mx + mn) / 2 > 200) light++
                    if (mx > 0) satSum += (mx - mn).toFloat() / mx
                }
                x += step
            }
            y += step
        }
        return n > 0 && light.toFloat() / n > 0.4f && satSum / n < 0.22f
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
