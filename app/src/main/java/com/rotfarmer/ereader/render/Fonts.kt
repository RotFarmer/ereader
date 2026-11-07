package com.rotfarmer.ereader.render

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle

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

    private val cache = HashMap<String, Typeface>()
    private var iconFace: Typeface? = null

    fun get(ctx: Context, key: String): Typeface = synchronized(cache) {
        cache.getOrPut(key) {
            try {
                load(ctx.applicationContext, key)
            } catch (e: Exception) {
                Typeface.SERIF
            }
        }
    }

    fun icons(ctx: Context): Typeface = synchronized(cache) {
        iconFace ?: Typeface.createFromAsset(ctx.applicationContext.assets, "icons.otf").also { iconFace = it }
    }

    private fun load(ctx: Context, key: String): Typeface = when (key) {
        "literata" -> family(ctx, "Literata", "serif")
        "atkinson" -> family(ctx, "Atkinson", "sans-serif")
        "gothic" -> variable(ctx, "fonts/Gothic.ttf", "serif")
        "fraktur" -> single(ctx, "fonts/Fraktur.ttf", "serif")
        "sans" -> Typeface.SANS_SERIF
        else -> Typeface.SERIF
    }

    /** A family with real regular / italic / bold / bold-italic files. */
    private fun family(ctx: Context, base: String, fallback: String): Typeface {
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
        return Typeface.CustomFallbackBuilder(fam).setSystemFallback(fallback).build()
    }

    /** A variable-weight font: regular and bold are instances of the same file. */
    private fun variable(ctx: Context, path: String, fallback: String): Typeface {
        val am = ctx.assets
        val fam = FontFamily.Builder(
            Font.Builder(am, path).setFontVariationSettings("'wght' 420").setWeight(400).build()
        ).addFont(
            Font.Builder(am, path).setFontVariationSettings("'wght' 720").setWeight(700).build()
        ).build()
        return Typeface.CustomFallbackBuilder(fam).setSystemFallback(fallback).build()
    }

    private fun single(ctx: Context, path: String, fallback: String): Typeface {
        val fam = FontFamily.Builder(Font.Builder(ctx.assets, path).setWeight(400).build()).build()
        return Typeface.CustomFallbackBuilder(fam).setSystemFallback(fallback).build()
    }
}
