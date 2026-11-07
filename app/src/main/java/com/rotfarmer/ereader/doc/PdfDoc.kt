package com.rotfarmer.ereader.doc

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.util.SizeF
import java.io.Closeable
import java.io.File

/** Thread-safe wrapper around [PdfRenderer] (which only allows one open page at a time). */
class PdfDoc(file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close()
        throw IllegalArgumentException("This PDF can't be opened (it may be password-protected or damaged).", e)
    }
    val pageCount: Int = renderer.pageCount
    private val sizes = arrayOfNulls<SizeF>(pageCount)
    @Volatile private var closed = false

    /** Page size in PDF points. */
    fun pageSize(i: Int): SizeF {
        sizes[i]?.let { return it }
        synchronized(this) {
            sizes[i]?.let { return it }
            if (closed) return SizeF(612f, 792f)
            val s = renderer.openPage(i).use { SizeF(it.width.toFloat(), it.height.toFloat()) }
            sizes[i] = s
            return s
        }
    }

    /** Renders page [i] into [bmp] using [m] to map PDF points to bitmap pixels. */
    fun render(i: Int, bmp: Bitmap, m: Matrix) = synchronized(this) {
        if (closed) return
        bmp.eraseColor(Color.WHITE)
        renderer.openPage(i).use { it.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
    }

    /** Renders the whole page [i] [width] pixels wide. */
    fun renderPage(i: Int, width: Int): Bitmap {
        val s = pageSize(i)
        val scale = width / s.width
        val h = (s.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
        render(i, bmp, Matrix().apply { setScale(scale, scale) })
        return bmp
    }

    /** Renders [region] (page fractions 0..1) of page [i] about [width] pixels wide. */
    fun renderRegion(i: Int, region: RectF, width: Int): Bitmap {
        val s = pageSize(i)
        val rw = region.width() * s.width
        val rh = region.height() * s.height
        val scale = width / rw.coerceAtLeast(1f)
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), (rh * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            setTranslate(-region.left * s.width, -region.top * s.height)
            postScale(scale, scale)
        }
        render(i, bmp, m)
        return bmp
    }

    /** Text inside [region] of page [i], on Android versions that support reading PDF text. */
    fun textIn(i: Int, region: RectF): String? {
        if (!textSupported) return null
        return try {
            synchronized(this) {
                if (closed) return null
                val s = pageSize(i)
                renderer.openPage(i).use { page ->
                    val a = Point((region.left * s.width).toInt(), (region.top * s.height).toInt())
                    val b = Point((region.right * s.width).toInt(), (region.bottom * s.height).toInt())
                    val sel = page.selectContent(SelectionBoundary(a), SelectionBoundary(b))
                    sel?.selectedTextContents?.joinToString(" ") { it.text }?.trim()?.ifEmpty { null }
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    override fun close() = synchronized(this) {
        if (closed) return
        closed = true
        try {
            renderer.close()
        } catch (_: Exception) {
        }
        pfd.close()
    }

    companion object {
        val textSupported: Boolean by lazy {
            Build.VERSION.SDK_INT >= 35 ||
                (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)
        }
    }
}
