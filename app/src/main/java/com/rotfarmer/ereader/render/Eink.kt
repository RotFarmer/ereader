package com.rotfarmer.ereader.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import kotlin.math.roundToInt
import kotlin.random.Random

/** Colors of the page itself, in light and dark mode. */
class ReaderColors(val dark: Boolean) {
    val paper = if (dark) 0xFF101010.toInt() else 0xFFF2F0E9.toInt()
    val text = if (dark) 0xFFD9D9D6.toInt() else 0xFF1C1C1C.toInt()
    val muted = if (dark) 0xFF8A8A88.toInt() else 0xFF6E6D69.toInt()
    val faint = if (dark) 0xFF323232.toInt() else 0xFFD2CEC4.toInt()
    val selection = if (dark) 0x665585D0 else 0x4A2E5E9E

    /** Makes white areas of PDFs / pictures take the paper tone (or inverts them in dark mode). */
    val pdfFilter: ColorMatrixColorFilter = if (dark) {
        val k = FloatArray(3) { i -> -(channel(text, i) - channel(paper, i)) / 255f }
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    k[0], 0f, 0f, 0f, channel(text, 0),
                    0f, k[1], 0f, 0f, channel(text, 1),
                    0f, 0f, k[2], 0f, channel(text, 2),
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    } else multiplyBy(paper)

    val imageFilter: ColorMatrixColorFilter = if (dark) {
        ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.82f, 0.82f, 0.82f, 1f) })
    } else multiplyBy(paper)

    /** Dark mode "Dim": keeps pictures/pages as they are, but much less bright (white becomes a soft grey). */
    val dimFilter: ColorMatrixColorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.58f, 0.56f, 0.53f, 1f) })

    fun highlight(color: Int): Int = if (dark) {
        Color.argb(0xD0, (Color.red(color) * 0.42f).toInt(), (Color.green(color) * 0.42f).toInt(), (Color.blue(color) * 0.42f).toInt())
    } else (color and 0x00FFFFFF) or 0x8C000000.toInt()

    companion object {
        private fun channel(c: Int, i: Int): Float = ((c shr (16 - 8 * i)) and 0xFF).toFloat()
        private fun multiplyBy(c: Int) = ColorMatrixColorFilter(
            ColorMatrix().apply { setScale(channel(c, 0) / 255f, channel(c, 1) / 255f, channel(c, 2) / 255f, 1f) }
        )
    }
}

object Eink {
    /** A tile of faint specks laid over the page, like the grain of an e-ink panel. */
    fun paperTile(dark: Boolean): Bitmap {
        val size = 256
        val rnd = Random(7)
        val px = IntArray(size * size)
        val base = if (dark) 0xFFFFFF else 0x000000
        for (i in px.indices) {
            val r = rnd.nextFloat()
            val a = when {
                r < 0.008f -> 26 + rnd.nextInt(20)
                r < 0.25f -> rnd.nextInt(10)
                else -> rnd.nextInt(4)
            }
            px[i] = (a shl 24) or base
        }
        // A few soft, larger blotches so the tile does not look too regular.
        repeat(18) {
            val cx = rnd.nextInt(size)
            val cy = rnd.nextInt(size)
            val rad = 6 + rnd.nextInt(14)
            for (dy in -rad..rad) for (dx in -rad..rad) {
                val d2 = dx * dx + dy * dy
                if (d2 > rad * rad) continue
                val x = (cx + dx + size) % size
                val y = (cy + dy + size) % size
                val i = y * size + x
                val add = ((1f - d2.toFloat() / (rad * rad)) * 3).toInt()
                val a = ((px[i] ushr 24) + add).coerceAtMost(60)
                px[i] = (a shl 24) or base
            }
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /** An alpha-only noise tile that gives pencil strokes their grain. */
    fun grainTile(): Bitmap {
        val size = 64
        val rnd = Random(11)
        val px = IntArray(size * size) { (((90 + rnd.nextInt(166)) shl 24)) }
        val argb = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        val alpha = argb.extractAlpha()
        argb.recycle()
        return alpha
    }

    /**
     * Converts a bitmap to the 16 shades of gray an e-ink panel can show, with Floyd–Steinberg
     * dithering. Works in place on a mutable bitmap.
     */
    fun process(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return
        val row = IntArray(w)
        var cur = FloatArray(w + 2)
        var next = FloatArray(w + 2)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val a = (c ushr 24) / 255f
                var l = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                l = l * a + 255f * (1f - a)
                l += cur[x + 1]
                val q = ((l / 17f).roundToInt() * 17).coerceIn(0, 255)
                val e = l - q
                cur[x + 2] += e * 0.4375f
                next[x] += e * 0.1875f
                next[x + 1] += e * 0.3125f
                next[x + 2] += e * 0.0625f
                row[x] = (0xFF shl 24) or (q shl 16) or (q shl 8) or q
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
            val t = cur
            cur = next
            next = t
            next.fill(0f)
        }
    }
}
