package com.kidslauncher.mdm.play

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.server.EnforcementPlan
import com.kidslauncher.mdm.server.NOTHING_ALLOWED_FALLBACK
import com.kidslauncher.mdm.server.PolicyToApply
import com.kidslauncher.mdm.server.WindowStart
import com.kidslauncher.mdm.server.computeEnforcementPlan
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.shouldSuspendNewPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayPolicyTest {

    private val controllable = listOf(OWN, DIALER, GAME, SMS, PLAY_STORE, PLAY_SERVICES, GOOGLE_SERVICES_FRAMEWORK)

    private fun plan(
        allowlist: List<String>?,
        overrideActive: Boolean = false,
        locked: Boolean = false,
        usable: Set<String> = emptySet(),
        calls: CallPolicyState = CallPolicyState.Unmanaged,
        play: PlayState = PlayState(),
    ): EnforcementPlan = computeEnforcementPlan(
        allowlist, kioskDesired = true, serverLockTaskFeatures = 0, overrideActive = overrideActive,
        controllable = controllable, ownPackage = OWN, systemDialer = DIALER, callState = calls,
        ourDialerActive = true, smsPackages = setOf(SMS), scheduleLocked = locked,
        lockUsableApps = usable, playState = play,
    )

    private val smsOff = CallPolicyState.Managed(CallRules(callsEnabled = true, smsEnabled = false))
    private val everyPlayCore = PLAY_CORE

    @Test
    fun `Play services and GSF are never suspended or hidden`() {
        val cases = listOf(
            plan(emptyList()),
            plan(listOf(GAME)),
            plan(emptyList(), locked = true),
            plan(null, locked = true),
            plan(emptyList(), calls = smsOff),
            plan(emptyList(), calls = CallPolicyState.UnknownFailClosed),
            plan(listOf(PLAY_SERVICES), locked = true),
        )
        for (p in cases) {
            for (pkg in PLAY_NEVER_RESTRICT) {
                assertFalse("$pkg suspended in $p", pkg in p.suspend)
                assertFalse("$pkg hidden in $p", pkg in p.hide)
                assertTrue(pkg in p.neverRestrict)
            }
        }
    }

    @Test
    fun `the Play Store is never hidden, even when not allowed`() {
        for (p in listOf(plan(emptyList()), plan(listOf(GAME)), plan(emptyList(), locked = true), plan(emptyList(), calls = smsOff))) {
            assertFalse(PLAY_STORE in p.hide)
        }
    }

    @Test
    fun `the Play Store is suspended while managed, even when allowlisted`() {
        assertTrue(PLAY_STORE in plan(emptyList()).suspend)
        assertTrue(PLAY_STORE in plan(listOf(PLAY_STORE, GAME)).suspend)
        // The fallback when nothing is readable is "nothing allowed, kiosk on" - the same plan.
        assertTrue(PLAY_STORE in plan(NOTHING_ALLOWED_FALLBACK.allowlist).suspend)
    }

    @Test
    fun `unmanaged or under an override the Play Store is left alone`() {
        assertFalse(PLAY_STORE in plan(null).suspend)
        assertFalse(PLAY_STORE in plan(emptyList(), overrideActive = true).suspend)
        assertFalse(PLAY_STORE in plan(emptyList(), overrideActive = true, locked = true).suspend)
    }

    @Test
    fun `install mode unsuspends the Play Store and pins only it`() {
        val p = plan(listOf(GAME), play = PlayState(installMode = true))
        assertFalse(PLAY_STORE in p.suspend)
        assertEquals(setOf(OWN, GAME, PLAY_STORE), p.kioskPackages)
        assertFalse(PLAY_SERVICES in p.kioskPackages!!)
        assertFalse(GOOGLE_SERVICES_FRAMEWORK in p.kioskPackages!!)
        // Everything else is unchanged: the not-allowed app stays suspended and hidden.
        assertTrue(SMS in p.suspend && SMS in p.hide)
    }

    @Test
    fun `a time-rule lock cuts install mode short`() {
        val p = plan(listOf(GAME), locked = true, play = PlayState(installMode = true))
        assertTrue(PLAY_STORE in p.suspend)
        assertFalse(PLAY_STORE in p.kioskPackages!!)
    }

    @Test
    fun `the update window unsuspends the Play Store, also during a lock, without pinning it`() {
        val window = PlayState(updateWindow = true)
        assertFalse(PLAY_STORE in plan(emptyList(), play = window).suspend)
        val locked = plan(emptyList(), locked = true, play = window)
        assertFalse(PLAY_STORE in locked.suspend)
        assertFalse(PLAY_STORE in locked.kioskPackages!!)
        assertFalse(PLAY_STORE in plan(listOf(PLAY_STORE), play = window).kioskPackages!!)
    }

    @Test
    fun `the lock suspends the Play Store on an unmanaged phone too, except in the window`() {
        assertTrue(PLAY_STORE in plan(null, locked = true).suspend)
        assertFalse(PLAY_STORE in plan(null, locked = true, play = PlayState(updateWindow = true)).suspend)
        // A lock that names the Play Store as usable doesn't free it.
        assertTrue(PLAY_STORE in plan(listOf(PLAY_STORE), locked = true, usable = setOf(PLAY_STORE)).suspend)
    }

    @Test
    fun `Play packages are never pinned in kiosk outside install mode, whatever the allowlist`() {
        val p = plan(listOf(GAME) + everyPlayCore)
        assertEquals(setOf(OWN, GAME), p.kioskPackages)
        val locked = plan(listOf(GAME) + everyPlayCore, locked = true, usable = everyPlayCore)
        assertEquals(setOf(OWN), locked.kioskPackages)
    }

    @Test
    fun `new installs of Play packages are left to apply()`() {
        val managed = PolicyToApply.Apply(PolicyResponse(allowlist = emptyList(), kioskDesired = true))
        for (pkg in PLAY_CORE) {
            assertFalse(shouldSuspendNewPackage(pkg, managed, false, OWN, DIALER, scheduleLocked = true))
        }
        assertTrue(shouldSuspendNewPackage(GAME, managed, false, OWN, DIALER))
    }

    @Test
    fun `Home and the drawer never show Play packages, except the store in install mode`() {
        assertFalse(playPackageLaunchable(PLAY_SERVICES, installModeActive = true))
        assertFalse(playPackageLaunchable(GOOGLE_SERVICES_FRAMEWORK, installModeActive = false))
        assertFalse(playPackageLaunchable(PLAY_STORE, installModeActive = false))
        assertTrue(playPackageLaunchable(PLAY_STORE, installModeActive = true))
        assertTrue(playPackageLaunchable(GAME, installModeActive = false))
    }

    // Update window

    @Test
    fun `the update window covers 02-04 only while the screen is off`() {
        assertFalse(updateWindowActive(119, screenInteractive = false))
        assertTrue(updateWindowActive(120, screenInteractive = false))
        assertTrue(updateWindowActive(239, screenInteractive = false))
        assertFalse(updateWindowActive(240, screenInteractive = false))
        // Screen on ends it at once; screen off again inside the window resumes it.
        assertFalse(updateWindowActive(150, screenInteractive = true))
        assertTrue(updateWindowActive(151, screenInteractive = false))
    }

    @Test
    fun `a window over midnight wraps, and an empty one never opens`() {
        val w = UpdateWindow(23 * 60, 60)
        assertTrue(w.contains(23 * 60 + 30))
        assertTrue(w.contains(0))
        assertFalse(w.contains(60))
        assertFalse(w.contains(12 * 60))
        assertFalse(UpdateWindow(100, 100).contains(100))
    }

    @Test
    fun `next window edge`() {
        val w = DEFAULT_UPDATE_WINDOW
        assertEquals(120, w.minutesToNextEdge(0))
        assertEquals(120, w.minutesToNextEdge(120))
        assertEquals(1, w.minutesToNextEdge(239))
        assertEquals(1440 - 240 + 120, w.minutesToNextEdge(240))
    }

    @Test
    fun `the play key changes with install mode and the window`() {
        val keys = listOf(PlayState(), PlayState(installMode = true), PlayState(updateWindow = true)).map { it.key() }
        assertEquals(3, keys.toSet().size)
    }

    // Install mode

    private val start = WindowStart(untilWallMs = 1_000_000L + INSTALL_MODE_DURATION_MS, elapsedStartMs = 5_000L, bootCount = 7)

    @Test
    fun `install mode lasts 15 minutes by every clock and ends on reboot`() {
        assertTrue(installModeActive(start, 1_000_000L, 5_000L, 7))
        assertTrue(installModeActive(start, 1_000_000L + INSTALL_MODE_DURATION_MS - 1, 5_000L + INSTALL_MODE_DURATION_MS - 1, 7))
        // Elapsed realtime runs out even if the wall clock was set back.
        assertFalse(installModeActive(start, 1_000_000L, 5_000L + INSTALL_MODE_DURATION_MS, 7))
        // The wall clock runs out.
        assertFalse(installModeActive(start, 1_000_000L + INSTALL_MODE_DURATION_MS, 5_001L, 7))
        // A clock moved far back can't make the window longer than its duration.
        assertFalse(installModeActive(start, 0L, 5_001L, 7))
        // Reboot: a new boot count ends it.
        assertFalse(installModeActive(start, 1_000_000L, 5_000L, 8))
        assertFalse(installModeActive(null, 1_000_000L, 5_000L, 7))
    }

    @Test
    fun `install mode needs a managed phone, the PIN and no time lock`() {
        assertEquals(InstallModeStart.OK, canStartInstallMode(true, true, false, false, true))
        assertEquals(InstallModeStart.NO_PLAY_STORE, canStartInstallMode(true, true, false, false, false))
        assertEquals(InstallModeStart.NOT_NEEDED, canStartInstallMode(false, true, false, false, true))
        assertEquals(InstallModeStart.NO_PIN, canStartInstallMode(true, false, false, false, true))
        assertEquals(InstallModeStart.LOCKED_OUT, canStartInstallMode(true, true, true, false, true))
        assertEquals(InstallModeStart.TIME_LOCKED, canStartInstallMode(true, true, false, true, true))
    }

    // Links and sources

    @Test
    fun `Play links are recognised`() {
        assertTrue(isPlayLink("market", "details", null))
        assertTrue(isPlayLink("https", "play.google.com", "/store/apps/details"))
        assertTrue(isPlayLink("HTTPS", "PLAY.google.com", "/store"))
        assertFalse(isPlayLink("https", "play.google.com", "/books"))
        assertFalse(isPlayLink("https", "example.com", "/store"))
        assertFalse(isPlayLink(null, null, null))
    }

    @Test
    fun `catalog updates skip Play-installed packages`() {
        assertTrue(catalogUpdateBlockedByPlay(PLAY_STORE))
        assertFalse(catalogUpdateBlockedByPlay(null))
        assertFalse(catalogUpdateBlockedByPlay("me.vibb.launcher"))
    }

    companion object {
        const val OWN = "me.vibb.launcher"
        const val DIALER = "com.android.dialer"
        const val GAME = "org.example.game"
        const val SMS = "com.google.android.apps.messaging"
    }
}
