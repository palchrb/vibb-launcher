package com.kidslauncher.mdm.server

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.COMMAND_LISTENER_NOTIFICATION_ID
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_LISTENER
import com.kidslauncher.mdm.createListenerChannel
import com.kidslauncher.mdm.shortcuts.ShortcutsNotification
import com.kidslauncher.mdm.shortcuts.ShortcutsRuntime
import com.kidslauncher.mdm.timerules.PLAIN_NOTICE
import com.kidslauncher.mdm.timerules.TimePart
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "AnchorNotification"

/** Design 99's panel id, its removed service's own: Android removed it with the service at the update (M9); the
 * anchor's start cancels it once anyway. */
private const val DESIGN_99_PANEL_ID = 1011

/** The posted view's key in the notification's extras (Q100-L4, R2-L11): what [verify] compares with what is up. */
private const val EXTRA_VIEW_KEY = "me.vibb.anchor_view_key"

/** The trailing slot's check, after a post: Android has enqueued it by then, or shed it (M5). */
private const val VERIFY_DELAY_MS = 1_500L

/**
 * The one poster of the anchor's notification, id 1002 (design 100 §2.2-§2.3): the Snarveier panel while it is shown,
 * else r13's plain notice - one record, swapped between the two channels by `notify` (M1-M3). Nothing else posts 1002.
 *
 * - **Its own context** (Q100-M1): from whichever caller comes first - [forStart], [notice], `ShortcutsRuntime.init`.
 * - **One thread** (`anchor-post`, design 99's refresh thread moved here): every refresh, build and post, and the
 *   picker's "Vis i varsellisten igjen" ([onPosterThread], Q100-L8).
 * - **Only a change is posted** (the view, the locale, the channel), at most once a second, always with a trailing post
 *   ([AnchorPostQueue], M5); a post Android shed is caught in the trailing slot ([verify], Q100-L4).
 * - **Channels first** (M7): the view's channel is made sure of right before every build ([ensuredBuild]).
 * - **Only while the anchor runs, exactly** (Q100-L5): the running check and the `notify` are one step under [lock],
 *   which the anchor's `onDestroy` takes before it clears its instance.
 */
object AnchorNotification {
    /** The check-and-notify, the posted key, and `CommandListenerService.onDestroy`'s clear of its instance. */
    val lock = Any()

    private val poster = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "anchor-post") }
    private val queue = AnchorPostQueue()

    @Volatile
    private var appContext: Context? = null

    /** The time part [AnchorNotice] handed over last; `null` before the first. */
    @Volatile
    private var notice: TimePart? = null

    /** A view as posted: with the language its words are in and the channel it is on. */
    private data class Key(val view: AnchorView, val locale: String, val channel: String)

    /** What is up now: the last post, or the anchor's `startForeground` ([forStart]). Under [lock]. */
    private var posted: Key? = null

    /** The trailing slot's check is queued; the key re-posted once after a mismatch (poster thread). */
    private var verifyQueued = false
    private var repostedFor: Key? = null

    /** The application context, kept from the first caller (any thread). */
    fun remember(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** [AnchorNotice]'s time part was computed (the tracker's minute, a re-check, a tap, the server's answer): the view
     * follows it. Any thread. */
    fun notice(context: Context, notice: TimePart) {
        remember(context)
        this.notice = notice
        requestRefresh()
    }

    /** Any change (a Snarveier signal, the time part, the anchor's start): one refresh soon, at most one post a second,
     * the last state always posted. Any thread. */
    fun requestRefresh() {
        val delay = queue.request(SystemClock.elapsedRealtime()) ?: return
        poster.schedule({ refreshNow() }, delay, TimeUnit.MILLISECONDS)
    }

    /** [task] on the poster's thread, ordered against every build and post (the picker's "Vis i varsellisten igjen",
     * Q100-L8). */
    fun onPosterThread(task: () -> Unit): Future<*> = poster.submit(task)

    /** The Snarveier panel is what is up (poster thread, for [ShortcutsRuntime.showAgain]): its channel holds the
     * foreground-service notification and can't be deleted. */
    fun panelUp(): Boolean = synchronized(lock) { posted?.view is AnchorView.Panel }

    // ---- the refresh (poster thread) ----

    private fun refreshNow() {
        queue.started()
        val app = appContext ?: return
        // Only the anchor's notification is posted: without it nothing is shown, and its start asks again.
        if (!CommandListenerService.isRunning) return
        try {
            val time = notice ?: firstNotice(app)
            post(app, anchorView(panelFor(app, time), time))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Refresh failed: ${e.javaClass.simpleName}")
        }
    }

    /** Before [AnchorNotice]'s first hand-over in this process: the same computation; plain if it can't be made. */
    private fun firstNotice(app: Context): TimePart = try {
        AnchorNotice.noticeNow(app).also { if (notice == null) notice = it }
    } catch (e: Exception) {
        PLAIN_NOTICE
    }

    /** The panel model for [time], `null` when it can't be built (Q100-M1: the view is then the plain notice, never
     * nothing). Mer tid's state is set from this very time part first (Q100-L6). */
    private fun panelFor(app: Context, time: TimePart) = try {
        ShortcutsRuntime.panelFor(app, time.ask)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "No panel: ${e.javaClass.simpleName}")
        null
    }

    private fun post(app: Context, view: AnchorView) {
        val key = Key(view, locale(app), channelOf(app, view))
        if (key == synchronized(lock) { posted }) return
        val notification = ensuredBuild(app, key)
        synchronized(lock) {
            if (!CommandListenerService.isRunning) return
            app.getSystemService(NotificationManager::class.java)?.notify(COMMAND_LISTENER_NOTIFICATION_ID, notification)
            posted = key
        }
        queue.posted(SystemClock.elapsedRealtime())
        scheduleVerify()
    }

    /** The channel id [view] goes on; read only, made sure of in [ensuredBuild]. */
    private fun channelOf(app: Context, view: AnchorView): String = when (view) {
        is AnchorView.Plain -> NOTIFICATION_CHANNEL_LISTENER
        is AnchorView.Panel -> ShortcutsRuntime.currentChannelId(app)
    }

    /** M7: the channel made sure of on the same path, right before the build; the key tagged into the extras. */
    private fun ensuredBuild(context: Context, key: Key): Notification {
        when (key.view) {
            is AnchorView.Plain -> createListenerChannel(context)
            is AnchorView.Panel -> ShortcutsRuntime.ensureChannel(context, key.channel)
        }
        val notification = when (val view = key.view) {
            is AnchorView.Plain -> AnchorNotice.build(context, view.time)
            is AnchorView.Panel -> ShortcutsNotification.build(context, key.channel, view.panel, view.time)
        }
        notification.extras.putInt(EXTRA_VIEW_KEY, key.hashCode())
        return notification
    }

    private fun locale(context: Context): String = context.resources.configuration.locales[0]?.toLanguageTag().orEmpty()

    // ---- a shed post (Q100-L4, R2-L11) ----

    private fun scheduleVerify() {
        if (verifyQueued) return
        verifyQueued = true
        poster.schedule({
            verifyQueued = false
            verify()
        }, VERIFY_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * The trailing slot: Android sheds updates over its per-package rate without an error (M5), the launcher's other
     * notifications counting too. When 1002 is up with another channel or view than the last post, the key is
     * forgotten and the view posted again - once per view, so a mismatch that isn't a shed can't loop. Nothing up
     * (the foreground deferral, the kid's block) is nothing to compare.
     */
    private fun verify() {
        val app = appContext ?: return
        val key = synchronized(lock) { posted } ?: return
        val up = try {
            app.getSystemService(NotificationManager::class.java)?.activeNotifications
                ?.firstOrNull { it.id == COMMAND_LISTENER_NOTIFICATION_ID }?.notification
        } catch (e: Exception) {
            null
        } ?: return
        if (up.channelId == key.channel && up.extras.getInt(EXTRA_VIEW_KEY) == key.hashCode()) return
        if (repostedFor == key) return
        repostedFor = key
        Log.i(LOG_TAG, "The posted view isn't up (shed?): posting it again")
        synchronized(lock) { if (posted == key) posted = null }
        requestRefresh()
    }

    // ---- the anchor's start ----

    /**
     * The notification for the anchor's `startForeground` (main thread, `onCreate`'s first call): the view this process
     * posted last, on that key's channel - made sure of first, never the panel model and never the shortcuts prefs
     * (Q100-L7) -; with none yet, or on any exception, r13's plain notice on the listener channel. It is what is up
     * now, so it is recorded as posted. Never throws.
     */
    fun forStart(context: Context): Notification {
        val app = context.applicationContext
        remember(app)
        try {
            val last = synchronized(lock) { posted }
            if (last != null) {
                return ensuredBuild(app, last).also { queue.posted(SystemClock.elapsedRealtime()) }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The last view couldn't be built for the start: ${e.javaClass.simpleName}")
        }
        // Code QA L5: an anchor restarted in a live process starts with the time part it has, not a bare "Vibb One".
        val cached = notice
        runCatching { createListenerChannel(app) }
        val withTime = cached?.let { runCatching { AnchorNotice.plain(app, it) }.getOrNull() }
        val key = Key(AnchorView.Plain(cached?.takeIf { withTime != null } ?: PLAIN_NOTICE), locale(app), NOTIFICATION_CHANNEL_LISTENER)
        val plain = withTime ?: AnchorNotice.plain(app)
        runCatching { plain.extras.putInt(EXTRA_VIEW_KEY, key.hashCode()) }
        synchronized(lock) { posted = key }
        queue.posted(SystemClock.elapsedRealtime())
        return plain
    }

    /** The anchor's start (after `startForeground`): design 99's old panel cancelled once (M9), the view asked for. */
    fun anchorStarted(context: Context) {
        remember(context)
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(DESIGN_99_PANEL_ID) }
        requestRefresh()
    }
}
