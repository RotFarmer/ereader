package com.rotfarmer.ereader.data

import android.content.Context
import android.content.SharedPreferences
import com.rotfarmer.ereader.ink.Tools
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Settings(ctx: Context) {
    val prefs: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Appearance
    var dark by bool("dark", false)
    /** 0..1, or -1 to follow the system brightness. */
    var brightness by float("brightness", -1f)
    var warmth by float("warmth", 0f)

    // Typography
    var font by string("font", "literata")
    var fontSize by int("fontSize", 5)
    var lineSpacing by int("lineSpacing", 1)
    var margins by int("margins", 1)
    var justify by bool("justify", true)
    var indent by bool("indent", true)
    var twoPages by bool("twoPages", true)

    // E-ink effects
    var paperTexture by bool("paperTexture", true)
    /** Full "e-ink refresh" flash every N page turns, 0 = off. */
    var flashEvery by int("flashEvery", 6)
    var ghosting by bool("ghosting", false)
    var einkImages by bool("einkImages", true)

    // Input
    var fingerDraw by bool("fingerDraw", false)
    var smartHighlight by bool("smartHighlight", true)
    var volumeKeys by bool("volumeKeys", true)
    /** Off: a tap shows/hides the menu and only swipes turn pages. */
    var tapTurns by bool("tapTurns", false)
    var pressure by float("pressure", 0.75f)

    // Pen toolbar
    var tool by int("tool", Tools.PEN)
    var toolbarRight by bool("toolbarRight", false)
    var toolbarExpanded by bool("toolbarExpanded", true)
    var toolbarVisible by bool("toolbarVisible", true)
    /** The sketchbook's pen toolbar has its own switch, so hiding it while reading doesn't hide it here. */
    var sketchToolbarVisible by bool("sketchToolbarVisible", true)

    // Reader footer + reading speed
    var footerMode by int("footerMode", 0)
    var secPerChar by float("secPerChar", 0f)
    var speedSamples by int("speedSamples", 0)

    // Library
    var libraryTab by int("libraryTab", 0)

    /** Dark-mode look for pictures/PDF pages, remembered per book (manga and novels want different things). */
    fun darkPictures(bookId: Long): Int = prefs.getInt("darkPics_$bookId", 0)
    fun setDarkPictures(bookId: Long, v: Int) = prefs.edit().putInt("darkPics_$bookId", v).apply()

    fun toolColor(tool: Int): Int = prefs.getInt("toolColor$tool", Tools.defaultColor(tool))
    fun setToolColor(tool: Int, color: Int) = prefs.edit().putInt("toolColor$tool", color).apply()
    fun toolWidth(tool: Int): Float = prefs.getFloat("toolWidth$tool", Tools.defaultWidth(tool))
    fun setToolWidth(tool: Int, w: Float) = prefs.edit().putFloat("toolWidth$tool", w).apply()

    fun fontSizeSp(): Float = FONT_SIZES[fontSize.coerceIn(0, FONT_SIZES.size - 1)]
    fun lineSpacingMult(): Float = LINE_SPACINGS[lineSpacing.coerceIn(0, LINE_SPACINGS.size - 1)]

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getBoolean(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getInt(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Int) = prefs.edit().putInt(key, value).apply()
    }

    private fun float(key: String, def: Float) = object : ReadWriteProperty<Any, Float> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getFloat(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Float) = prefs.edit().putFloat(key, value).apply()
    }

    private fun string(key: String, def: String) = object : ReadWriteProperty<Any, String> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getString(key, def) ?: def
        override fun setValue(thisRef: Any, property: KProperty<*>, value: String) = prefs.edit().putString(key, value).apply()
    }

    companion object {
        val FONT_SIZES = floatArrayOf(12f, 13f, 14f, 15f, 16f, 17.5f, 19f, 21f, 23f, 26f, 29f, 33f, 38f)
        /** Distance between lines, in em (1.5 = one and a half times the text size). */
        val LINE_SPACINGS = floatArrayOf(1.3f, 1.5f, 1.75f)
        val MARGINS_DP = intArrayOf(14, 28, 48)
    }
}
