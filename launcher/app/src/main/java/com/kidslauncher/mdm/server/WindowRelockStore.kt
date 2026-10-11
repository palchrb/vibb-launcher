package com.kidslauncher.mdm.server

import android.content.SharedPreferences
import com.kidslauncher.mdm.server.dto.RelockReport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * The relock engine's state (design 24b §1.2): credential-encrypted prefs `window_relock`, every
 * write a synchronous `commit()`. Never device-protected storage, excluded from backup like
 * everything else. The marker and the window flags change only under [LOCK], held briefly - never
 * for a whole relock: [WindowRelock.opened] commits the marker and lifts the window inside it, and
 * a relock decides it is due and finishes inside it, so no relock can see a marker without its
 * window or clear a newer window's marker (QA #2).
 */
class WindowRelockStore(private val prefs: SharedPreferences) {

    companion object {
        /** See the class comment. Taken by nothing else; nothing slow runs under it but the
         * start snapshot of the first window (~100-300 ms, PackageManager). */
        val LOCK = Any()

        private const val PENDING = "pending"
        private const val GEN = "gen"
        private const val WINDOWS = "windows"
        private const val SECURE_AT_START = "secure_at_start"
        private const val SNAPSHOT = "snapshot"
        private const val DISABLED_AT_START = "disabled_at_start"
        private const val BLOCKED = "blocked"
        private const val REPORT_CURRENT = "report_current"
        private const val REPORT_LAST = "report_last"
        private const val TOKEN = "token"

        private val SNAPSHOT_SERIALIZER = MapSerializer(String.serializer(), PackageState.serializer())
        private val NAMES = ListSerializer(String.serializer())
        private val BLOCKED_SERIALIZER = MapSerializer(String.serializer(), SetSerializer(String.serializer()).nullable)
    }

    fun marker(): RelockMarker = RelockMarker(
        pending = prefs.getBoolean(PENDING, false),
        gen = prefs.getInt(GEN, 0),
        windows = prefs.getString(WINDOWS, null).orEmpty().split(',').filter { it.isNotEmpty() }.toSet(),
    )

    private fun SharedPreferences.Editor.putMarker(marker: RelockMarker) = this
        .putBoolean(PENDING, marker.pending)
        .putInt(GEN, marker.gen)
        .putString(WINDOWS, marker.windows.sorted().joinToString(","))

    fun saveMarker(marker: RelockMarker): Boolean = prefs.edit().putMarker(marker).commit()

    /** `null` = unknown (no start snapshot: an adopted window). */
    fun secureAtStart(): Boolean? = if (prefs.contains(SECURE_AT_START)) prefs.getBoolean(SECURE_AT_START, true) else null

    /** `null` = no snapshot (an adopted window, or it couldn't be taken). Unreadable = none. */
    fun snapshot(): Map<String, PackageState>? = decode(SNAPSHOT) { ServerJson.decodeFromString(SNAPSHOT_SERIALIZER, it) }

    fun disabledAtStart(): Set<String>? = decode(DISABLED_AT_START) { ServerJson.decodeFromString(NAMES, it).toSet() }

    /** The first window's marker and its start state, in one commit ([WindowRelock.opened]). */
    fun saveOpened(marker: RelockMarker, start: RelockStart?): Boolean {
        val editor = prefs.edit().putMarker(marker)
        if (start != null) {
            editor.putBoolean(SECURE_AT_START, start.secure)
            putOrRemove(editor, SNAPSHOT, start.packages?.let { ServerJson.encodeToString(SNAPSHOT_SERIALIZER, it) })
            putOrRemove(editor, DISABLED_AT_START, start.disabled?.let { ServerJson.encodeToString(NAMES, it.sorted()) })
        }
        return editor.commit()
    }

    /** `relock_blocked` (§1.6): package -> the expected signers (`null` = none known). */
    fun blocked(): Map<String, Set<String>?> = decode(BLOCKED) { ServerJson.decodeFromString(BLOCKED_SERIALIZER, it) }.orEmpty()

    fun saveBlocked(blocked: Map<String, Set<String>?>): Boolean =
        prefs.edit().putString(BLOCKED, ServerJson.encodeToString(BLOCKED_SERIALIZER, blocked)).commit()

    /** The PWA's `allow_blocked`: every entry goes. Returns how many there were. */
    fun clearBlocked(): Int {
        val count = blocked().size
        prefs.edit().remove(BLOCKED).commit()
        return count
    }

    /** The relock in progress (its findings add up across re-runs, QA #11), and the last finished one. */
    fun currentReport(): RelockReport? = decode(REPORT_CURRENT) { ServerJson.decodeFromString(RelockReport.serializer(), it) }

    fun lastReport(): RelockReport? = decode(REPORT_LAST) { ServerJson.decodeFromString(RelockReport.serializer(), it) }

    fun saveCurrentReport(report: RelockReport): Boolean =
        prefs.edit().putString(REPORT_CURRENT, ServerJson.encodeToString(RelockReport.serializer(), report)).commit()

    /**
     * The end of a relock, under [LOCK]: the marker is cleared only when no window opened since
     * [genAtStart] and apply() didn't throw ([markerAfterRelock]); then the report becomes the last
     * one and the start state goes. Otherwise the report stays in progress, for the next run.
     * Returns whether the marker was cleared.
     */
    fun complete(genAtStart: Int, applyFailed: Boolean, report: RelockReport): Boolean = synchronized(LOCK) {
        val marker = marker()
        val after = markerAfterRelock(marker, genAtStart, applyFailed)
        val editor = prefs.edit()
        val encoded = ServerJson.encodeToString(RelockReport.serializer(), report)
        if (after.pending) {
            editor.putString(REPORT_CURRENT, encoded)
        } else {
            editor.putMarker(after)
                .putString(REPORT_LAST, encoded)
                .remove(REPORT_CURRENT)
                .remove(SECURE_AT_START)
                .remove(SNAPSHOT)
                .remove(DISABLED_AT_START)
        }
        editor.commit()
        !after.pending
    }

    fun token(): String? = prefs.getString(TOKEN, null)

    fun saveToken(hex: String): Boolean = prefs.edit().putString(TOKEN, hex).commit()

    private fun putOrRemove(editor: SharedPreferences.Editor, key: String, value: String?) {
        if (value == null) editor.remove(key) else editor.putString(key, value)
    }

    private fun <T> decode(key: String, block: (String) -> T): T? = try {
        prefs.getString(key, null)?.let(block)
    } catch (e: Exception) {
        null
    }
}

/** The first window's start state ([takesStartSnapshot]): whether the phone had an Android
 * credential, the non-system packages and the disabled system packages. */
data class RelockStart(val secure: Boolean, val packages: Map<String, PackageState>?, val disabled: Set<String>?)

/** What a relock needs from the phone: WindowRelock's implementation reads and acts on Android;
 * WindowRelockPlanTest fakes it. Each call may throw - the step is then a failure. */
interface RelockPlatform {
    fun now(): Long

    /** The windows open now (`isActive()` of the override and the pause). */
    fun openWindows(): Set<WindowKind>

    fun secureNow(): Boolean

    /** Our reset-password token is stored and active. */
    fun tokenUsable(): Boolean

    /** `resetPasswordWithToken(null)`: true when the credential is gone. */
    fun removeCredential(): Boolean

    fun installedPackages(): List<InstalledPackage>

    /** The enforced policy's allowlist (`null` = apps unmanaged). */
    fun allowlist(): List<String>?

    /** The Play Store and our own package. */
    fun trustedInstallers(): Set<String>

    /** `AppEnforcer.apply(currentPolicyDecision().policy)`. */
    fun apply()

    /** The last apply() set its permitted accessibility and keyboard lists. */
    fun permittedListsOk(): Boolean

    /** Wi-Fi proxies cleared. */
    fun clearProxies(): Int

    /** User CA certificates removed (the count before). */
    fun removeCaCerts(): Int

    fun regrantNotifications()

    /** What is still on (the credential is the engine's: [RelockDetections.credentialLeft] is ignored). */
    fun detect(disabledAtStart: Set<String>?): RelockDetections

    /** The lock re-check, the time-rule screen, the alarm, Home to the front (unless a call,
     * [windowReopened] or a [processStart]), the notifications of windows no longer open. */
    fun finish(windowReopened: Boolean, processStart: Boolean)
}

/** [runRelock]'s answer: `null` report = not due. */
data class RelockOutcome(val report: RelockReport?, val cleared: Boolean)

/**
 * One relock (design 24b §1.2), when due: (1) an Android screen lock set in the window goes, so
 * `PinLockRuntime.configure` in (3) sees none; (2) the signer check writes `relock_blocked`; (3)
 * apply(); (4) the resets; (5) the detections; (6) finish. Each step's findings are committed as it
 * completes; a step that throws is a failure and the rest still runs; a throwing apply() keeps the
 * marker for the next trigger. [fromSync]: the sync's own apply() just ran - (3) runs again only
 * when (1) or (2) changed something ([applyNeeded]).
 */
fun runRelock(store: WindowRelockStore, platform: RelockPlatform, trigger: RelockTrigger, fromSync: Boolean): RelockOutcome {
    val marker = synchronized(WindowRelockStore.LOCK) {
        val marker = store.marker()
        val open = platform.openWindows()
        // A window an older build opened has no marker: it relocks too, without a snapshot.
        adoptedMarker(marker, open)?.let { store.saveMarker(it) }
        if (!relockDue(marker, WindowKind.OVERRIDE in open, WindowKind.PAUSE in open)) return RelockOutcome(null, false)
        marker
    }
    val genAtStart = marker.gen
    val secureAtStart = store.secureAtStart()
    var report = (store.currentReport() ?: startedReport(platform.now(), trigger, marker.windows))
        .let { it.copy(windows = (it.windows + marker.windows).filter { w -> WindowKind.entries.any { k -> k.wire == w } }.distinct().sorted()) }

    fun step(name: String, block: () -> Unit) {
        val failed = try {
            block()
            false
        } catch (e: Exception) {
            true
        }
        report = withStep(report, name, failed)
        store.saveCurrentReport(report)
    }

    // 1. The credential first (QA #9).
    var credentialRemoved = false
    step(RelockFailure.CREDENTIAL) {
        if (credentialAction(secureAtStart, platform.secureNow(), platform.tokenUsable()) == CredentialAction.REMOVE) {
            credentialRemoved = platform.removeCredential()
            report = withResets(report, 0, 0, credentialRemoved, emptyList())
        }
    }
    // 2. The signer check.
    var newlyBlocked = false
    step(RelockFailure.SIGNER_CHECK) {
        val check = signerCheck(platform.installedPackages(), store.snapshot(), platform.allowlist(), store.blocked(), platform.trustedInstallers())
        store.saveBlocked(check.blocked)
        newlyBlocked = check.newlyBlocked.isNotEmpty()
        report = withResets(report, 0, 0, false, check.reported)
    }
    // 3. apply(): kiosk, hiding (with relock_blocked), the VPN, sideloading, tethering, the lists.
    var applyFailed = false
    if (applyNeeded(fromSync, credentialRemoved, newlyBlocked)) {
        step(RelockFailure.APPLY) {
            try {
                platform.apply()
            } catch (e: Exception) {
                applyFailed = true
                throw e
            }
        }
    } else {
        report = withStep(report, RelockFailure.APPLY, false)
    }
    report = withStep(report, RelockFailure.PERMITTED_LISTS, !platform.permittedListsOk())
    // 4. The resets.
    step(RelockFailure.PROXIES) { report = withResets(report, platform.clearProxies(), 0, false, emptyList()) }
    step(RelockFailure.CA_CERTS) { report = withResets(report, 0, platform.removeCaCerts(), false, emptyList()) }
    step(RelockFailure.NOTIFICATIONS) { platform.regrantNotifications() }
    // 5. What is still on.
    step(RelockFailure.DETECTIONS) {
        val left = credentialAction(secureAtStart, platform.secureNow(), tokenUsable = false) != CredentialAction.NONE
        report = withDetections(report, platform.detect(store.disabledAtStart()).copy(credentialLeft = left))
    }
    // 6. Finish.
    step(RelockFailure.FINISH) {
        platform.finish(
            windowReopened = store.marker().gen != genAtStart || platform.openWindows().isNotEmpty(),
            processStart = trigger == RelockTrigger.BOOT,
        )
    }
    val cleared = store.complete(genAtStart, applyFailed, report)
    return RelockOutcome(report, cleared)
}
