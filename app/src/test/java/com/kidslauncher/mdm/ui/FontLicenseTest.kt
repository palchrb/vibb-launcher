package com.kidslauncher.mdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled Nunito ships with its licence and is listed in LegalInfo (design 08, QA #12). */
class FontLicenseTest {

    private fun file(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }

    @Test
    fun `the OFL is in the assets and has no reserved font name`() {
        val ofl = file("src/main/assets/licenses/OFL.txt").readText()
        assertTrue(ofl.contains("SIL OPEN FONT LICENSE Version 1.1"))
        val copyright = ofl.lines().first()
        assertTrue(copyright.contains("Nunito Project Authors"))
        assertFalse(copyright.contains("Reserved Font Name"))
        assertEquals("licenses/OFL.txt", LegalInfoActivity.OFL_ASSET)
        assertTrue(file("src/main/res/values/donottranslate.xml").readText().contains("legal_info_nunito"))
        assertTrue(file("src/main/res/layout/legal_info.xml").readText().contains("@string/legal_info_nunito"))
    }

    @Test
    fun `three static weights and no system font families left in the kid layouts`() {
        for (w in listOf("semibold", "bold", "extrabold")) {
            assertTrue(w, file("src/main/res/font/nunito_$w.ttf").length() > 50_000)
        }
        val layouts = file("src/main/res/layout").listFiles()!!.toList() + file("src/main/res/values/styles.xml")
        for (f in layouts) {
            assertFalse(f.name, Regex("fontFamily\"?>?=?\"?sans-serif").containsMatchIn(f.readText()))
        }
    }
}
