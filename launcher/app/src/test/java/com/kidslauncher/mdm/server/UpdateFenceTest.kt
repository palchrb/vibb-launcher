package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 11 §2 with qa-11-design.md #1-#7: the update fence's plan, record, order and release rule. */
class UpdateFenceTest {
    private val own = "com.kidslauncher.mdm"
    private val pixelHome = HomeCandidate("com.google.android.apps.nexuslauncher", system = true, persistent = false, priority = 0)
    private val launcher3 = HomeCandidate("com.android.launcher3", system = true, persistent = false, priority = 0)

    private fun plan(vararg candidates: HomeCandidate, protected: Set<String> = emptySet(), controllable: Set<String> = emptySet(), suspended: Set<String> = emptySet()) =
        fencePlan(candidates.toList(), own, protected, controllable, suspended)

    // ---- fencePlan ------------------------------------------------------------------------------

    @Test
    fun `the stock launchers are fenced`() {
        assertEquals(setOf(pixelHome.packageName, launcher3.packageName), plan(pixelHome, launcher3).suspend)
    }

    @Test
    fun `never ours - release or debug - whichever build runs`() {
        val ours = HomeCandidate(own, system = false, persistent = false, priority = 0)
        val debug = HomeCandidate("$own.debug", system = false, persistent = false, priority = 0)
        val result = plan(ours, debug, pixelHome)
        assertEquals(setOf(pixelHome.packageName), result.suspend)
        assertEquals(FenceSkip.OWN, result.skipped[own])
        assertEquals(FenceSkip.OWN, result.skipped["$own.debug"])
        assertEquals(setOf(pixelHome.packageName), fencePlan(listOf(ours, debug, pixelHome), "$own.debug", emptySet(), emptySet(), emptySet()).suspend)
    }

    @Test
    fun `never the platform, SystemUI, phone, Telecom, Settings or Play core`() {
        for (pkg in listOf(
            "android", "com.android.systemui", "com.android.phone", "com.android.server.telecom", "com.android.settings",
            "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
        )) {
            val result = plan(HomeCandidate(pkg, system = true, persistent = false, priority = 0))
            assertEquals(pkg, emptySet<String>(), result.suspend)
            assertEquals(pkg, FenceSkip.NEVER, result.skipped[pkg])
        }
    }

    @Test
    fun `never a persistent package (the boot-loop class)`() {
        val persistent = HomeCandidate("com.oem.persistenthome", system = true, persistent = true, priority = 0)
        assertEquals(FenceSkip.PERSISTENT, plan(persistent).skipped[persistent.packageName])
        assertEquals(emptySet<String>(), plan(persistent).suspend)
    }

    @Test
    fun `never FallbackHome, found by its negative priority whatever its package`() {
        val fallback = HomeCandidate("com.oem.settings", system = true, persistent = false, priority = -1000)
        assertEquals(FenceSkip.FALLBACK_HOME, plan(fallback).skipped[fallback.packageName])
        // The same package with another, ordinary HOME activity is still never fenced.
        val second = fallback.copy(priority = 0)
        assertEquals(emptySet<String>(), plan(fallback, second).suspend)
        assertEquals(emptySet<String>(), plan(second, fallback).suspend)
    }

    @Test
    fun `never a protected package - each kind the glue resolves`() {
        val protectedKinds = mapOf(
            "resolved FallbackHome/Settings package" to "com.oem.settings",
            "SystemUI under another name" to "com.oem.systemui",
            "system dialer" to "com.google.android.dialer",
            "default dialer" to "com.oem.dialer",
            "Telecom" to "com.oem.telecom",
            "emergency dialer" to "com.oem.emergency",
            "keyboard" to "com.google.android.inputmethod.latin",
            "kiosk-block / PIN-lock helper" to "com.google.android.permissioncontroller",
            "lock-task package" to "org.example.allowed",
        )
        for ((kind, pkg) in protectedKinds) {
            val candidate = HomeCandidate(pkg, system = true, persistent = false, priority = 0)
            val result = plan(candidate, pixelHome, protected = setOf(pkg))
            assertEquals(kind, setOf(pixelHome.packageName), result.suspend)
            assertEquals(kind, FenceSkip.PROTECTED, result.skipped[pkg])
        }
    }

    @Test
    fun `never a controllable package - enforcement owns it (qa-11 5)`() {
        val nova = HomeCandidate("com.teslacoilsw.launcher", system = false, persistent = false, priority = 0)
        val result = plan(nova, pixelHome, controllable = setOf(nova.packageName))
        assertEquals(setOf(pixelHome.packageName), result.suspend)
        assertEquals(FenceSkip.CONTROLLABLE, result.skipped[nova.packageName])
    }

    @Test
    fun `never an already suspended package - a release only undoes the fence`() {
        val result = plan(pixelHome, launcher3, suspended = setOf(launcher3.packageName))
        assertEquals(setOf(pixelHome.packageName), result.suspend)
        assertEquals(FenceSkip.ALREADY_SUSPENDED, result.skipped[launcher3.packageName])
    }

    // ---- the record -----------------------------------------------------------------------------

    private val record = FenceRecord(
        version = UPDATE_FENCE_V1,
        stage = FenceStage.ACTIVE,
        planned = setOf("a.home", "b.home"),
        suspended = setOf("a.home"),
        sessionId = 42,
        ownLastUpdateMs = 1_000L,
        bootCount = 7,
        startedWallMs = 1_700_000_000_000L,
        startedElapsedMs = 50_000L,
        releaseTag = "launcher-v1.2.3",
    )

    @Test
    fun `the prefs file and keys are pinned - a later build must release this build's fence (qa-11 2)`() {
        assertEquals("update_fence", UPDATE_FENCE_PREFS)
        assertEquals(1, UPDATE_FENCE_V1)
        assertEquals(
            listOf("v", "stage", "planned", "suspended", "session_id", "own_last_update_ms", "boot_count", "started_wall_ms", "started_elapsed_ms", "release_tag"),
            listOf(
                FenceKeys.VERSION, FenceKeys.STAGE, FenceKeys.PLANNED, FenceKeys.SUSPENDED, FenceKeys.SESSION_ID, FenceKeys.OWN_LAST_UPDATE_MS,
                FenceKeys.BOOT_COUNT, FenceKeys.STARTED_WALL_MS, FenceKeys.STARTED_ELAPSED_MS, FenceKeys.RELEASE_TAG,
            ),
        )
        assertEquals(listOf("planned", "active"), FenceStage.entries.map { it.wire })
    }

    @Test
    fun `a v1 record as SharedPreferences returns it parses - literally, not via encode`() {
        val prefs: Map<String, Any?> = mapOf(
            "v" to 1, "stage" to "active", "planned" to setOf("a.home", "b.home"), "suspended" to setOf("a.home"),
            "session_id" to 42, "own_last_update_ms" to 1_000L, "boot_count" to 7, "started_wall_ms" to 1_700_000_000_000L,
            "started_elapsed_ms" to 50_000L, "release_tag" to "launcher-v1.2.3",
        )
        assertEquals(record, decodeFenceRecord(prefs))
        assertEquals(record, decodeFenceRecord(encodeFenceRecord(record)))
        assertEquals(setOf("b.home"), record.unsuspendable)
        assertEquals(setOf("a.home", "b.home"), record.toRelease)
    }

    @Test
    fun `no session yet is stored as -1 and read back as none`() {
        val planned = record.copy(sessionId = null, stage = FenceStage.PLANNED, suspended = emptySet())
        assertEquals(-1, encodeFenceRecord(planned)[FenceKeys.SESSION_ID])
        assertEquals(planned, decodeFenceRecord(encodeFenceRecord(planned)))
        assertEquals(emptySet<String>(), planned.unsuspendable)
    }

    @Test
    fun `an empty file is no fence, anything unreadable still releases its packages`() {
        assertNull(decodeFenceRecord(emptyMap<String, Any>()))
        val future = mapOf("v" to 2, "planned" to setOf("a.home"), "suspended" to setOf("a.home", "c.home"), "whatever" to "x")
        val decoded = decodeFenceRecord(future)!!
        assertEquals(2, decoded.version)
        assertEquals(setOf("a.home", "c.home"), decoded.toRelease)
        val broken = encodeFenceRecord(record).toMutableMap().apply { put(FenceKeys.BOOT_COUNT, "seven") }
        assertEquals(-1, decodeFenceRecord(broken)!!.version)
        assertEquals(record.toRelease, decodeFenceRecord(broken)!!.toRelease)
        val noVersion = mapOf("planned" to setOf("a.home"), "suspended" to "not a set")
        assertEquals(setOf("a.home"), decodeFenceRecord(noVersion)!!.toRelease)
        for (unreadable in listOf(future, broken, noVersion)) {
            assertEquals(FenceVerdict.Release(FenceReleaseReason.UNKNOWN_VERSION), fenceRelease(decodeFenceRecord(unreadable), check()))
        }
    }

    // ---- order: write-ahead, record only what was suspended -------------------------------------

    private class FakePlatform(val refuse: Set<String> = emptySet(), val writeOk: Boolean = true, val throwOnSuspend: Boolean = false) : FencePlatform {
        val log = mutableListOf<String>()
        val records = mutableListOf<FenceRecord>()
        val suspendedNow = mutableSetOf<String>()

        override fun write(record: FenceRecord): Boolean {
            log += "write:${record.stage.wire}"
            if (writeOk) records += record
            return writeOk
        }

        override fun clear(): Boolean {
            log += "clear"
            records.clear()
            return true
        }

        override fun suspend(packages: Set<String>): Set<String> {
            log += "suspend"
            if (throwOnSuspend) throw SecurityException("not device owner")
            suspendedNow += packages - refuse
            return packages intersect refuse
        }

        override fun unsuspend(packages: Set<String>): Set<String> {
            log += "unsuspend"
            if (throwOnSuspend) throw SecurityException("not device owner")
            suspendedNow -= packages
            return emptySet()
        }
    }

    private val planned = record.copy(stage = FenceStage.PLANNED, planned = setOf("a.home", "b.home"), suspended = emptySet())

    @Test
    fun `the record is written before anything is suspended, then only what the platform suspended`() {
        val platform = FakePlatform(refuse = setOf("b.home"))
        val active = runFence(platform, planned)!!
        assertEquals(listOf("write:planned", "suspend", "write:active"), platform.log)
        assertEquals(setOf("a.home", "b.home"), platform.records.first().planned)
        assertEquals(emptySet<String>(), platform.records.first().suspended)
        assertEquals(setOf("a.home"), active.suspended)
        assertEquals(setOf("b.home"), active.unsuspendable)
    }

    @Test
    fun `a failed write-ahead suspends nothing`() {
        val platform = FakePlatform(writeOk = false)
        assertNull(runFence(platform, planned))
        assertEquals(listOf("write:planned"), platform.log)
        assertTrue(platform.suspendedNow.isEmpty())
    }

    @Test
    fun `a crash between suspend and the second write is still released - planned covers it`() {
        // The process died right after the suspend call: only the planned record is on disk.
        val platform = FakePlatform()
        platform.write(planned)
        platform.suspend(planned.planned)
        val onDisk = decodeFenceRecord(encodeFenceRecord(platform.records.single()))!!
        val verdict = fenceRelease(onDisk, check(session = FenceSession.UNCOMMITTED, committingHere = false))
        assertEquals(FenceVerdict.Release(FenceReleaseReason.NOT_COMMITTED), verdict)
        runRelease(platform, onDisk, controllableNow = emptySet())
        assertTrue(platform.suspendedNow.isEmpty())
        assertTrue(platform.records.isEmpty())
    }

    @Test
    fun `a throwing suspend records nothing as suspended`() {
        val active = runFence(FakePlatform(throwOnSuspend = true), planned)!!
        assertEquals(emptySet<String>(), active.suspended)
        assertEquals(planned.planned, active.unsuspendable)
    }

    @Test
    fun `release unsuspends, then clears - controllable packages are left to apply (qa-11 5)`() {
        val platform = FakePlatform()
        val active = runFence(platform, planned)!!
        platform.log.clear()
        val outcome = runRelease(platform, active, controllableNow = setOf("b.home"))
        assertEquals(listOf("unsuspend", "clear"), platform.log)
        assertEquals(setOf("a.home"), outcome.unsuspended)
        assertEquals(setOf("b.home"), outcome.leftToApply)
        assertEquals(setOf("b.home"), platform.suspendedNow)
    }

    @Test
    fun `release clears the record even when the platform throws (no longer device owner)`() {
        val platform = FakePlatform(throwOnSuspend = true)
        platform.write(record)
        val outcome = runRelease(platform, record, emptySet())
        assertEquals(record.toRelease, outcome.refused)
        assertTrue(platform.records.isEmpty())
    }

    // ---- the release rule -----------------------------------------------------------------------

    private fun check(
        ownLastUpdateMs: Long = record.ownLastUpdateMs,
        bootCount: Int = record.bootCount,
        nowElapsedMs: Long = record.startedElapsedMs + 20_000L,
        nowWallMs: Long = record.startedWallMs + 20_000L,
        session: FenceSession = FenceSession.COMMITTED,
        installResult: FenceInstallResult = FenceInstallResult.NONE,
        committingHere: Boolean = true,
        frontReady: Boolean = false,
        processAgeMs: Long = 600_000L,
        managed: Boolean = true,
        switchOn: Boolean = true,
    ) = FenceCheck(ownLastUpdateMs, bootCount, nowElapsedMs, nowWallMs, session, installResult, committingHere, frontReady, processAgeMs, managed, switchOn)

    private fun release(reason: FenceReleaseReason) = FenceVerdict.Release(reason)

    @Test
    fun `no record, no fence`() {
        assertEquals(FenceVerdict.NoFence, fenceRelease(null, check()))
    }

    @Test
    fun `the committing process keeps the fence while its session is open - a wake before the freeze doesn't end it (qa-11 3)`() {
        val verdict = fenceRelease(record, check(frontReady = true))
        assertTrue(verdict is FenceVerdict.Keep)
        assertEquals(FenceKeepReason.INSTALLING, (verdict as FenceVerdict.Keep).reason)
        assertEquals(FENCE_MAX_MS - 20_000L, verdict.recheckInMs)
        assertTrue(fenceRelease(record, check(session = FenceSession.UNCOMMITTED, frontReady = true)) is FenceVerdict.Keep)
        assertTrue(fenceRelease(record.copy(sessionId = null), check(session = FenceSession.NONE)) is FenceVerdict.Keep)
        assertTrue(fenceRelease(record, check(session = FenceSession.UNKNOWN)) is FenceVerdict.Keep)
    }

    @Test
    fun `a restarted old process keeps a fence whose install is still running`() {
        assertTrue(fenceRelease(record, check(committingHere = false, session = FenceSession.COMMITTED)) is FenceVerdict.Keep)
    }

    @Test
    fun `replaced - the new build releases once Home or the lock is in front or the screen is off`() {
        val replaced = check(ownLastUpdateMs = 9_000L, committingHere = false, session = FenceSession.GONE, processAgeMs = 3_000L, nowWallMs = 9_000L + 3_000L)
        val waiting = fenceRelease(record, replaced)
        assertEquals(FenceVerdict.Keep(FenceKeepReason.AWAITING_FRONT, FENCE_TAIL_BACKSTOP_MS - 3_000L), waiting)
        assertEquals(release(FenceReleaseReason.REPLACED), fenceRelease(record, replaced.copy(frontReady = true)))
    }

    @Test
    fun `replaced - released 2 min after the replacement at the latest, also when the new Home crash-loops`() {
        val replaced = check(ownLastUpdateMs = 9_000L, committingHere = false, session = FenceSession.GONE)
        assertEquals(
            release(FenceReleaseReason.REPLACED_BACKSTOP),
            fenceRelease(record, replaced.copy(processAgeMs = FENCE_TAIL_BACKSTOP_MS, nowWallMs = 9_000L)),
        )
        // A crash loop restarts the process every few seconds - the wall time since the update counts.
        assertEquals(
            release(FenceReleaseReason.REPLACED_BACKSTOP),
            fenceRelease(record, replaced.copy(processAgeMs = 2_000L, nowWallMs = 9_000L + FENCE_TAIL_BACKSTOP_MS)),
        )
    }

    @Test
    fun `a dev adb install also releases a stale fence - lastUpdateTime moves even when versionCode doesn't (qa-11 14)`() {
        assertEquals(
            release(FenceReleaseReason.REPLACED),
            fenceRelease(record, check(ownLastUpdateMs = record.ownLastUpdateMs + 1, committingHere = false, frontReady = true)),
        )
    }

    @Test
    fun `reboot mid-install releases at boot - kiosk on or off`() {
        assertEquals(release(FenceReleaseReason.REBOOTED), fenceRelease(record, check(bootCount = 8, committingHere = false, nowElapsedMs = 5_000L)))
        assertEquals(release(FenceReleaseReason.REBOOTED), fenceRelease(record, check(bootCount = -1)))
        assertEquals(release(FenceReleaseReason.REBOOTED), fenceRelease(record.copy(bootCount = -1), check(bootCount = -1)))
    }

    @Test
    fun `failed installs release at once - signature mismatch, downgrade, storage, user action`() {
        assertEquals(release(FenceReleaseReason.INSTALL_FAILED), fenceRelease(record, check(installResult = FenceInstallResult.FAILURE)))
        assertEquals(release(FenceReleaseReason.PENDING_USER_ACTION), fenceRelease(record, check(installResult = FenceInstallResult.PENDING_USER_ACTION)))
        // A post-kill failure: the old build is started again for the result broadcast.
        assertEquals(
            release(FenceReleaseReason.INSTALL_FAILED),
            fenceRelease(record, check(installResult = FenceInstallResult.FAILURE, committingHere = false, session = FenceSession.GONE)),
        )
    }

    @Test
    fun `the session gone without a result also releases - the broadcast may never come`() {
        assertEquals(release(FenceReleaseReason.SESSION_GONE), fenceRelease(record, check(session = FenceSession.GONE)))
        assertEquals(release(FenceReleaseReason.SESSION_GONE), fenceRelease(record, check(session = FenceSession.GONE, committingHere = false)))
    }

    @Test
    fun `a success that didn't replace us releases - the APK was another package`() {
        assertEquals(release(FenceReleaseReason.SUCCESS_NOT_REPLACED), fenceRelease(record, check(installResult = FenceInstallResult.SUCCESS)))
    }

    @Test
    fun `a success in the new process waits for the front like any replacement`() {
        val verdict = fenceRelease(record, check(installResult = FenceInstallResult.SUCCESS, ownLastUpdateMs = 9_000L, committingHere = false, processAgeMs = 1_000L, nowWallMs = 9_500L))
        assertTrue(verdict is FenceVerdict.Keep)
    }

    @Test
    fun `a process that died before committing leaves no fence behind`() {
        assertEquals(release(FenceReleaseReason.NO_SESSION), fenceRelease(record.copy(sessionId = null), check(session = FenceSession.NONE, committingHere = false)))
        assertEquals(release(FenceReleaseReason.NOT_COMMITTED), fenceRelease(record, check(session = FenceSession.UNCOMMITTED, committingHere = false)))
    }

    @Test
    fun `no fence outlives 10 minutes, or a clock that went backwards`() {
        assertEquals(release(FenceReleaseReason.EXPIRED), fenceRelease(record, check(nowElapsedMs = record.startedElapsedMs + FENCE_MAX_MS)))
        assertEquals(release(FenceReleaseReason.EXPIRED), fenceRelease(record, check(nowElapsedMs = record.startedElapsedMs - 1)))
    }

    @Test
    fun `unmanaged, unenrolled or the server switch off releases`() {
        assertEquals(release(FenceReleaseReason.UNMANAGED), fenceRelease(record, check(managed = false)))
        assertEquals(release(FenceReleaseReason.SWITCHED_OFF), fenceRelease(record, check(switchOn = false)))
    }

    @Test
    fun `an unreadable own lastUpdateTime isn't taken for a replacement`() {
        assertTrue(fenceRelease(record, check(ownLastUpdateMs = -1, frontReady = true)) is FenceVerdict.Keep)
    }

    @Test
    fun `apply never unsuspends a package the fence holds`() {
        assertTrue(suspendTarget("a.home", planSuspend = emptySet(), fenceHeld = setOf("a.home")))
        assertTrue(suspendTarget("x", planSuspend = setOf("x"), fenceHeld = emptySet()))
        assertEquals(false, suspendTarget("y", planSuspend = setOf("x"), fenceHeld = setOf("a.home")))
    }
}
