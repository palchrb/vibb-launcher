package me.vibb.music.bridge

import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.RestrictionsManager
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.LocaleList
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import me.vibb.bridge.IMusicBridge
import java.security.MessageDigest

private const val LOG_TAG = "MusicBridge"

/**
 * The music app's side of the bridge (design 21 §2): the managed config, the signer check, and
 * binding the launcher's `MusicBridgeService` - for one sync ([withBridge]) or while the player or
 * the screens live ([LiveBridge], rebound when the launcher updates itself).
 */
object Bridge {
    /** The managed config now. */
    fun config(context: Context): BridgeConfig {
        val bundle = runCatching { context.getSystemService(RestrictionsManager::class.java).applicationRestrictions }.getOrNull()
            ?: return BridgeConfig.UNMANAGED
        @Suppress("DEPRECATION") // Bundle.get: plain values
        val values = bundle.keySet().associateWith { bundle.get(it) }
        return bridgeConfig(values)
    }

    /** Is [pkg] signed with this app's own certificate? */
    fun sameSigner(context: Context, pkg: String): Boolean = try {
        val pm = context.packageManager
        val own = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners.orEmpty()
        own.isNotEmpty() && own.all { cert ->
            pm.hasSigningCertificate(pkg, MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()), PackageManager.CERT_INPUT_SHA256)
        }
    } catch (e: Exception) {
        false
    }

    /** The launcher to bind, when the config names one and it is signed like this app. */
    fun launcher(context: Context, config: BridgeConfig = config(context)): String? =
        config.launcherPackage?.takeIf { launcherAllowed(it) { pkg -> sameSigner(context, pkg) } }

    fun intent(pkg: String): Intent = Intent().setComponent(ComponentName(pkg, BRIDGE_SERVICE))

    /**
     * [block] with the launcher's bridge bound (off the main thread); null when the app isn't
     * managed, the launcher isn't one it trusts, or the bridge doesn't answer in 10 s. A launcher
     * that goes away mid-call (its own update) is a RemoteException: the sync's bridge part stops.
     */
    suspend fun <T> withBridge(context: Context, block: suspend (IMusicBridge) -> T): T? {
        val pkg = launcher(context) ?: return null
        val connected = CompletableDeferred<IMusicBridge?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected.complete(service?.let { IMusicBridge.Stub.asInterface(it) })
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
            override fun onBindingDied(name: ComponentName?) {
                connected.complete(null)
            }

            override fun onNullBinding(name: ComponentName?) {
                connected.complete(null)
            }
        }
        val bound = try {
            context.bindService(intent(pkg), connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "The launcher refused the bridge (another signer?)")
            false
        }
        try {
            if (!bound) return null
            val bridge = withTimeoutOrNull(10_000) { connected.await() } ?: return null
            return block(bridge)
        } catch (e: RemoteException) {
            Log.w(LOG_TAG, "The bridge went away mid-sync", e)
            return null
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    /** The launcher's language for this app ("" = the phone's), set when it changed (21 §2). */
    fun applyLanguage(context: Context, config: BridgeConfig) {
        if (!config.managed) return
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        if (lm.applicationLocales.toLanguageTags() != config.language) lm.applicationLocales = LocaleList.forLanguageTags(config.language)
    }
}

/**
 * A bridge held while the player or the screens live: `setNowPlaying` and `setForeground` go out
 * at once, and are sent again after a rebind. A bind that didn't happen (no config yet, the
 * launcher mid-update) is tried again after 2 s, 10 s and 60 s, and whenever the managed config
 * changes ([refresh]) - qa-21-step3-code L4. Main thread.
 */
object LiveBridge {
    private val main = Handler(Looper.getMainLooper())
    private val token = Binder() // dies with this process: the launcher clears "in front" and "playing"
    private val retries = listOf(2_000L, 10_000L, 60_000L)
    private var users = 0
    private var attempt = 0
    private var app: Context? = null
    private var bridge: IMusicBridge? = null
    private var connection: ServiceConnection? = null
    private var nowPlaying: Bundle? = null
    private var inFront = false

    /** A user (the player service, the activity) starts needing the bridge. */
    fun acquire(context: Context) {
        app = context.applicationContext
        if (users++ == 0) {
            attempt = 0
            bind()
        }
    }

    fun release(context: Context) {
        if (users == 0) return
        if (--users == 0) {
            main.removeCallbacks(retry)
            unbind(context.applicationContext)
        }
    }

    /** The managed config changed: bind now if a user needs the bridge and it isn't bound. */
    fun refresh(context: Context) {
        app = context.applicationContext
        if (users > 0 && connection == null) {
            attempt = 0
            main.removeCallbacks(retry)
            bind()
        }
    }

    fun setNowPlaying(state: Bundle?) {
        nowPlaying = state
        call { it.setNowPlaying(state) }
    }

    fun setForeground(front: Boolean) {
        if (inFront == front) return
        inFront = front
        call { it.setForeground(token, front) }
    }

    private fun call(action: (IMusicBridge) -> Unit) {
        val b = bridge ?: return
        try {
            action(b)
        } catch (e: RemoteException) {
            bridge = null
        }
    }

    private val retry = Runnable { if (users > 0 && connection == null) bind() }

    private fun scheduleRetry() {
        if (users == 0 || attempt >= retries.size) return
        main.postDelayed(retry, retries[attempt++])
    }

    private fun bind() {
        val app = app ?: return
        val pkg = Bridge.launcher(app) ?: return scheduleRetry()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                attempt = 0
                bridge = service?.let { IMusicBridge.Stub.asInterface(it) }
                call { it.setNowPlaying(nowPlaying) }
                call { it.setForeground(token, inFront) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bridge = null
            }

            override fun onBindingDied(name: ComponentName?) {
                // The launcher was updated: bind again, with the retries.
                unbind(app)
                attempt = 0
                scheduleRetry()
            }
        }
        val ok = try {
            app.bindService(Bridge.intent(pkg), conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        if (ok) {
            connection = conn
        } else {
            runCatching { app.unbindService(conn) }
            scheduleRetry()
        }
    }

    private fun unbind(app: Context) {
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        bridge = null
    }
}
