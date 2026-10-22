package com.kidslauncher.mdm.server

import android.content.SharedPreferences
import com.kidslauncher.mdm.server.dto.ForeignAccessReport
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
        private const val START_MS = "start_ms"
        private const val CATALOG_PINS = "catalog_pins"
        private const val ALLOWED = "allowed"
        private const val BLOCK_NOTES = "block_notes"
        private const val CA_AT_START = "ca_at_start"
        private const val PROXIES_AT_START = "proxies_at_start"
        private const val FOREIGN_ACCESS = "foreign_access"

        private val SNAPSHOT_SERIALIZER = MapSerializer(String.serializer(), PackageState.serializer())
        private val NAMES = ListSerializer(String.serializer())
        private val BLOCKED_SERIALIZER = MapSerializer(String.serializer(), SetSerializer(String.serializer()).nullable)
        private val PINS_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
        private val ALLOWED_SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
        private val NOTES_SERIALIZER = MapSerializer(String.serializer(), BlockNote.serializer())
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

    /** The user CA certificates (SHA-256) at the first window's start; `null` = no snapshot. */
    fun caCertsAtStart(): Set<String>? = decode(CA_AT_START) { ServerJson.decodeFromString(NAMES, it).toSet() }

    /** The saved Wi-Fi networks' proxies at the first window's start (SSID -> proxy); `null` = no snapshot. */
    fun proxiesAtStart(): Map<String, String>? = decode(PROXIES_AT_START) { ServerJson.decodeFromString(PINS_SERIALIZER, it) }

    /** When the first window opened (wall clock); `null` = unknown. */
    fun startMs(): Long? = if (prefs.contains(START_MS)) prefs.getLong(START_MS, 0L) else null

    /** A window an older build opened gets a marker, and a start as early as it could have been. */
    fun saveAdopted(marker: RelockMarker, startMs: Long): Boolean = prefs.edit().putMarker(marker).putLong(START_MS, startMs).commit()

    /** The catalog's pinned signers (M10: package -> SHA-256 of its signing certificate), from the
     * server's app list - the relock's expected signers for those packages. */
    fun catalogPins(): Map<String, String> = decode(CATALOG_PINS) { ServerJson.decodeFromString(PINS_SERIALIZER, it) }.orEmpty()

    fun saveCatalogPins(pins: Map<String, String>): Boolean =
        if (pins == catalogPins()) true else prefs.edit().putString(CATALOG_PINS, ServerJson.encodeToString(PINS_SERIALIZER, pins)).commit()

    /** The first window's marker and its start state, in one commit ([WindowRelock.opened]). */
    fun saveOpened(marker: RelockMarker, start: RelockStart?): Boolean {
        val editor = prefs.edit().putMarker(marker)
        if (start != null) {
            editor.putLong(START_MS, start.atMs)
            editor.putBoolean(SECURE_AT_START, start.secure)
            putOrRemove(editor, SNAPSHOT, start.packages?.let { ServerJson.encodeToString(SNAPSHOT_SERIALIZER, it) })
            putOrRemove(editor, DISABLED_AT_START, start.disabled?.let { ServerJson.encodeToString(NAMES, it.sorted()) })
            putOrRemove(editor, CA_AT_START, start.caCerts?.let { ServerJson.encodeToString(NAMES, it.sorted()) })
            putOrRemove(editor, PROXIES_AT_START, start.proxies?.let { ServerJson.encodeToString(PINS_SERIALIZER, it) })
        }
        return editor.commit()
    }

    /** `relock_blocked` (§1.6): package -> the expected signers (`null` = none known). */
    fun blocked(): Map<String, Set<String>?> = decode(BLOCKED) { ServerJson.decodeFromString(BLOCKED_SERIALIZER, it) }.orEmpty()

    /** `relock_blocked` and, for each new block in it, its [BlockNote] ([notes]) - in one commit;
     * the notes of blocks that went go with them. */
    fun saveBlocked(blocked: Map<String, Set<String>?>, notes: Map<String, BlockNote> = emptyMap()): Boolean = prefs.edit()
        .putString(BLOCKED, ServerJson.encodeToString(BLOCKED_SERIALIZER, blocked))
        .putString(BLOCK_NOTES, ServerJson.encodeToString(NOTES_SERIALIZER, (blockNotes() + notes).filterKeys { it in blocked }))
        .commit()

    /** Why, where and when each block was set (the status report's `relock_blocks`); a block from
     * before the notes were kept has none. */
    fun blockNotes(): Map<String, BlockNote> = decode(BLOCK_NOTES) { ServerJson.decodeFromString(NOTES_SERIALIZER, it) }.orEmpty()

    /** What "Allow these apps" let out last: package -> its update time then. */
    fun allowed(): Map<String, Long> = decode(ALLOWED) { ServerJson.decodeFromString(ALLOWED_SERIALIZER, it) }.orEmpty()

    /** `allow_blocked`'s result in one commit: the remaining blocks (and their notes) and what was
     * let out. */
    fun saveAllowed(blocked: Map<String, Set<String>?>, allowed: Map<String, Long>): Boolean = prefs.edit()
        .putString(BLOCKED, ServerJson.encodeToString(BLOCKED_SERIALIZER, blocked))
        .putString(BLOCK_NOTES, ServerJson.encodeToString(NOTES_SERIALIZER, blockNotes().filterKeys { it in blocked }))
        .putString(ALLOWED, ServerJson.encodeToString(ALLOWED_SERIALIZER, allowed))
        .commit()


    /** [ForeignAccess]'s last finding (`null` = nothing). */
    fun foreignAccess(): ForeignAccessReport? = decode(FOREIGN_ACCESS) { ServerJson.decodeFromString(ForeignAccessReport.serializer(), it) }

    fun saveForeignAccess(report: ForeignAccessReport?): Boolean = prefs.edit().apply {
        if (report == null) remove(FOREIGN_ACCESS) else putString(FOREIGN_ACCESS, ServerJson.encodeToString(ForeignAccessReport.serializer(), report))
    }.commit()

    /** The relock in progress (its findings add up across re-runs, QA #11), and the last finished one. */
    fun currentReport(): RelockReport? = decode(REPORT_CURRENT) { ServerJson.decodeFromString(RelockReport.serializer(), it) }

    fun lastReport(): RelockReport? = decode(REPORT_LAST) { ServerJson.decodeFromString(RelockReport.serializer(), it) }

    fun saveCurrentReport(report: RelockReport): Boolean =
        prefs.edit().putString(REPORT_CURRENT, ServerJson.encodeToString(RelockReport.serializer(), report)).commit()

    /**
     * The end of a relock, under [LOCK]: the marker is cleared only when no window opened since
     * [genAtStart] and neither apply() nor the signer check threw ([markerAfterRelock], QA Low 2);
     * then the report becomes the last one and the start state goes. Otherwise the report and the
     * snapshot stay, for the next run. Returns whether the marker was cleared.
     */
    fun complete(genAtStart: Int, keepPending: Boolean, report: RelockReport): Boolean = synchronized(LOCK) {
        val marker = marker()
        val after = markerAfterRelock(marker, genAtStart, keepPending)
        val editor = prefs.edit()
        val encoded = ServerJson.encodeToString(RelockReport.serializer(), report)
        if (after.pending) {
            editor.putString(REPORT_CURRENT, encoded)
        } else {
            editor.putMarker(after)
                .putString(REPORT_LAST, encoded)
                .remove(REPORT_CURRENT)
                .remove(SECURE_AT_START)
                .remove(START_MS)
                .remove(SNAPSHOT)
                .remove(DISABLED_AT_START)
                .remove(CA_AT_START)
                .remove(PROXIES_AT_START)
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

/** The first window's start state ([takesStartSnapshot]): when it opened, whether the phone had an
 * Android credential, the non-system packages, the disabled system packages, the user CA
 * certificates and the Wi-Fi proxies (`null` = couldn't be read). */
data class RelockStart(
    val secure: Boolean,
    val packages: Map<String, PackageState>?,
    val disabled: Set<String>?,
    val atMs: Long = 0L,
    val caCerts: Set<String>? = null,
    val proxies: Map<String, String>? = null,
)

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

    /** Design 59 §3.7: the policy asks for Android's lock (CE `keyguard_requested`) - step 1 is skipped then. */
    fun androidKeyguardRequested(): Boolean = false

    fun installedPackages(): List<InstalledPackage>

    /** The enforced policy's allowlist (`null` = apps unmanaged). */
    fun allowlist(): List<String>?

    /** The catalog's pinned signers (M10), package -> signers. */
    fun lockedSigners(): Map<String, Set<String>>

    /** The Play Store and our own package. */
    fun trustedInstallers(): Set<String>

    /** `AppEnforcer.apply(currentPolicyDecision())`. */
    fun apply()

    /** The last apply() set its permitted accessibility and keyboard lists. */
    fun permittedListsOk(): Boolean

    /** The saved Wi-Fi networks' proxies set or changed since [atStart] ([proxyStays]) cleared;
     * [ResetCount.left] = those `updateNetwork` refused. */
    fun clearProxies(atStart: Map<String, String>?): ResetCount

    /** The user CA certificates added since [atStart] ([caCertsToRemove]) removed one by one, then
     * read again (QA Low 9): [ResetCount.done] = gone, [ResetCount.left] = still there. */
    fun removeCaCerts(atStart: Set<String>?): ResetCount

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
 * completes; a step that throws is a failure and the rest still runs; a throwing apply() or signer
 * check keeps the marker (and the snapshot) for the next trigger. (3) always runs - also in the sync,
 * whose own apply() may have run with the window still open (QA Medium 1).
 */
fun runRelock(store: WindowRelockStore, platform: RelockPlatform, trigger: RelockTrigger): RelockOutcome {
    val marker = synchronized(WindowRelockStore.LOCK) {
        val marker = store.marker()
        val open = platform.openWindows()
        // A window an older build opened has no marker: it relocks too, without a snapshot.
        adoptedMarker(marker, open)?.let { store.saveAdopted(it, adoptedStartMs(platform.now())) }
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
    step(RelockFailure.CREDENTIAL) {
        if (credentialAction(secureAtStart, platform.secureNow(), platform.tokenUsable(), platform.androidKeyguardRequested()) == CredentialAction.REMOVE) {
            report = withResets(report, 0, 0, platform.removeCredential(), emptyList())
        }
    }
    // 2. The signer check: a throw keeps the marker and the snapshot (QA Low 2).
    var signerFailed = false
    step(RelockFailure.SIGNER_CHECK) {
        try {
            val check = signerCheck(
                platform.installedPackages(), store.snapshot(), platform.allowlist(), store.blocked(),
                platform.trustedInstallers(), platform.lockedSigners(), store.startMs(),
            )
            val notes = check.reasons.mapValues { BlockNote(it.value, BlockFound.RELOCK, platform.now()) }
            if (!store.saveBlocked(check.blocked, notes)) throw IllegalStateException("relock_blocked not saved")
            report = withResets(report, 0, 0, false, check.reported, check.signers)
        } catch (e: Exception) {
            signerFailed = true
            throw e
        }
    }
    // 3. apply(): kiosk, hiding (with relock_blocked), the VPN, sideloading, tethering, the lists.
    var applyFailed = false
    step(RelockFailure.APPLY) {
        try {
            platform.apply()
        } catch (e: Exception) {
            applyFailed = true
            throw e
        }
    }
    report = withStep(report, RelockFailure.PERMITTED_LISTS, !platform.permittedListsOk())
    // 4. The resets: only what the window added (QA Low 4); what couldn't go is the step's failure.
    step(RelockFailure.PROXIES) {
        val proxies = platform.clearProxies(store.proxiesAtStart())
        report = withResets(report, proxies.done, 0, false, emptyList())
        if (proxies.left > 0) throw IllegalStateException("${proxies.left} Wi-Fi proxies left")
    }
    step(RelockFailure.CA_CERTS) {
        val certs = platform.removeCaCerts(store.caCertsAtStart())
        report = withResets(report, 0, certs.done, false, emptyList())
        if (certs.left > 0) throw IllegalStateException("${certs.left} certificates left")
    }
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
    val cleared = store.complete(genAtStart, keepPending = applyFailed || signerFailed, report)
    return RelockOutcome(report, cleared)
}
