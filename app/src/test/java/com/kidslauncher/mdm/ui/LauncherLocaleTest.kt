package com.kidslauncher.mdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherLocaleTest {

    @Test
    fun `nb and en are set, anything else follows the phone`() {
        assertEquals("nb", resolveLauncherLocale("nb"))
        assertEquals("en", resolveLauncherLocale("en"))
        assertEquals("nb", resolveLauncherLocale(" NB "))
        for (other in listOf(null, "", "system", "de", "no", "nb-NO")) {
            assertEquals(other.toString(), "", resolveLauncherLocale(other))
        }
    }

    @Test
    fun `only a real change is applied`() {
        assertFalse(localeChangeNeeded("", ""))
        assertFalse(localeChangeNeeded("nb", "nb"))
        assertTrue(localeChangeNeeded("", "nb"))
        assertTrue(localeChangeNeeded("nb", ""))
        assertTrue(localeChangeNeeded("en", "nb"))
    }
}
