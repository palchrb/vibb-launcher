package com.kidslauncher.mdm.push.up

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.push.up.vendor.RaiseToForeground
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.DeviceToken
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.createMdmApi
import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "UpRuntime"

/** A REGISTER's server call: at most 20 s, inside a background broadcast's budget; Element X waits 30 s. */
private const val REGISTER_TIMEOUT_MS = 20_000L

/** A pull's or a DELETE's call. */
private const val PULL_TIMEOUT_MS = 15_000L

/** The boot instant's step when `BOOT_COUNT` can't be read (L6). */
private const val BOOT_STEP_MS = 600_000L

/** The pull's timed partial wake lock (§5.5). */
private const val PULL_WAKELOCK_MS = 30_000L

/**
 * Design 108's distributor on the phone (§5), on two single-thread executors (code QA H1):
 * - [work] runs [UpEngine] - the receiver's requests, the pulls, the policy's reconcile, every server call (each with
 *   its time budget) - so the engine needs no lock;
 * - [raise] runs the raise ([RaiseToForeground]): its sends, its binding's callbacks and its 5 s timer, never behind a
 *   server call; a sent or failed message is posted back to [work].
 * No broadcast is held across any of it: the receivers hand their request over and return (the anchor's foreground
 * service keeps the process), and every answer to an app is a broadcast of its own. The engine is built on its first
 * use; it never touches the credential-encrypted store before the first unlock ([isUserUnlocked], L6).
 *
 * Entry points: [handle] (from [UpReceiver]), [requestPull] (the anchor's `up` event and every stream reopen, every
 * sync while UP is on), [policyAccepted] (the sync's fresh policy), [packageGone] ([UpPackageReceiver]),
 * [setComponentsEnabled] (`AppEnforcer.apply`) and [diagnostics] (the PIN-gated Settings row).
 */
object UpRuntime {
    private val work: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "unified-push").apply { isDaemon = true }
    }
    private val raise: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "unified-push-raise").apply { isDaemon = true }
    }

    @Volatile
    private var engine: UpEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The raise per app while bound, and their connections ([raise] only); [raising] their count for the wake lock. */
    private val raises = HashMap<String, RaiseToForeground>()
    private val connections = HashMap<RaiseToForeground, ServiceConnection>()

    @Volatile
    private var raising = 0

    /** The late sends' ack pull ([work] only). */
    private var flush: ScheduledFuture<*>? = null

    private fun engine(context: Context): UpEngine = engine ?: synchronized(this) {
        engine ?: build(context.applicationContext).also { engine = it }
    }

    fun isUserUnlocked(context: Context): Boolean = try {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    } catch (e: Exception) {
        false
    }

    /** What the cached policy says (an unreadable or missing one: off - fail closed). */
    fun view(): UpView = try {
        UpView.of((cachedPolicy() as? CachedPolicy.Ok)?.policy)
    } catch (e: Exception) {
        UpView(false, null, emptyList(), emptyList())
    }

    private fun build(app: Context): UpEngine {
        val phone = AndroidPhone(app)
        lateinit var built: UpEngine
        built = UpEngine(
            unlocked = { isUserUnlocked(app) },
            store = { UpStore.of(app) },
            view = ::view,
            server = { serverFor() },
            phone = phone,
            log = { Log.i(LOG_TAG, it) },
            boot = { bootCount(app) },
        )
        phone.onSent = { message -> post { built.sent(message) } }
        phone.onFailed = { message -> post { built.failed(message) } }
        registerUserPresent(app)
        return built
    }

    /** This boot's count: Q1's once-per-boot re-send of an outstanding ping (design 108b §2). */
    private fun bootCount(app: Context): Int = try {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        // L6: an unreadable count errs toward re-sending: the boot instant (now minus uptime) in 10-minute steps, so it
        // changes at a restart, and a clock change only adds a re-send. Logged once.
        if (!bootFallbackLogged) {
            bootFallbackLogged = true
            Log.w(LOG_TAG, "BOOT_COUNT unreadable - the boot read from the boot instant")
        }
        ((System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / BOOT_STEP_MS).toInt()
    }

    private var bootFallbackLogged = false

    /**
     * Design 108b §3.3: the refreshes that waited for the phone to be in use go out at the next unlock. `USER_PRESENT`
     * isn't delivered to a manifest receiver, so this one is context-registered, once, in the process that holds the
     * anchor's foreground service (the engine is built here); not exported - it is a protected system broadcast. The same
     * on Android 14-17. A process that restarts registers again at its first use, and the next accepted policy covers
     * what it missed.
     */
    private fun registerUserPresent(app: Context) {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_USER_PRESENT) post { engine?.userPresent() }
                }
            }
            app.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't listen for the unlock: ${e.javaClass.simpleName}")
        }
    }

    /** [block] on the engine's executor with the store, after the first unlock (the debug hook's way in; nothing else
     * touches the store from another thread). */
    fun withStore(context: Context, block: (UpStore) -> Unit) {
        val app = context.applicationContext
        post { if (isUserUnlocked(app)) block(UpStore.of(app)) }
    }

    /** The engine's in-memory ack limits forgotten (the debug hook, after it moved stamps in the store). */
    fun forgetAcks(token: String?) {
        post { engine?.forgetAcks(token) }
    }

    /** An app's request, read in full by [UpReceiver]; queued, never awaited (no broadcast is held). */
    fun handle(context: Context, request: UpRequest) {
        val app = context.applicationContext
        post { engine(app).handle(request) }
    }

    /**
     * A REGISTER without a shared identity (L4): never answered; noted once for the diagnostics row only when its
     * claimed package (the `application` extra, unverified - code QA L1) is installed and may register, under the
     * notes' own cap ([UpStore.OLD_CONNECTOR_MAX]); the row words it as unverified.
     */
    fun noteOldConnector(context: Context, pkgHint: String?) {
        val app = context.applicationContext
        if (pkgHint == null) return
        post {
            if (!isUserUnlocked(app) || !view().mayRegister(pkgHint)) return@post
            val installed = try {
                app.packageManager.getApplicationInfo(pkgHint, PackageManager.ApplicationInfoFlags.of(0))
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            if (installed) UpStore.of(app).note(UpNote(pkgHint, NOTE_OLD_CONNECTOR, System.currentTimeMillis()))
        }
    }

    /** [pkg] was removed or its data cleared ([UpPackageReceiver]); queued, never awaited. */
    fun packageGone(context: Context, pkg: String) {
        val app = context.applicationContext
        post { engine(app).packageGone(pkg) }
    }

    /** The sync accepted [policy], fetched at [requestStartMs]: the reconcile by that policy's own view (code QA M3,
     * never the cache read back), the origin's rewrite, the queued deletes, then a pull while UP is on (§5.5). */
    fun policyAccepted(context: Context, policy: PolicyResponse, requestStartMs: Long) {
        val app = context.applicationContext
        val view = UpView.of(policy)
        val regs = policy.unifiedPush?.regs
        post {
            engine(app).policyAccepted(view, regs, requestStartMs)
        }
        requestPull(app, "sync")
    }

    /**
     * A pull (§5.5): a timed partial wake lock at once, then one pull in flight and one trailing (the engine's
     * coalescing). From the anchor's `up` event and every stream reopen, every sync while UP is on, and the late
     * sends' acks. A no-op before the first unlock and while UP is off.
     */
    fun requestPull(context: Context, reason: String) {
        val app = context.applicationContext
        if (!isUserUnlocked(app)) return
        if (!view().on) return
        acquireWakeLock(app)
        post {
            val engine = engine(app)
            if (engine.requestPull(reason)) step(engine) else releaseIfIdle()
        }
    }

    /** One pull step per task, so a REGISTER waits at most one call between them. A fault ends the pull (the engine's
     * `pullStep` resets it, code QA M2). The wake lock goes when the pull is over and no raise is bound (code QA L2). */
    private fun step(engine: UpEngine) {
        val again = try {
            engine.pullStep()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "A pull failed: ${e.javaClass.simpleName}")
            false
        }
        if (again) post { step(engine) } else releaseIfIdle()
    }

    /** The pull's wake lock released once nothing is left: no pull running and no raise bound. */
    private fun releaseIfIdle() {
        if (engine?.pullActive == true || raising > 0) return
        releaseWakeLock()
    }

    /** UP's two exported components follow the policy's `unified_push.on` (§5.1): disabled, no app sees Vibb One as
     * a distributor and the `unifiedpush://link` preferred activity resolves to nothing of ours (L3). Compare first,
     * `DONT_KILL_APP`. Never throws. */
    fun setComponentsEnabled(context: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val pm = context.packageManager
        for (cls in listOf(UpReceiver::class.java, UpLinkActivity::class.java)) {
            val component = ComponentName(context, cls)
            try {
                if (pm.getComponentEnabledSetting(component) != state) {
                    pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                    Log.i(LOG_TAG, "${cls.simpleName} ${if (on) "enabled" else "disabled"}")
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't switch ${cls.simpleName}: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The PIN-gated Settings' "Push for apps" row (§6.5): `null` before the first unlock. Reads the store directly
     * (thread-safe prefs), off the main thread. */
    fun diagnostics(context: Context): UpDiagnostics? {
        val app = context.applicationContext
        if (!isUserUnlocked(app)) return null
        val store = UpStore.of(app)
        val rows = (store.read() as? UpRead.Rows)?.rows
        return UpDiagnostics(view().on, rows, store.notes())
    }

    private fun post(block: () -> Unit) = run(work, block)

    private fun run(executor: ScheduledExecutorService, block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "App push failed: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "App push couldn't queue: ${e.javaClass.simpleName}")
        }
    }

    private fun serverFor(): UpServer? {
        val url = LauncherPreferences.mdm().serverUrl()
        val token = DeviceToken.get()
        if (url.isNullOrBlank() || token.isNullOrBlank()) return null
        return MdmUpServer(url, token)
    }

    @Synchronized
    private fun acquireWakeLock(context: Context) {
        try {
            val lock = wakeLock ?: context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kidslauncher:unified_push")
                ?.apply { setReferenceCounted(false) }
                ?.also { wakeLock = it }
            lock?.acquire(PULL_WAKELOCK_MS)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't take the pull's wake lock: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't release the pull's wake lock: ${e.javaClass.simpleName}")
        }
    }

    /** The device API through [createMdmApi] (S-M1's transport, design 76's check), blocking on the executor. */
    private class MdmUpServer(private val url: String, private val token: String) : UpServer {
        override fun register(body: UpRegisterBody): UpServer.Registered = try {
            val response = runBlocking { createMdmApi(url, token, callTimeoutMs = REGISTER_TIMEOUT_MS).upRegister(body) }
            val text = (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }
            when {
                response.isSuccessful && text != null ->
                    UpServer.Registered.Endpoint(ServerJson.decodeFromString(UpEndpointAnswer.serializer(), text).endpoint)
                response.code() == 409 && text != null ->
                    UpServer.Registered.Refused(ServerJson.decodeFromString(UpRefusalAnswer.serializer(), text).refused)
                response.code() == 400 -> UpServer.Registered.Invalid
                response.code() in 401..499 -> UpServer.Registered.Rejected(response.code())
                else -> UpServer.Registered.Network
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            UpServer.Registered.Rejected(0)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "A registration's call failed: ${e.javaClass.simpleName}")
            UpServer.Registered.Network
        }

        override fun unregister(reg: String): Boolean = try {
            val response = runBlocking { createMdmApi(url, token, callTimeoutMs = PULL_TIMEOUT_MS).upUnregister(reg) }
            response.isSuccessful || response.code() == 404
        } catch (e: Exception) {
            false
        }

        override fun pull(acks: List<String>): UpMessagesAnswer? = try {
            if (com.kidslauncher.mdm.BuildConfig.DEBUG) Log.d(LOG_TAG, "Pull acking [${acks.joinToString(" ") { it.take(8) }}]")
            val response = runBlocking { createMdmApi(url, token, callTimeoutMs = PULL_TIMEOUT_MS).upMessages(UpAckBody(acks)) }
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                Log.w(LOG_TAG, "A pull got ${response.code()}")
                null
            } else {
                response.body()?.use { ServerJson.decodeFromString(UpMessagesAnswer.serializer(), it.string()) }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "A pull failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * The connector's broadcasts, each with its explicit action (Android 15's safer intents: an explicit-package intent
     * must match the target's filter), `setPackage` and `FLAG_INCLUDE_STOPPED_PACKAGES` (a force-stopped app otherwise
     * misses it, as `MusicSync` handles for the music app). The raise per app ([RaiseToForeground]).
     */
    private class AndroidPhone(private val app: Context) : UpPhone, RaiseToForeground.Platform {
        var onSent: (RaiseToForeground.Message) -> Unit = {}
        var onFailed: (RaiseToForeground.Message) -> Unit = {}

        private fun broadcast(pkg: String, action: String, extras: Intent.() -> Unit) {
            try {
                app.sendBroadcast(Intent(action).setPackage(pkg).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).apply(extras))
                Log.i(LOG_TAG, "${action.substringAfterLast('.')} to $pkg")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't send ${action.substringAfterLast('.')} to $pkg: ${e.javaClass.simpleName}")
            }
        }

        override fun newEndpoint(pkg: String, token: String, endpoint: String, id: String) = broadcast(pkg, UpSpec.NEW_ENDPOINT) {
            putExtra(UpSpec.EXTRA_TOKEN, token)
            putExtra(UpSpec.EXTRA_ENDPOINT, endpoint)
            putExtra(UpSpec.EXTRA_ID, id)
        }

        override fun registrationFailed(pkg: String, token: String, reason: String) = broadcast(pkg, UpSpec.REGISTRATION_FAILED) {
            putExtra(UpSpec.EXTRA_TOKEN, token)
            putExtra(UpSpec.EXTRA_REASON, reason)
        }

        override fun unregistered(pkg: String, token: String) = broadcast(pkg, UpSpec.UNREGISTERED) {
            putExtra(UpSpec.EXTRA_TOKEN, token)
        }

        /** Installed and visible (suspended included); hidden (still installed, invisible to a plain query); gone. */
        override fun appState(pkg: String): UpAppState {
            val pm = app.packageManager
            return try {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                UpAppState.INSTALLED
            } catch (e: PackageManager.NameNotFoundException) {
                try {
                    val info = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
                    if (info.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED != 0) UpAppState.HIDDEN else UpAppState.UNINSTALLED
                } catch (e: PackageManager.NameNotFoundException) {
                    UpAppState.UNINSTALLED
                }
            }
        }

        /** Interactive and not in Doze (design 108b §3.3): `PowerManager` on 14-17 alike. */
        override fun inUse(): Boolean = try {
            app.getSystemService(PowerManager::class.java)?.let { it.isInteractive && !it.isDeviceIdleMode } == true
        } catch (e: Exception) {
            false
        }

        /** On the [raise] executor: the raise and its send never wait behind a server call. */
        override fun deliver(pkg: String, message: RaiseToForeground.Message) = run(raise) {
            val instance = raises.getOrPut(pkg) {
                RaiseToForeground(pkg, this, { onSent(it) }, { gone -> raises.remove(pkg, gone) }, { onFailed(it) })
            }
            instance.raiseAndSend(message)
        }

        override fun pullLater(delayMs: Long) {
            if (flush?.isDone == false) return
            flush = work.schedule({ requestPull(app, "ack") }, delayMs, TimeUnit.MILLISECONDS)
        }

        override fun hasRaiseService(app: String): Boolean = try {
            this.app.packageManager.queryIntentServices(
                Intent(UpSpec.RAISE_TO_FOREGROUND).setPackage(app),
                PackageManager.ResolveInfoFlags.of(0),
            ).any { it.serviceInfo?.exported == true }
        } catch (e: Exception) {
            false
        }

        override fun bind(app: String, connection: RaiseToForeground): Boolean {
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) = connection.onServiceConnected()
                override fun onServiceDisconnected(name: ComponentName?) = connection.onServiceDisconnected()
                override fun onBindingDied(name: ComponentName?) = connection.onBindingDied()
                override fun onNullBinding(name: ComponentName?) = connection.onNullBinding()
            }
            val bound = this.app.bindService(
                Intent(UpSpec.RAISE_TO_FOREGROUND).setPackage(app),
                Context.BIND_AUTO_CREATE,
                raise,
                conn,
            )
            if (bound) {
                connections[connection] = conn
                raising = connections.size
            } else {
                // A false bindService still holds the connection until it is unbound.
                runCatching { this.app.unbindService(conn) }
            }
            return bound
        }

        override fun unbind(connection: RaiseToForeground) {
            try {
                connections.remove(connection)?.let { this.app.unbindService(it) }
            } finally {
                raising = connections.size
                if (raising == 0) post { releaseIfIdle() }
            }
        }

        override fun send(app: String, message: RaiseToForeground.Message) {
            if (message.kind == RaiseToForeground.Kind.NEW_ENDPOINT) {
                this.app.sendBroadcast(
                    Intent(UpSpec.NEW_ENDPOINT).setPackage(app).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        .putExtra(UpSpec.EXTRA_TOKEN, message.token)
                        .putExtra(UpSpec.EXTRA_ENDPOINT, message.endpoint)
                        .putExtra(UpSpec.EXTRA_ID, message.id),
                )
                Log.i(LOG_TAG, "NEW_ENDPOINT to $app (raised)")
                return
            }
            this.app.sendBroadcast(
                Intent(UpSpec.MESSAGE).setPackage(app).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(UpSpec.EXTRA_TOKEN, message.token)
                    .putExtra(UpSpec.EXTRA_BYTES_MESSAGE, message.content)
                    .putExtra(UpSpec.EXTRA_ID, message.id),
            )
            // Design 108 R1-G row 10: a key's send and its ack, in order (debug builds only; random keys, no content).
            if (com.kidslauncher.mdm.BuildConfig.DEBUG) Log.d(LOG_TAG, "MESSAGE sent to $app: ${message.id.take(8)}")
        }

        override fun schedule(delayMs: Long, action: () -> Unit): RaiseToForeground.Cancellable {
            val future = raise.schedule({
                try {
                    action()
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "The raise's timeout failed: ${e.javaClass.simpleName}")
                }
            }, delayMs, TimeUnit.MILLISECONDS)
            return RaiseToForeground.Cancellable { future.cancel(false) }
        }

        private val noted = HashSet<String>()

        override fun noRaiseService(app: String) {
            if (!noted.add(app)) return
            Log.i(LOG_TAG, "$app has no raise service: its messages go without the raise")
            // The store is the work executor's.
            post { if (isUserUnlocked(this.app)) UpStore.of(this.app).note(UpNote(app, NOTE_NO_RAISE, System.currentTimeMillis())) }
        }
    }
}

/** The diagnostics row's data: [on], the rows (`null`: the store doesn't open), the notes. */
data class UpDiagnostics(val on: Boolean, val rows: List<UpRow>?, val notes: List<UpNote>)
