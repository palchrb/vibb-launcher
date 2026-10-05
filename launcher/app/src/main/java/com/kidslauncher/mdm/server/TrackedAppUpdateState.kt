package com.kidslauncher.mdm.server

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlinx.serialization.Serializable

private const val LOG_TAG = "TrackedAppUpdateState"

@Serializable
data class TrackedAppState(
    val lastInstalledTag: String? = null,
    val lastFailedTag: String? = null,
    /** When [lastFailedTag] was recorded - lets [MdmSyncWorker.checkForTrackedAppUpdates] retry
     * automatically after a backoff window instead of skipping the same release forever. Null for
     * state persisted before this field existed, which is treated as "eligible to retry now" (see
     * that function) rather than crashing or defaulting to some other timestamp. */
    val lastFailedAtMs: Long? = null,
    /** Set right before a download+install attempt starts, cleared once it resolves (success or
     * failure) - see [TrackedAppUpdateState.recordAttemptStarted]'s doc comment for what this
     * guards against. */
    val attemptStartedAtMs: Long? = null,
)

/**
 * Per-app install/failure tracking for apps tracked from GitHub Releases (see [AppInstaller],
 * [MdmSyncWorker]'s tracked-app sync) - cached as one JSON blob in a single preference, the same
 * "small JSON blob in a String preference" pattern already used for `kid_mode_policy`, rather than
 * a new custom preference-annotation serializer for a `Map` type. Without this, a release that's
 * already installed would be re-downloaded and re-installed every 2-minute sync forever; one that
 * fails to install is re-attempted after a backoff window instead of forever, see
 * [MdmSyncWorker.checkForTrackedAppUpdates] - an earlier version of this skipped a failed release
 * permanently until its tag changed, which left an app that failed once (e.g. from a since-fixed
 * overlapping-install race) stuck silently un-retried indefinitely, with no notification and no
 * server-side visibility either. Keyed by [TrackedAppUpdate.id] (as a string - stringified once at the
 * call site, not here, to keep this a plain `Map<String, _>` like the preference blob it mirrors),
 * not the app's Android package name - that's optional server-side now and can't be trusted to be
 * present or unique across tracked apps.
 */
object TrackedAppUpdateState {

    fun load(): Map<String, TrackedAppState> {
        val raw = LauncherPreferences.mdm().trackedAppUpdateState() ?: return emptyMap()
        return try {
            ServerJson.decodeFromString(raw)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to decode tracked-app update state", e)
            emptyMap()
        }
    }

    // Writes synchronously (commit(), not the generated preference setter's apply()) because a
    // successful self-update record is written from AppInstallReceiver right as Android is about
    // to SIGKILL this process to replace it with the new APK - an async apply() write frequently
    // never reached disk before that kill, so the launcher kept re-downloading and reinstalling
    // the exact same release forever (confirmed live: same asset_id installed twice ~25s apart,
    // killing CommandListenerService's SSE connection each time). recordFailed shares this path
    // for consistency, though it isn't itself racing a process kill.
    private fun save(context: Context, state: Map<String, TrackedAppState>) {
        val key = LauncherPreferences.mdm().keys().trackedAppUpdateState()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(key, ServerJson.encodeToString(state))
            .commit()
    }

    fun recordInstalled(context: Context, appKey: String, releaseTag: String) {
        val state = load().toMutableMap()
        state[appKey] = TrackedAppState(lastInstalledTag = releaseTag)
        save(context, state)
    }

    fun recordFailed(context: Context, appKey: String, releaseTag: String) {
        val state = load().toMutableMap()
        val lastInstalledTag = state[appKey]?.lastInstalledTag
        state[appKey] = TrackedAppState(
            lastInstalledTag,
            lastFailedTag = releaseTag,
            lastFailedAtMs = System.currentTimeMillis(),
        )
        save(context, state)
    }

    /**
     * Marks a download+install attempt as in flight for this app - checked by
     * [MdmSyncWorker.checkForTrackedAppUpdates] before starting a *new* attempt for the same app,
     * so a later sync cycle (triggered by checking a different app while this one's still
     * mid-install) doesn't fire a second, overlapping install for the same target.
     *
     * This matters because [AppInstaller.installSilently]'s `PackageInstaller.Session.commit()`
     * returns immediately - the real result only arrives later via [AppInstallReceiver], well after
     * `performMdmSync` (and the `syncMutex` guarding it) has already returned. The mutex prevents
     * two sync cycles from running *concurrently*, but does nothing to stop a *later, non-
     * overlapping* cycle from re-attempting an app whose previous attempt simply hasn't resolved
     * yet - confirmed live: checking a second app while the first was still installing caused the
     * first app's install to restart from a fresh cycle's redundant attempt, and the second app's
     * own attempt never completed, ending with it selected/allowed but never actually installed.
     *
     * Recorded before the download even starts (not just before `installSilently`), since the
     * whole download+install span is the window a later cycle shouldn't re-enter. Cleared by
     * [recordInstalled]/[recordFailed] once `AppInstallReceiver` resolves the real result, or by
     * [clearAttempt] on an earlier failure (download error, exception) where that receiver is never
     * reached at all. If neither ever fires - the process dies mid-attempt, or the callback is
     * somehow lost - the timeout in `checkForTrackedAppUpdates` reclaims it instead of blocking
     * retries forever.
     */
    fun recordAttemptStarted(context: Context, appKey: String) {
        val state = load().toMutableMap()
        val current = state[appKey] ?: TrackedAppState()
        state[appKey] = current.copy(attemptStartedAtMs = System.currentTimeMillis())
        save(context, state)
    }

    /** Clears an in-flight marker without touching `lastInstalledTag`/`lastFailedTag` - used for a
     * failure that happens before `installSilently` is ever reached (download error, exception),
     * where nothing else will ever resolve this attempt otherwise. Deliberately doesn't set
     * `lastFailedTag` itself - a transient download failure should still be retried next cycle,
     * not treated as a sticky "don't retry this release" the way a real install failure is. */
    fun clearAttempt(context: Context, appKey: String) {
        val state = load().toMutableMap()
        val current = state[appKey] ?: return
        if (current.attemptStartedAtMs == null) return
        state[appKey] = current.copy(attemptStartedAtMs = null)
        save(context, state)
    }
}
