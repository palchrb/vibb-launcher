package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Test

/** Design 67 §2.1: the browsers' managed config ([browserDohPlan]). */
class BrowserDohTest {
    private val installed = setOf("com.android.chrome", "com.brave.browser", "org.cromite.cromite", "org.mozilla.firefox", "org.example")

    @Test
    fun `with the filter on each installed listed browser gets the one key, its value a String`() {
        val plan = browserDohPlan(filterOn = true, installed = installed)
        assertEquals(setOf("com.android.chrome", "com.brave.browser"), plan.keys)
        // A Map<String, String>: the glue writes it with putString, Chromium's type for this policy.
        for (values in plan.values) assertEquals(mapOf("DnsOverHttpsMode" to "off"), values)
    }

    @Test
    fun `with the filter off the same browsers get an empty bundle, and nothing else is ever in the plan`() {
        val plan = browserDohPlan(filterOn = false, installed = installed)
        assertEquals(mapOf("com.android.chrome" to emptyMap<String, String>(), "com.brave.browser" to emptyMap()), plan)
        assertEquals(emptyMap<String, Map<String, String>>(), browserDohPlan(true, setOf("org.example", "com.chrome.beta.fake")))
        assertEquals(BROWSER_DOH_PACKAGES.toSet(), browserDohPlan(true, BROWSER_DOH_PACKAGES.toSet()).keys)
    }
}
