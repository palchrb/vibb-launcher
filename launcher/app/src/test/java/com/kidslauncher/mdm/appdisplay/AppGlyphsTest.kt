package com.kidslauncher.mdm.appdisplay

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.ui.LegalInfoActivity
import com.kidslauncher.mdm.ui.home.tileColor
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 14 QA #3-#5: the icon and colour table is the shared `app_icons.json` (identical to the
 * server's copy), every icon has its converted glyph saying where it comes from, every tile is
 * `tileColor(seed)`, and the Apache-2.0 licence ships with them.
 */
class AppGlyphsTest {
    private val text = javaClass.classLoader!!.getResource("app_icons.json")!!.readText()
    private val json = ServerJson.parseToJsonElement(text).jsonObject

    private fun file(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }

    @Test
    fun `the json is identical to the server's copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/app_icons.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), text)
    }

    @Test
    fun `the runtime table is the json`() {
        val icons = json["icons"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(icons, AppGlyphs.SYMBOLS)
        assertEquals(icons.keys, AppGlyphs.ICONS.keys)
        assertEquals(16, icons.size)
        val colors = json["colors"]!!.jsonObject
        assertEquals(colors.keys, AppGlyphs.COLORS.keys)
        assertEquals(6, colors.size)
        for ((key, value) in colors) {
            val color = AppGlyphs.COLORS.getValue(key)
            fun argb(field: String) = (0xFF000000 or value.jsonObject[field]!!.jsonPrimitive.content.removePrefix("#").toLong(16)).toInt()
            assertEquals(key, argb("seed"), color.seed)
            assertEquals(key, argb("tile"), color.tile)
            // QA #3: the tile is what the launcher draws for the seed.
            assertEquals(key, color.tile, tileColor(color.seed))
        }
        assertTrue(AUTO_COLOR !in AppGlyphs.COLORS)
    }

    @Test
    fun `every icon has its converted glyph`() {
        for ((key, name) in AppGlyphs.SYMBOLS) {
            val xml = file("src/main/res/drawable/app_glyph_$key.xml").readText()
            assertTrue(key, xml.contains("Converted from Material Symbols \"$name\"") && xml.contains("Apache License 2.0"))
            assertTrue(key, xml.contains("android:viewportWidth=\"960\"") && xml.contains("android:translateY=\"960\""))
            assertTrue(key, xml.contains("android:fillColor=\"#FFFFFFFF\""))
        }
    }

    @Test
    fun `the Apache licence ships and is shown`() {
        val licence = file("src/main/assets/licenses/Apache-2.0.txt").readText()
        assertTrue(licence.contains("Apache License") && licence.contains("Version 2.0, January 2004"))
        assertEquals("licenses/Apache-2.0.txt", LegalInfoActivity.APACHE_ASSET)
        assertTrue(file("src/main/res/values/donottranslate.xml").readText().contains("legal_info_material_symbols"))
        assertTrue(file("src/main/res/layout/legal_info.xml").readText().contains("@string/legal_info_material_symbols"))
    }
}
