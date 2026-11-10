package com.rotfarmer.ereader.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushToolsTest {
    @Test fun legacyToolIdsAreUnchangedAndBrushesHaveNewIds() {
        assertEquals(0, Tools.PEN)
        assertEquals(1, Tools.FOUNTAIN)
        assertEquals(2, Tools.CALLIGRAPHY)
        assertEquals(3, Tools.PENCIL)
        assertEquals(4, Tools.MARKER)
        assertEquals(5, Tools.HIGHLIGHTER)
        assertEquals(6, Tools.ERASER)
        assertEquals(7, Tools.BRUSH)
        assertEquals(8, Tools.SOFT_BRUSH)

        assertEquals(8, Tools.DRAWING.size)
        assertTrue(Tools.DRAWING.contains(Tools.BRUSH))
        assertTrue(Tools.DRAWING.contains(Tools.SOFT_BRUSH))
        assertFalse(Tools.DRAWING.contains(Tools.ERASER))
    }

    @Test fun brushesHaveNameWidthAndColorDefaults() {
        assertEquals("Brush", Tools.name(Tools.BRUSH))
        assertEquals("Soft brush", Tools.name(Tools.SOFT_BRUSH))
        assertEquals(5f, Tools.defaultWidth(Tools.BRUSH), 0f)
        assertEquals(16f, Tools.defaultWidth(Tools.SOFT_BRUSH), 0f)
        assertTrue(Tools.defaultWidth(Tools.BRUSH) in Tools.minWidth(Tools.BRUSH)..Tools.maxWidth(Tools.BRUSH))
        assertTrue(Tools.defaultWidth(Tools.SOFT_BRUSH) in Tools.minWidth(Tools.SOFT_BRUSH)..Tools.maxWidth(Tools.SOFT_BRUSH))
        assertEquals(0xFF1A1A1A.toInt(), Tools.defaultColor(Tools.BRUSH))
        assertEquals(0xFF4A4A4A.toInt(), Tools.defaultColor(Tools.SOFT_BRUSH))
    }

    @Test fun legacyDefaultsAreUnchanged() {
        assertEquals(1.6f, Tools.defaultWidth(Tools.PEN), 0f)
        assertEquals(2.4f, Tools.defaultWidth(Tools.FOUNTAIN), 0f)
        assertEquals(4.5f, Tools.defaultWidth(Tools.CALLIGRAPHY), 0f)
        assertEquals(1.5f, Tools.defaultWidth(Tools.PENCIL), 0f)
        assertEquals(3.5f, Tools.defaultWidth(Tools.MARKER), 0f)
        assertEquals(16f, Tools.defaultWidth(Tools.HIGHLIGHTER), 0f)
        assertEquals(14f, Tools.defaultWidth(Tools.ERASER), 0f)
        assertEquals("Ballpoint", Tools.name(Tools.PEN))
        assertEquals("Eraser", Tools.name(Tools.ERASER))
    }

    @Test fun brushWidthTapersWithPressure() {
        val light = StrokeGeometry.pressureFactor(Tools.BRUSH, 0.1f, 1f)
        val heavy = StrokeGeometry.pressureFactor(Tools.BRUSH, 0.9f, 1f)
        assertTrue(light < 0.5f)
        assertTrue(heavy > light)
        assertEquals(1f, StrokeGeometry.pressureFactor(Tools.BRUSH, 0.5f, 0f), 0f)

        val softLight = StrokeGeometry.pressureFactor(Tools.SOFT_BRUSH, 0.1f, 1f)
        val softHeavy = StrokeGeometry.pressureFactor(Tools.SOFT_BRUSH, 0.9f, 1f)
        assertTrue(softHeavy > softLight)
        assertTrue(softLight > 0f)
    }
}
