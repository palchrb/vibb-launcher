package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.RelockReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 24b §1.2/§1.5/§6: the relock engine ([runRelock], the marker, the start snapshot, the
 * report) against a fake prefs map and a fake phone.
 */
class WindowRelockPlanTest {

    private val prefs = ParentPinStoreTest.FakePrefs()
    private val store = WindowRelockStore(prefs)

    /** A phone that records what the relock asked of it, in order. */
    private class FakePhone : RelockPlatform {
        val calls = mutableListOf<String>()
        var open = emptySet<WindowKind>()
        var secure = false
        var token = true
        var packages = listOf<InstalledPackage>()
        var allowlist: List<String>? = listOf("org.example.chat")
        var applyThrows = false
        var listsOk = true
        var proxies = 0
        var certs = 0
        var certsThrow = false
        var detected = RelockDetections(false, false, emptyList(), emptyList(), emptyList(), emptyList(), false)
        var finishedReopened: Boolean? = null
        var finishedAtStart: Boolean? = null
        /** Runs inside the apply step (a window opened meanwhile, ...). */
        var duringApply: () -> Unit = {}

        override fun now() = 1_760_100_000_000
        override fun openWindows() = open
        override fun secureNow() = secure
        override fun tokenUsable() = token
        override fun removeCredential(): Boolean {
            calls += "credential"
            secure = false
            return true
        }
        override fun installedPackages(): List<InstalledPackage> {
            calls += "signers"
            if (installedThrows) throw IllegalStateException("dead binder")
            return packages
        }
        override fun allowlist() = allowlist
        var pins = emptyMap<String, Set<String>>()
        override fun lockedSigners() = pins
        var installedThrows = false
        override fun trustedInstallers() = setOf("com.android.vending", "me.vibb.launcher")
        override fun apply() {
            calls += "apply"
            duringApply()
            if (applyThrows) throw IllegalStateException("apply")
        }
        override fun permittedListsOk() = listsOk
        override fun clearProxies(): Int {
            calls += "proxies"
            return proxies
        }
        override fun removeCaCerts(): Int {
            calls += "certs"
            if (certsThrow) throw SecurityException("certs")
            return certs
        }
        override fun regrantNotifications() {
            calls += "notifications"
        }
        override fun detect(disabledAtStart: Set<String>?): RelockDetections {
            calls += "detect"
            return detected
        }
        override fun finish(windowReopened: Boolean, processStart: Boolean) {
            calls += "finish"
            finishedReopened = windowReopened
            finishedAtStart = processStart
        }
    }

    private val phone = FakePhone()

    /** What WindowRelock.opened commits for [kind] (the lift is the caller's). */
    private fun open(kind: WindowKind, start: RelockStart? = RelockStart(secure = false, packages = emptyMap(), disabled = emptySet())) {
        val marker = store.marker()
        store.saveOpened(markerOpened(marker, kind), start.takeIf { takesStartSnapshot(marker) })
        phone.open = phone.open + kind
    }

    private fun end(kind: WindowKind) {
        phone.open = phone.open - kind
    }

    private fun relock(trigger: RelockTrigger = RelockTrigger.TIMER) = runRelock(store, phone, trigger)

    @Test
    fun `a relock is due only with the marker set and no window open`() {
        assertFalse(relockDue(RelockMarker(), overrideActive = false, pauseActive = false))
        assertTrue(relockDue(RelockMarker(pending = true), overrideActive = false, pauseActive = false))
        assertFalse(relockDue(RelockMarker(pending = true), overrideActive = true, pauseActive = false))
        assertFalse(relockDue(RelockMarker(pending = true), overrideActive = false, pauseActive = true))

        // Nothing opened: nothing runs.
        assertNull(relock().report)
        assertTrue(phone.calls.isEmpty())
        // Open: nothing runs either.
        open(WindowKind.OVERRIDE)
        assertNull(relock().report)
        assertTrue(phone.calls.isEmpty())
        end(WindowKind.OVERRIDE)
        val outcome = relock()
        assertTrue(outcome.cleared)
        assertFalse(store.marker().pending)
        assertEquals(false, phone.finishedReopened)
        assertEquals(false, phone.finishedAtStart)
        // Done once: a second trigger finds nothing.
        phone.calls.clear()
        assertNull(relock().report)
        assertTrue(phone.calls.isEmpty())
    }

    @Test
    fun `the steps run in order - the credential before apply, the resets after it`() {
        open(WindowKind.PAUSE)
        phone.secure = true
        phone.packages = listOf(InstalledPackage("org.example.chat", setOf("aa"), 1L, "com.android.vending"))
        end(WindowKind.PAUSE)
        relock()
        assertEquals(listOf("credential", "signers", "apply", "proxies", "certs", "notifications", "detect", "finish"), phone.calls)
    }

    @Test
    fun `the marker stays when a window opened during the relock - and the first snapshot with it`() {
        val first = RelockStart(secure = false, packages = mapOf("org.example.chat" to PackageState(setOf("aa"), 1L)), disabled = emptySet())
        open(WindowKind.OVERRIDE, first)
        end(WindowKind.OVERRIDE)
        // The parent opens a pause while the relock runs (QA #2).
        phone.duringApply = { open(WindowKind.PAUSE, RelockStart(secure = true, packages = emptyMap(), disabled = emptySet())) }
        val outcome = relock()
        assertFalse(outcome.cleared)
        assertTrue(store.marker().pending)
        assertEquals(setOf("override", "pause"), store.marker().windows)
        assertEquals(true, phone.finishedReopened)
        // The second window kept the first one's start (QA #11).
        assertEquals(false, store.secureAtStart())
        assertEquals(first.packages, store.snapshot())
        // The pause ends: now it clears.
        phone.duringApply = {}
        end(WindowKind.PAUSE)
        assertTrue(relock().cleared)
        assertNull(store.snapshot())
        assertNull(store.secureAtStart())
    }

    @Test
    fun `overlapping windows keep the first window's snapshot`() {
        open(WindowKind.OVERRIDE, RelockStart(secure = false, packages = mapOf("a.b" to PackageState(setOf("x"), 1L)), disabled = setOf("c.d")))
        open(WindowKind.PAUSE, RelockStart(secure = true, packages = emptyMap(), disabled = emptySet()))
        assertEquals(false, store.secureAtStart())
        assertEquals(setOf("c.d"), store.disabledAtStart())
        assertEquals(mapOf("a.b" to PackageState(setOf("x"), 1L)), store.snapshot())
        assertEquals(2, store.marker().gen)
        assertTrue(takesStartSnapshot(RelockMarker()))
        assertFalse(takesStartSnapshot(RelockMarker(pending = true)))
    }

    @Test
    fun `a throwing apply keeps the marker, and the next run's findings add up`() {
        open(WindowKind.OVERRIDE)
        end(WindowKind.OVERRIDE)
        phone.applyThrows = true
        phone.certs = 1
        phone.proxies = 2
        val first = relock()
        assertFalse(first.cleared)
        assertTrue(store.marker().pending)
        assertEquals(listOf("apply"), first.report!!.failures)
        // The rest still ran (QA #11): proxies, certificates, detections, finish.
        assertTrue(phone.calls.containsAll(listOf("proxies", "certs", "notifications", "detect", "finish")))
        assertNull(store.lastReport())

        // The next trigger runs everything again: nothing more to remove, but "1 certificate"
        // stays, and the apply failure is gone once it works.
        phone.applyThrows = false
        phone.certs = 0
        phone.proxies = 0
        val second = relock(RelockTrigger.SCREEN_ON)
        assertTrue(second.cleared)
        val report = store.lastReport()!!
        assertEquals(1, report.caCertsRemoved)
        assertEquals(2, report.proxiesCleared)
        assertEquals(emptyList<String>(), report.failures)
        // The first run's trigger stays the relock's.
        assertEquals("timer", report.trigger)
        assertNull(store.currentReport())
    }

    @Test
    fun `a step that throws is a failure and the rest still runs`() {
        open(WindowKind.PAUSE)
        end(WindowKind.PAUSE)
        phone.certsThrow = true
        phone.listsOk = false
        val outcome = relock()
        assertTrue(outcome.cleared)
        assertEquals(listOf("ca_certs", "permitted_lists"), outcome.report!!.failures)
        assertEquals(listOf("signers", "apply", "proxies", "certs", "notifications", "detect", "finish"), phone.calls)
        assertTrue(outcome.report!!.failures.all { it in RelockFailure.ALL })
    }

    @Test
    fun `an Android screen lock goes only when the phone had none at the first window's start`() {
        assertEquals(CredentialAction.REMOVE, credentialAction(secureAtStart = false, secureNow = true, tokenUsable = true))
        assertEquals(CredentialAction.LEFT, credentialAction(secureAtStart = false, secureNow = true, tokenUsable = false))
        assertEquals(CredentialAction.NONE, credentialAction(secureAtStart = true, secureNow = true, tokenUsable = true))
        assertEquals(CredentialAction.NONE, credentialAction(secureAtStart = null, secureNow = true, tokenUsable = true))
        assertEquals(CredentialAction.NONE, credentialAction(secureAtStart = false, secureNow = false, tokenUsable = true))

        // Set during the window: removed, reported.
        open(WindowKind.OVERRIDE)
        phone.secure = true
        end(WindowKind.OVERRIDE)
        val removed = relock().report!!
        assertTrue(removed.credentialRemoved)
        assertFalse(removed.credentialLeft)
        // No usable token: left, reported.
        phone.calls.clear()
        open(WindowKind.OVERRIDE)
        phone.secure = true
        phone.token = false
        end(WindowKind.OVERRIDE)
        val left = relock().report!!
        assertFalse(left.credentialRemoved)
        assertTrue(left.credentialLeft)
        assertFalse("credential" in phone.calls)
        // The phone had one before the window (not migrated yet): never touched.
        phone.calls.clear()
        phone.token = true
        open(WindowKind.PAUSE, RelockStart(secure = true, packages = emptyMap(), disabled = emptySet()))
        end(WindowKind.PAUSE)
        val kept = relock().report!!
        assertFalse(kept.credentialRemoved)
        assertFalse(kept.credentialLeft)
        assertFalse("credential" in phone.calls)
    }

    @Test
    fun `the reset token is set only while it isn't active and the phone has no credential`() {
        assertTrue(resetTokenNeeded(tokenActive = false, tokenStored = false, secureNow = false))
        assertTrue(resetTokenNeeded(tokenActive = false, tokenStored = true, secureNow = false))
        assertTrue(resetTokenNeeded(tokenActive = true, tokenStored = false, secureNow = false))
        assertFalse(resetTokenNeeded(tokenActive = true, tokenStored = true, secureNow = false))
        // With a credential it couldn't be activated (it would wait for the kid's credential).
        assertFalse(resetTokenNeeded(tokenActive = false, tokenStored = false, secureNow = true))
    }

    @Test
    fun `the relock always applies - also in the sync, whose own apply may have seen the window (QA Medium 1)`() {
        open(WindowKind.OVERRIDE)
        end(WindowKind.OVERRIDE)
        relock(RelockTrigger.SYNC)
        assertTrue("apply" in phone.calls)
        assertEquals("sync", store.lastReport()!!.trigger)

        // A sideloaded app under an allowlisted name: blocked and hidden by that apply.
        phone.calls.clear()
        open(WindowKind.OVERRIDE)
        phone.packages = listOf(InstalledPackage("org.example.chat", setOf("e5e5"), 9L, "com.google.android.packageinstaller"))
        end(WindowKind.OVERRIDE)
        relock(RelockTrigger.SYNC)
        assertTrue("apply" in phone.calls)
        assertEquals(setOf("org.example.chat"), store.blocked().keys)
        assertEquals(listOf("org.example.chat"), store.lastReport()!!.blocked)
        assertEquals(emptyMap<String, String>(), store.lastReport()!!.blockedSigners)
    }

    @Test
    fun `a signer check that throws keeps the marker and the snapshot (QA Low 2)`() {
        val start = RelockStart(secure = false, packages = mapOf("org.example.chat" to PackageState(setOf("a1a1"), 1L)), disabled = emptySet(), atMs = 500L)
        open(WindowKind.PAUSE, start)
        end(WindowKind.PAUSE)
        phone.installedThrows = true
        val first = relock()
        assertFalse(first.cleared)
        assertEquals(listOf("signer_check"), first.report!!.failures)
        assertTrue(store.marker().pending)
        assertEquals(start.packages, store.snapshot())
        assertEquals(500L, store.startMs())
        // The next trigger compares against the same snapshot.
        phone.installedThrows = false
        phone.packages = listOf(InstalledPackage("org.example.chat", setOf("e5e5"), 9L, "com.android.vending"))
        val second = relock()
        assertTrue(second.cleared)
        assertEquals(listOf("org.example.chat"), second.report!!.blocked)
        assertEquals(emptyList<String>(), second.report!!.failures)
        assertNull(store.startMs())
    }

    @Test
    fun `a window an older build opened is adopted and relocks without a snapshot`() {
        // A pause running when the new build started: no marker.
        phone.open = setOf(WindowKind.PAUSE)
        assertNull(relock().report)
        assertTrue(store.marker().pending)
        assertEquals(setOf("pause"), store.marker().windows)
        // A start as early as the window could have begun (QA Low 2).
        assertEquals(adoptedStartMs(phone.now()), store.startMs())
        phone.secure = true
        end(WindowKind.PAUSE)
        val report = relock(RelockTrigger.BOOT).report!!
        // A reboot ended it: the boot brings Home itself.
        assertEquals(true, phone.finishedAtStart)
        // No start state: no credential removed on a guess, nothing compared.
        assertFalse(report.credentialRemoved)
        assertFalse(report.credentialLeft)
        assertTrue(report.blocked.isEmpty())
        assertNull(adoptedMarker(RelockMarker(pending = true), setOf(WindowKind.PAUSE)))
        assertNull(adoptedMarker(RelockMarker(), emptySet()))
    }

    @Test
    fun `the report keeps the server's limits`() {
        val names = (1..12).map { "org.example.app$it" } + listOf("not a package", "x", "org.example.app1")
        val packages = reportPackages(names)
        assertEquals(RELOCK_LIST_MAX, packages.size)
        assertTrue(packages.all { it.startsWith("org.example.app") })
        assertEquals(packages, packages.sorted())
        val started = startedReport(5L, RelockTrigger.LOCK_AGAIN, setOf("pause", "override", "install_mode"))
        assertEquals(listOf("override", "pause"), started.windows)
        assertEquals("lock_again", started.trigger)
        val withFailure = withStep(withStep(started, RelockFailure.PROXIES, true), "unknown_step", true)
        assertEquals(listOf("proxies"), withFailure.failures)
        assertEquals(emptyList<String>(), withStep(withFailure, RelockFailure.PROXIES, false).failures)
        assertEquals(setOf("x.y"), newlyDisabled(setOf("a.b"), setOf("a.b", "x.y")))
        assertEquals(emptySet<String>(), newlyDisabled(null, setOf("a.b")))
    }

    @Test
    fun `detections are the latest run's, resets add up`() {
        val start = startedReport(1L, RelockTrigger.TIMER, setOf("override"))
        val once = withResets(start, proxiesCleared = 1, caCertsRemoved = 2, credentialRemoved = true, blocked = listOf("a.b"))
        val twice = withResets(once, proxiesCleared = 0, caCertsRemoved = 0, credentialRemoved = false, blocked = listOf("c.d"))
        assertEquals(1, twice.proxiesCleared)
        assertEquals(2, twice.caCertsRemoved)
        assertTrue(twice.credentialRemoved)
        assertEquals(listOf("a.b", "c.d"), twice.blocked)
        val found = RelockDetections(true, true, listOf("evil.ally"), listOf("evil.admin"), listOf("com.android.inputmethod"), listOf("x.y"), true)
        val first = withDetections(twice, found)
        val later = withDetections(first, RelockDetections(false, false, emptyList(), emptyList(), emptyList(), emptyList(), false))
        assertTrue(first.proxyLeft && first.credentialLeft && first.ownNotificationsOff)
        assertEquals(listOf("evil.ally"), first.accessibilityEnabled)
        assertEquals(
            RelockReport(1L, "timer", listOf("override"), 1, false, 2, true, false, listOf("a.b", "c.d"), emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), false, emptyList()),
            later,
        )
    }

    @Test
    fun `a window's end is the earlier of its wall end and the elapsed time left`() {
        val hour = 3_600_000L
        val start = WindowStart(untilWallMs = 10 * hour, elapsedStartMs = 1_000L, bootCount = 3)
        // Started at wall 8 h, elapsed 1 s; one hour later both agree.
        assertEquals(10 * hour, windowEndMs(start, 2 * hour, nowWallMs = 9 * hour, nowElapsedMs = 1_000L + hour, nowBootCount = 3))
        // The clock was set back an hour: the elapsed end comes first.
        assertEquals(9 * hour, windowEndMs(start, 2 * hour, nowWallMs = 8 * hour, nowElapsedMs = 1_000L + hour, nowBootCount = 3))
        // Over, or another boot: no end.
        assertNull(windowEndMs(start, 2 * hour, nowWallMs = 10 * hour, nowElapsedMs = 1_000L + 2 * hour, nowBootCount = 3))
        assertNull(windowEndMs(start, 2 * hour, nowWallMs = 9 * hour, nowElapsedMs = 5_000L, nowBootCount = 4))
    }

    @Test
    fun `the keyboards kept are enabled and system or allowlisted - while apps are managed`() {
        val enabled = setOf("com.google.android.inputmethod.latin", "com.sideloaded.keyboard", "org.example.allowed.keys")
        val system = setOf("com.google.android.inputmethod.latin")
        assertEquals(
            setOf("com.google.android.inputmethod.latin", "org.example.allowed.keys"),
            keptInputMethods(enabled, system, listOf("org.example.allowed.keys")),
        )
        assertEquals(system, keptInputMethods(enabled, system, emptyList()))
        // Unmanaged: every enabled one (nothing is hidden anyway).
        assertEquals(enabled, keptInputMethods(enabled, system, null))
    }

    @Test
    fun `permitted lists are set only when they differ`() {
        assertFalse(permittedListChanged(null, null))
        assertFalse(permittedListChanged(listOf("b.c", "a.b"), listOf("a.b", "b.c")))
        assertTrue(permittedListChanged(null, listOf("a.b")))
        assertTrue(permittedListChanged(listOf("a.b"), null))
        assertTrue(permittedListChanged(emptyList(), listOf("a.b")))
    }
}
