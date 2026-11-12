package com.kidslauncher.mdm.server

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.preferences.LauncherPreferences
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import tsembed.Client
import tsembed.Tsembed

private const val LOG_TAG = "TsnetClient"

/** Plain string preference keys, not routed through the kapt-generated LauncherPreferences
 * Config system - this is internal crash-detection bookkeeping, not an admin-facing setting, so
 * it doesn't need a donottranslate.xml entry (same reasoning LocateCommands.kt's own cached-state
 * preferences already use). */
private const val CRASH_GUARD_PENDING_KEY = "tsnet_connect_attempt_pending"
private const val CRASH_COUNT_KEY = "tsnet_connect_crash_count"
private const val LAST_CRASH_AT_KEY = "tsnet_connect_last_crash_at"

/** Set (with the auth key's deletion, one commit) once this phone's node has been on the tailnet:
 * later starts come up from tsnet's stored node state without a key (security review M12). */
private const val TAILNET_JOINED_KEY = "tsnet_node_joined"

/** When the last connect returned (wall clock): the next process judges the death that followed (S-L10,
 * [deathAfterConnectIsCrash]). */
private const val CONNECT_RETURNED_AT_KEY = "tsnet_connect_returned_at"

/** After this many consecutive crashes-during-connect, skip the next attempt entirely rather than
 * retry into the same crash - see [TsnetClient.connectFromPreferences]'s own doc comment. */
private const val MAX_CONSECUTIVE_CRASHES = 2

/** A crash streak older than this doesn't count toward the threshold - the goal is catching a
 * tight boot loop, not permanently disabling tsnet after two crashes that happened days apart. */
private const val CRASH_STREAK_COOLDOWN_MS = 15 * 60 * 1000L

/** Fixed per tsnet.Server.Loopback's own contract - the SOCKS5 proxy it starts always expects
 * this exact username, with the per-connection proxyCred as the password. Not configurable. */
private const val SOCKS_USERNAME = "tsnet"

/**
 * Thin Kotlin wrapper around the embedded tsnet connection (see this repo's
 * `mobile/tsembed` Go package, bound into `libs/tsnet.aar` by CI via
 * gomobile) - gives the launcher its own tailnet connection to reach the
 * server, replacing the standalone Tailscale app. Unlike that app, this is
 * in-process only (no device-wide VPN/routing of its own - see tsnet's own
 * docs) - [KidVpnService] is what owns the device's actual network path.
 *
 * Reaching the server goes through tsnet's own local SOCKS5 proxy
 * ([proxyAddress]/[proxyCredential], via `tsembed.Client.startProxy`) rather
 * than any direct Dial/socket bridging - gobind can't generate Java bindings
 * for tsnet.Server's own Dial()/net.Conn-returning methods, and a SOCKS5
 * proxy is tsnet's own documented mechanism for exactly this "give a
 * non-Go program tailnet access" case. See `mobile/tsembed/tsembed.go`.
 *
 * Owns exactly one [Client] instance for the app's lifetime - not safe to
 * share across threads without external synchronization.
 */
object TsnetClient {
    private var client: Client? = null

    // The (hostname, authKey, stateDir) the current [client] was constructed with. tsnet.Server's
    // AuthKey is only read at construction time (Tsembed.new_), so without tracking this,
    // connect() below would keep reusing a Client built with a stale key forever after the first
    // call in this process's lifetime - a real bug hit live: updating the auth key in Settings had
    // no effect until the app was force-stopped, since the cached Client's Go-side AuthKey field
    // never changed.
    private var clientParams: Triple<String, String, String>? = null

    @Volatile
    var proxyAddress: String? = null
        private set

    @Volatile
    var proxyCredential: String? = null
        private set

    val connected: Boolean
        get() = proxyAddress != null

    /** The last process's death has been judged in this one (S-L10): only the first call of a process looks. */
    private var lastDeathJudged = false

    /**
     * Connects to the tailnet and starts the local SOCKS5 proxy - safe to
     * call repeatedly (reuses the existing [Client] once one exists, rather
     * than reconnecting from scratch every call). Returns this device's own
     * Tailscale IP on success, null on failure - logged, never thrown, since
     * "not connected yet" (no tailnet reachability this cycle) is an
     * expected transient state for callers to handle, not a crash.
     */
    fun connect(
        hostname: String,
        authKey: String,
        stateDir: String,
        timeoutSeconds: Long = 30,
    ): String? {
        val before = proxyAddress
        val ip = try {
            val params = Triple(hostname, authKey, stateDir)
            if (params != clientParams) {
                client?.close()
                proxyAddress = null
                proxyCredential = null
                client = null
            }
            val c = client ?: Tsembed.new_(hostname, authKey, stateDir).also {
                client = it
                clientParams = params
            }
            val ip = c.up(timeoutSeconds)
            val addr = c.startProxy()
            val cred = c.proxyCredential()
            proxyAddress = addr
            proxyCredential = cred
            installAuthenticator(addr, cred)
            ip
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to connect to tailnet", e)
            // Upstream siesta5787/kids-launcher-mdm 0d4201dab9: a joined node never retries on the same tsnet.Server.
            // On Android it can't watch for network changes (netlink is SELinux-blocked for apps), so one started
            // before the network was up stays in "Starting" for good - seen live upstream at LOCKED_BOOT_COMPLETED,
            // ~2 s before Wi-Fi. The next attempt builds a fresh one on whatever network exists by then, from the node
            // state in [stateDir]. A first join keeps its server ([tailnetFreshAfterFailure]). proxyChanged fires
            // once, below.
            if (tailnetFreshAfterFailure(authKey)) discard()
            null
        } catch (e: LinkageError) {
            // Security review S-L10: the aar is arm64-only - a missing or bad `.so` (UnsatisfiedLinkError,
            // ExceptionInInitializerError) fails this connect, it doesn't crash the process from an IO coroutine.
            Log.w(LOG_TAG, "tsnet's native library couldn't load: ${e.javaClass.simpleName}")
            null
        }
        proxyChanged(before)
        return ip
    }

    /** Called (on the connecting thread, never the main one) whenever [proxyAddress] changes:
     * design 38's prepared shutdown client is rebuilt then ([PowerEvents.init], QA N1). */
    @Volatile
    var proxyListener: (() -> Unit)? = null

    private fun proxyChanged(before: String?) {
        if (before == proxyAddress) return
        try {
            proxyListener?.invoke()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Proxy listener failed", e)
        }
    }

    /**
     * Reads the configured auth key/hostname/state dir from preferences and calls [connect] - the
     * one shared entry point for every caller ([com.kidslauncher.mdm.ui.HomeActivity]'s first
     * post-unlock resume, every [MdmSyncWorker] cycle as a retry-until-connected backstop, and
     * [applyProvisioningExtras]'s QR setup flow), so hostname/state-dir computation lives in
     * exactly one place. No-ops (returns null) if no auth key is configured and the phone never
     * joined, or if already connected - safe to call as often as needed. The key is deleted once
     * the node is up; later calls come up from the stored node state (security review M12,
     * [tailnetConnect]).
     *
     * Wrapped in a crash-loop guard, not called unconditionally: [connect] runs tsnet's embedded
     * Go/cgo runtime, a real native-crash surface a plain Kotlin try/catch cannot protect against
     * (a hard abort - e.g. from GrapheneOS's hardened_malloc catching a memory-safety violation -
     * bypasses the JVM's exception handling entirely and kills the process at the signal level).
     * This app has no fallback Home app once Device-Owner-pinned, so a connect attempt that
     * crashes on every launch would boot-loop the device with no way to reach a working launcher
     * again short of a hardware-recovery-mode wipe - confirmed as a real, not hypothetical, risk:
     * this exact mechanism (tsnet's Go runtime SIGABRT-crashing the whole process) already
     * happened once before for a different, since-fixed trigger (`no safe place found to store
     * log state`). A persisted "attempt in progress" flag, set synchronously right before and
     * cleared right after, survives a native crash where in-memory state can't - if a launch finds
     * it still true from last time, that's evidence the previous attempt never returned, and after
     * [MAX_CONSECUTIVE_CRASHES] such detections in a row this skips the attempt entirely for that
     * launch, letting the rest of the app boot normally without tailnet connectivity that cycle,
     * rather than retrying straight into the same crash.
     *
     * Synchronized (QA of the launcher wave, Low 5): Home's resume, the sync and the QR setup call
     * it from different threads, and two first joins at once would build two tsnet servers on one
     * state directory, registering with the one key - the loser's state could win, and the key is
     * deleted after the first success. A second caller waits (up to the 30 s connect) and then sees
     * [connected]. Every caller is off the main thread.
     */
    @Synchronized
    fun connectFromPreferences(context: Context): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val now = System.currentTimeMillis()

        // Consumed immediately, regardless of what happens below - this must never stay
        // stale-true across a cycle that didn't actually attempt a connection (e.g. one skipped
        // by the threshold check further down), or every future call would misread it as evidence
        // of a fresh crash forever, permanently locking tsnet off with no way to self-heal.
        val wasLeftPending = prefs.getBoolean(CRASH_GUARD_PENDING_KEY, false)
        if (wasLeftPending) prefs.edit().putBoolean(CRASH_GUARD_PENDING_KEY, false).commit()

        // Security review S-L10: the last process's connect returned - did the process die soon after (a Go panic or
        // abort outside any try)? Judged once per process, before this one connects.
        val lastConnect = if (lastDeathJudged) null else lastConnectDied(context, prefs)
        lastDeathJudged = true

        var crashCount = prefs.getInt(CRASH_COUNT_KEY, 0)
        val lastCrashAt = prefs.getLong(LAST_CRASH_AT_KEY, 0)
        if (crashCount > 0 && now - lastCrashAt > CRASH_STREAK_COOLDOWN_MS) {
            crashCount = 0
        }
        val streak = crashStreakAfter(crashCount, wasLeftPending, lastConnect)
        if (streak > crashCount) {
            Log.w(
                LOG_TAG,
                (if (wasLeftPending) "Previous tsnet connect attempt never completed" else "The process died soon after a tsnet connect") +
                    " (process likely crashed) - consecutive count now $streak",
            )
            prefs.edit()
                .putInt(CRASH_COUNT_KEY, streak)
                .putLong(LAST_CRASH_AT_KEY, now)
                .commit()
        } else if (streak < crashCount) {
            prefs.edit().putInt(CRASH_COUNT_KEY, streak).commit()
        }
        crashCount = streak

        if (crashCount >= MAX_CONSECUTIVE_CRASHES) {
            Log.w(
                LOG_TAG,
                "Skipping tsnet connect this launch - $crashCount consecutive crashes detected " +
                    "within the last ${CRASH_STREAK_COOLDOWN_MS / 60_000}m",
            )
            return null
        }

        val storedKey = LauncherPreferences.mdm().tailscaleAuthKey()
        val authKey = when (val step = tailnetConnect(storedKey, prefs.getBoolean(TAILNET_JOINED_KEY, false), connected)) {
            TailnetConnect.Nothing -> return null
            TailnetConnect.Connected -> {
                // A key scanned or typed while connected: the node exists, tsnet won't use it.
                forgetKeyOnceJoined(context, storedKey)
                return null
            }
            is TailnetConnect.Up -> step.authKey
        }
        val hostname = "kids-launcher-${Build.MODEL}".replace(Regex("[^A-Za-z0-9-]"), "-")
        val stateDir = context.filesDir.resolve("tailscale").absolutePath

        prefs.edit().putBoolean(CRASH_GUARD_PENDING_KEY, true).commit()
        val ip = try {
            connect(hostname, authKey, stateDir)
        } finally {
            // Reached by any path that returns normally (success or a caught/logged failure
            // inside connect() itself, which already has its own try/catch) - the attempt itself
            // didn't crash. The streak ends only once the process outlives the window after it
            // (S-L10: a Go panic can follow a connect that returned) - judged by the next process.
            prefs.edit()
                .putBoolean(CRASH_GUARD_PENDING_KEY, false)
                .putLong(CONNECT_RETURNED_AT_KEY, System.currentTimeMillis())
                .commit()
        }
        if (ip != null) forgetKeyOnceJoined(context, storedKey)
        return ip
    }

    /**
     * The last process's connect ([CONNECT_RETURNED_AT_KEY]) and Android's record of how that process ended: true
     * when it died as a crash within the window ([deathAfterConnectIsCrash]), false when it lived on, null when no
     * connect returned since the last look. The mark is consumed.
     */
    private fun lastConnectDied(context: Context, prefs: android.content.SharedPreferences): Boolean? {
        val returnedAt = prefs.getLong(CONNECT_RETURNED_AT_KEY, 0L)
        if (returnedAt <= 0L) return null
        prefs.edit().remove(CONNECT_RETURNED_AT_KEY).commit()
        val exit = try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                ?.firstOrNull { it.processName == context.packageName }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the last exit: ${e.javaClass.simpleName}")
            null
        }
        return deathAfterConnectIsCrash(returnedAt, exit?.timestamp, exit?.reason, exit?.status)
    }

    /**
     * The parent's "Leave tailnet" (the `leave_tailnet` command, security review S-L10): tsnet stops, and the joined
     * mark, any stored auth key, the crash guard's marks and the node state (`files/tailscale`) go, so the phone no
     * longer starts tsnet on every Home resume and sync. It reaches the server on its direct address from then on;
     * joining again takes a new key. Returns the command's answer ([leaveTailnetAnswer]).
     */
    @Synchronized
    fun leave(context: Context): Pair<Boolean, String> {
        // The QA fix round's M2: a server at a tailnet address isn't reached safely any other way.
        if (tailnetOnlyServer(LauncherPreferences.mdm().serverUrl())) {
            Log.i(LOG_TAG, "Leave tailnet refused: the server address is a tailnet address")
            return leaveTailnetRefusal()
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val keyName = LauncherPreferences.mdm().keys().tailscaleAuthKey()
        val stateDir = context.filesDir.resolve("tailscale")
        val wasOn = client != null || prefs.getBoolean(TAILNET_JOINED_KEY, false) ||
            !prefs.getString(keyName, null).isNullOrBlank() || stateDir.exists()
        close()
        // Nothing answers for the closed proxy's port any more (the fix round's nit 1).
        Authenticator.setDefault(null)
        // The marks first: a death mid-way leaves a phone that doesn't start tsnet, never one that rejoins.
        val saved = prefs.edit()
            .remove(TAILNET_JOINED_KEY)
            .remove(keyName)
            .remove(CRASH_GUARD_PENDING_KEY)
            .remove(CRASH_COUNT_KEY)
            .remove(LAST_CRASH_AT_KEY)
            .remove(CONNECT_RETURNED_AT_KEY)
            .commit()
        val deleted = !stateDir.exists() || stateDir.deleteRecursively()
        val answer = leaveTailnetAnswer(wasOn, saved && deleted)
        Log.i(LOG_TAG, "Leave tailnet: ${answer.second}")
        return answer
    }

    /** Whether this phone's node has been on the tailnet (Settings shows it once the key is gone). */
    fun joined(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(TAILNET_JOINED_KEY, false)

    /**
     * Security review M12: once the node is up, the auth key is never needed again (tsnet reads it
     * only to create the node), so it is deleted, with the "joined" mark, in one commit. Later
     * starts connect from the stored node state ([tailnetConnect]).
     */
    private fun forgetKeyOnceJoined(context: Context, storedKey: String?) {
        if (!connected) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val forget = forgetTailnetKey(storedKey, connected)
        if (!forget && prefs.getBoolean(TAILNET_JOINED_KEY, false)) return
        val edit = prefs.edit().putBoolean(TAILNET_JOINED_KEY, true)
        if (forget) edit.remove(LauncherPreferences.mdm().keys().tailscaleAuthKey())
        if (edit.commit()) {
            if (forget) Log.i(LOG_TAG, "On the tailnet - the auth key is deleted (the node state stays)")
        } else {
            Log.w(LOG_TAG, "Couldn't delete the auth key - tried again at the next connect")
        }
    }

    /** A [Proxy] pointing at the running SOCKS5 proxy, or null if not connected yet - see
     * [MdmApi]'s `createMdmApi`, the only intended caller. Credentials are supplied separately via
     * the process-wide [Authenticator] installed in [connect], since OkHttp's [Proxy] type itself
     * carries no room for them (that's how the JDK's own SOCKS5 client is designed - proxy
     * authentication is always negotiated through [Authenticator], not the [Proxy] object). */
    fun proxy(): Proxy? {
        val addr = proxyAddress ?: return null
        val host = addr.substringBeforeLast(':')
        val port = addr.substringAfterLast(':').toIntOrNull() ?: return null
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
    }

    /**
     * [Authenticator] is a single process-wide singleton in the JDK, not something you can scope
     * to one [OkHttpClient] instance - this app has no other proxy/authenticator use anywhere
     * (confirmed: this is the only [Authenticator] reference in the codebase), so installing one
     * globally here is safe. Checks the requesting host/port match this proxy specifically before
     * handing out the credential, rather than answering unconditionally, in case that ever
     * changes.
     */
    private fun installAuthenticator(proxyAddr: String, cred: String) {
        val host = proxyAddr.substringBeforeLast(':')
        val port = proxyAddr.substringAfterLast(':').toIntOrNull()
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestingHost != host || requestingPort != port) return null
                return PasswordAuthentication(SOCKS_USERNAME, cred.toCharArray())
            }
        })
    }

    fun close() {
        val before = proxyAddress
        discard()
        proxyChanged(before)
    }

    /** [close] without telling [proxyListener]: [connect]'s failure path tells it once, at its end. */
    private fun discard() {
        try {
            client?.close()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to close tsnet client", e)
        } catch (e: LinkageError) {
            Log.w(LOG_TAG, "Failed to close tsnet client: ${e.javaClass.simpleName}")
        } finally {
            client = null
            clientParams = null
            proxyAddress = null
            proxyCredential = null
            // The security QA of S-M1, L1: nothing answers for the closed proxy's port any more - an app that binds
            // it gets no credential (and ServerTransport's sockets refuse a proxy that isn't the current one).
            Authenticator.setDefault(null)
        }
    }
}
