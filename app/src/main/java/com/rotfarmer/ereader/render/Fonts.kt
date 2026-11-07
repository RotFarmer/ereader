package com.rotfarmer.ereader.render

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import java.util.IdentityHashMap

/**
 * Bundled reading fonts. Each family is built as four explicit typefaces (regular, bold,
 * italic, bold italic). We never ask Android to derive bold/italic from a custom font with
 * Typeface.create(font, style): on Samsung phones that can return the system font instead.
 */
object Fonts {
    class Family(val key: String, val label: String)

    val families = listOf(
        Family("literata", "Literata"),
        Family("serif", "Serif"),
        Family("atkinson", "Hyperlegible"),
        Family("sans", "Sans"),
        Family("gothic", "Gothic"),
        Family("fraktur", "Fraktur"),
    )

    /** key -> [regular, bold, italic, bold italic] (indexes match Typeface.NORMAL/BOLD/ITALIC/BOLD_ITALIC). */
    private val variants = HashMap<String, Array<Typeface>>()
    /** Which family and style each of our typefaces is. */
    private val owner = IdentityHashMap<Typeface, Pair<String, Int>>()
    private var iconFace: Typeface? = null

    fun get(ctx: Context, key: String): Typeface = variants(ctx, key)[Typeface.NORMAL]

    fun get(ctx: Context, key: String, style: Int): Typeface = variants(ctx, key)[style and 3]

    fun icons(ctx: Context): Typeface = synchronized(this) {
        iconFace ?: Typeface.createFromAsset(ctx.applicationContext.assets, "icons.otf").also { iconFace = it }
    }

    /** [base] in [style] (Typeface.BOLD etc.), staying in the same family. */
    fun styled(base: Typeface, style: Int): Typeface = synchronized(this) {
        val o = owner[base] ?: return Typeface.create(base, style)
        variants[o.first]?.get(style and 3) ?: base
    }

    fun styleOf(tf: Typeface): Int = synchronized(this) { owner[tf]?.second ?: tf.style }

    private fun variants(ctx: Context, key: String): Array<Typeface> = synchronized(this) {
        variants[key]?.let { return it }
        val arr = try {
            load(ctx.applicationContext, key)
        } catch (e: Exception) {
            system(Typeface.SERIF)
        }
        variants[key] = arr
        arr.forEachIndexed { style, tf -> owner[tf] = key to style }
        arr
    }

    private fun load(ctx: Context, key: String): Array<Typeface> = when (key) {
        "literata" -> family(ctx, "Literata", "serif")
        "atkinson" -> family(ctx, "Atkinson", "sans-serif")
        "gothic" -> variable(ctx, "fonts/Gothic.ttf", "serif")
        "fraktur" -> single(ctx, "fonts/Fraktur.ttf", "serif")
        "sans" -> system(Typeface.SANS_SERIF)
        else -> system(Typeface.SERIF)
    }

    private fun system(base: Typeface) = Array(4) { Typeface.create(base, it) }

    private fun build(fam: FontFamily, fallback: String) = Array(4) { style ->
        val weight = if (style and Typeface.BOLD != 0) 700 else 400
        val slant = if (style and Typeface.ITALIC != 0) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT
        Typeface.CustomFallbackBuilder(fam)
            .setStyle(FontStyle(weight, slant))
            .setSystemFallback(fallback)
            .build()
    }

    /** A family with real regular / italic / bold / bold-italic files. */
    private fun family(ctx: Context, base: String, fallback: String): Array<Typeface> {
        val am = ctx.assets
        fun font(file: String, weight: Int, italic: Boolean) = Font.Builder(am, "fonts/$base-$file.ttf")
            .setWeight(weight)
            .setSlant(if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
            .build()
        val fam = FontFamily.Builder(font("Regular", 400, false))
            .addFont(font("Italic", 400, true))
            .addFont(font("Bold", 700, false))
            .addFont(font("BoldItalic", 700, true))
            .build()
        return build(fam, fallback)
    }

    /** A variable-weight font: regular and bold are instances of the same file; italic is slanted. */
    private fun variable(ctx: Context, path: String, fallback: String): Array<Typeface> {
        val am = ctx.assets
        val fam = FontFamily.Builder(
            Font.Builder(am, path).setFontVariationSettings("'wght' 420").setWeight(400).build()
        ).addFont(
            Font.Builder(am, path).setFontVariationSettings("'wght' 720").setWeight(700).build()
        ).build()
        return build(fam, fallback)
    }

    /** One file only: bold and italic are synthesized from it. */
    private fun single(ctx: Context, path: String, fallback: String): Array<Typeface> {
        val fam = FontFamily.Builder(Font.Builder(ctx.assets, path).setWeight(400).build()).build()
        return build(fam, fallback)
    }
}

/**
 * Bold / italic text inside a paragraph. Unlike StyleSpan it switches to our own bold/italic
 * typeface of the current family instead of asking Android to derive one.
 */
class EmphasisSpan(val style: Int) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) = apply(p)
    override fun updateDrawState(p: TextPaint) = apply(p)

    private fun apply(p: TextPaint) {
        val cur = p.typeface ?: Typeface.DEFAULT
        val want = Fonts.styleOf(cur) or style
        p.typeface = Fonts.styled(cur, want)
    }
}
