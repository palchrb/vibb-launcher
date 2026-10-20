package com.kidslauncher.mdm.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Launcher round 7 §3.1.5: which posted notification may wake the screen on the lock - each
 * condition on its own ([glanceAlerts], [glanceWakes] over [glanceRows]), and by source that only
 * `GlanceReader.posted` triggers (never a sweep, a ranking update or a policy re-read) and that the
 * trigger carries nothing.
 */
class GlanceWakeTest {
    private val element = "io.element.android.x"
    private val policy = GlancePolicy(mapOf(element to false))

    private fun notice(key: String, conversation: String, postTimeMs: Long, suspended: Boolean = false) = GlanceNotice(
        key = key, packageName = element, conversationKey = conversation, level = GlanceLevel.SENDER, title = null,
        text = null, count = 1, whenMs = postTimeMs, postTimeMs = postTimeMs, suspended = suspended,
    )

    private fun rows(vararg notices: GlanceNotice, opened: Map<GlanceConversation, Long> = emptyMap()) =
        glanceRowConversations(glanceRows(notices.toList(), null, policy, opened))

    private val alerting = GlanceAlertFacts(importance = 3, passesFilter = true, onlyAlertOnce = false, known = false)

    @Test
    fun `a post alerts only from DEFAULT up, through DND, and not as an only-alert-once update`() {
        assertTrue(glanceAlerts(alerting))
        assertTrue(glanceAlerts(alerting.copy(importance = 4)))
        assertFalse("importance LOW (the glance keeps it)", glanceAlerts(alerting.copy(importance = 2)))
        assertFalse("no ranking", glanceAlerts(alerting.copy(importance = null)))
        assertFalse("DND blocks it", glanceAlerts(alerting.copy(passesFilter = false)))
        assertFalse("an only-alert-once update", glanceAlerts(alerting.copy(onlyAlertOnce = true, known = true)))
        assertTrue("only-alert-once, but new", glanceAlerts(alerting.copy(onlyAlertOnce = true, known = false)))
        assertTrue("an update that alerts again", glanceAlerts(alerting.copy(known = true)))
    }

    @Test
    fun `a new row wakes, an update of a visible row or a suspended app doesn't`() {
        val first = notice("k1", "room-a", 100)
        val before = rows(first)
        // Another conversation: a row more.
        assertTrue(glanceWakes(true, false, before, rows(first, notice("k2", "room-b", 200))))
        // A newer message in the same conversation (another key, a newer time): no row more.
        assertFalse(glanceWakes(true, false, before, rows(first, notice("k3", "room-a", 300))))
        // The same key posted again.
        assertFalse(glanceWakes(true, false, before, rows(notice("k1", "room-a", 400))))
        // A suspended app's notification: no row, and suspended anyway.
        val suspended = notice("k4", "room-c", 500, suspended = true)
        assertFalse(glanceWakes(true, true, before, rows(first, suspended)))
        assertEquals(before, rows(first, suspended))
        // Not alerting: never.
        assertFalse(glanceWakes(false, false, before, rows(first, notice("k2", "room-b", 200))))
        // A conversation opened through the glance comes back as a row with a newer post: a wake.
        val opened = mapOf(GlanceConversation(element, "room-a") to 100L)
        assertTrue(glanceWakes(true, false, rows(first, opened = opened), rows(notice("k5", "room-a", 600), opened = opened)))
        // The first row of all.
        assertTrue(glanceWakes(true, false, emptySet(), before))
    }

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun code(name: String) = File(main, "java/com/kidslauncher/mdm/badges/$name").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `only the post triggers, and the trigger carries nothing`() {
        val reader = code("GlanceReader.kt")
        val store = code("GlanceStore.kt")
        assertEquals("posted is the one caller", 1, Regex("""putPosted\(""").findAll(reader).count())
        val posted = reader.substringAfter("fun posted(").substringBefore("\n\n")
        assertTrue(posted, posted.contains("val known = GlanceStore.has(sbn.key)"))
        assertTrue(posted.indexOf("GlanceStore.has(sbn.key)") < posted.indexOf("read(service, sbn, rankingMap)"))
        assertTrue(posted.contains("GlanceStore.putPosted(entry.first, entry.second, alerts(sbn, rankingMap, known))"))
        // The sweep, the ranking update and the policy's re-sweep only put, replace or flag.
        for (name in listOf("sweep", "rankingUpdate")) {
            assertFalse(name, reader.substringAfter("fun $name(").substringBefore("\n\n").contains("putPosted"))
        }
        assertEquals("the store's one trigger", 1, Regex("""noticeCame\(""").findAll(store).count())
        val putPosted = store.substringAfter("fun putPosted(").substringBefore("\n    }\n")
        assertTrue(putPosted.contains("com.kidslauncher.mdm.lock.PinLockRuntime.noticeCame()"))
        for (name in listOf("replaceAll", "updateSuspended", "refreshPolicy", "put", "remove")) {
            assertFalse(name, store.substringAfter("fun $name(").substringBefore("\n    }\n").contains("noticeCame"))
        }
        // No argument: nothing about the notice reaches the lock.
        val runtime = File(main, "java/com/kidslauncher/mdm/lock/PinLockRuntime.kt").readText()
        assertTrue(runtime.contains("    fun noticeCame() {"))
        assertEquals(1, Regex("""fun noticeCame\(""").findAll(runtime).count())
    }
}
