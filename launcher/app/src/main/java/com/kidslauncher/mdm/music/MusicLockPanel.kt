package com.kidslauncher.mdm.music

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.view.View
import android.widget.TextView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ViewLockGlanceBinding
import com.kidslauncher.mdm.lock.GLANCE_MINUS
import com.kidslauncher.mdm.lock.GlanceTileSize
import com.kidslauncher.mdm.lock.glanceTicks
import com.kidslauncher.mdm.lock.glanceTimeWidest
import com.kidslauncher.mdm.lock.glanceTimes
import kotlin.math.ceil

/**
 * Media on the PIN lock (design 21 step 4, design 25; since 2026-11-04 any app the parent allowed
 * and that is usable now, `lockTilePick`: the playing one first, Vibb music first among equals - Home's
 * card stays Vibb music's): the glance page's full and compact
 * tiles, bound while a session is live and inside the listening window (design 28: playing, or 10
 * minutes after the end of play or a fresh headset - Home's card's rule, [shown]; never the
 * snapshot, which has no controls without opening the player) (the keypad stage has no music since
 * 25b-G, user decision 2026-10-12: it stays plain). No stage logic - the glance
 * panel (lock/GlancePanel) decides whether a tile shows and which ([show], from its fit); this binds
 * the session's art, item, entry, progress, the full tile's times and play/pause into them, watches
 * the session between the lock's onStart and onStop and tells the panel when it changed ([onChange];
 * the panel coalesces the callbacks of a frame, looks the session up and renders the tile alone - r6
 * §2). The once-a-second tick moves only the bars and the full tile's times ([tick]): no text that
 * changes the tile's height, and no layout while the times keep their shape.
 *
 * It weakens nothing (LockMediaGuardTest): the picked session's play/pause, previous and next are all
 * it does - on that app's own session - it never opens the app, its session activity, a custom action,
 * a setting or a notification.
 */
class MusicLockPanel(
    private val activity: Activity,
    private val glance: ViewLockGlanceBinding,
    private val onChange: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var session: MusicSession? = null
    /** The session [shown] picked, whose controller the buttons act on. */
    private var picked: MusicSessions.Live? = null
    /** The apps whose session may show ([MusicSessions.lockUsableApps]), looked up by [lookUp] only at the
     * start and when [MusicSessions.lockGeneration] moved (a sessions-list change, a suspension). */
    private var usableApps: Set<String> = emptySet()
    private var usableGeneration = -1
    private var tile = GlanceTileSize.NONE
    private var pageShown = false
    private var shownArt: Bitmap? = null
    private var artShown = false
    private val ticker = Runnable { tick() }
    private val changed: () -> Unit = { onChange() }
    /** The listening window's end while a paused session shows: the panel lays the page out without the tile (design 28 §2.4). */
    private val expiry = Runnable { changed() }

    init {
        glance.lockMediaPlay.setOnClickListener { playPause() }
        glance.lockMediaPrevious.setOnClickListener { usablePick()?.let { MusicSessions.previous(activity, it.controller) } }
        glance.lockMediaNext.setOnClickListener { usablePick()?.let { MusicSessions.next(activity, it.controller) } }
        glance.lockMediaCompactPlay.setOnClickListener { playPause() }
    }

    /** onStart: watching the session and the listening window. */
    fun start() {
        if (started) return
        started = true
        usableGeneration = -1
        MusicSessions.addListener(activity, changed, everyApp = true)
        MusicListening.addListener(changed)
    }

    /** onStop (and onDestroy): nothing watched or ticking while the lock isn't shown. */
    fun stop() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(expiry)
        if (!started) return
        started = false
        MusicSessions.removeListener(changed)
        MusicListening.removeListener(changed)
    }

    /** The session the lock may show, looked up again ([shown]); a paused one's window end is
     * rechecked once (at least 1 ms later, so the end is never re-rendered in a loop). */
    fun lookUp(): MusicSession? {
        handler.removeCallbacks(expiry)
        if (usableGeneration != MusicSessions.lockGeneration || !started) {
            usableGeneration = MusicSessions.lockGeneration
            usableApps = MusicSessions.lockUsableApps(activity, MusicSessions.reading.live)
        }
        val now = SystemClock.elapsedRealtime()
        val tileNow = shown(now)
        session = tileNow.session
        // Only while started: stop() removes the expiry, and a post after it would outlive the lock.
        if (started) musicRecheckInMs(tileNow.hideAtElapsedMs, now)?.let { handler.postDelayed(expiry, it) }
        return session
    }

    /**
     * The one read of what the lock shows, for [lookUp] and [render] alike: the pure
     * [lockTilePick] over every live session (none without listener access; an app not allowed,
     * suspended or gone never; each kept only while it plays or inside its listening window - design
     * 28, QA L7 - a live session only, never the snapshot), with the window's end for [lookUp]'s
     * recheck (null while playing). Sets [picked].
     */
    private fun shown(nowElapsedMs: Long = SystemClock.elapsedRealtime()): WindowedSession {
        val reading = MusicSessions.reading
        val now = BootStamp(nowElapsedMs, MusicListening.bootCount())
        val candidates = reading.live.map { LockCandidate(it.session, it.vibb, it.session.packageName in usableApps) }
        val pick = lockTilePick(reading.access, candidates, MusicListening.listening(), now)
        picked = pick?.let { reading.live[it.index] }
        return pick?.tile ?: WindowedSession(null, null)
    }

    /** The glance panel's choice: the full tile, the compact one or none; [page]: the glance page
     * shows (not the keypad stage) - the progress ticks only then. */
    fun show(size: GlanceTileSize, page: Boolean) {
        tile = size
        pageShown = page
        render()
    }

    /** The picked session if its app may still be used on the lock - the full check again at the tap (code QA L1). */
    private fun usablePick(): MusicSessions.Live? =
        picked?.takeIf { it.session.packageName in MusicSessions.lockUsableApps(activity, listOf(it)) }

    /** A pause always goes; a play only while the app may be used ([usablePick]). */
    private fun playPause() {
        val p = (if (session?.playing == true) picked else usablePick()) ?: return
        MusicSessions.playPause(activity, p.controller, session)
    }

    private fun render() {
        handler.removeCallbacks(ticker)
        session = shown().session
        val s = session
        val size = if (s == null) GlanceTileSize.NONE else tile
        glance.lockMediaTile.visibility = if (size == GlanceTileSize.FULL) View.VISIBLE else View.GONE
        glance.lockMediaCompact.visibility = if (size == GlanceTileSize.COMPACT) View.VISIBLE else View.GONE
        if (s == null) return
        val playIcon = if (s.playing) R.drawable.ic_music_pause else R.drawable.ic_music_play
        val playLabel = activity.getString(if (s.playing) R.string.music_pause else R.string.music_play)
        val art = picked?.art
        if (!artShown || art !== shownArt) {
            artShown = true
            shownArt = art
            glance.lockMediaArt.setImageBitmap(art)
            glance.lockMediaCompactArt.setImageBitmap(art)
            val glyph = if (art == null) View.VISIBLE else View.GONE
            glance.lockMediaGlyph.visibility = glyph
            glance.lockMediaCompactGlyph.visibility = glyph
        }
        val item = s.item ?: s.entry
        val entry = if (s.item != null) s.entry else null
        glance.lockMediaItem.text = item
        glance.lockMediaEntry.text = entry
        glance.lockMediaCompactItem.text = item
        glance.lockMediaCompactEntry.text = entry
        for (button in listOf(glance.lockMediaPlay, glance.lockMediaCompactPlay)) {
            button.setImageResource(playIcon)
            button.contentDescription = playLabel
        }
        tick()
    }

    /** The bars and the full tile's times at the position now; again in a second while the tile ticks. */
    private fun tick() {
        handler.removeCallbacks(ticker)
        val s = session ?: return
        val position = lockPositionNow(s, SystemClock.elapsedRealtime())
        val progress = progressPermille(position, s.durationMs)
        val progressText = if (s.durationMs > 0) {
            activity.getString(R.string.music_time_of, clockText(position), clockText(s.durationMs))
        } else {
            clockText(position)
        }
        for (bar in listOf(glance.lockMediaProgress, glance.lockMediaCompactProgress)) {
            bar.progress = progress
            bar.contentDescription = progressText
        }
        // The full tile's times only (r9 §3); the compact one has its bar's description. The time left goes
        // without a duration (a live stream: the elapsed time alone, the bar to the edge).
        val times = glanceTimes(position, s.durationMs)
        val elapsed = glance.lockMediaElapsed
        fixWidth(elapsed, glanceTimeWidest(s.durationMs, position))
        elapsed.text = times.elapsed
        val remaining = glance.lockMediaRemaining
        remaining.visibility = if (times.remaining != null) View.VISIBLE else View.GONE
        if (times.remaining != null) {
            fixWidth(remaining, GLANCE_MINUS + glanceTimeWidest(s.durationMs))
            remaining.text = times.remaining
        }
        if (glanceTicks(tile != GlanceTileSize.NONE, pageShown, s)) handler.postDelayed(ticker, 1_000)
    }

    /**
     * A time's fixed width (r9 §3): the widest text of its shape at the view's own paint (the font scale
     * and Nunito's kerning count) plus 1 dp, so it is never cut and no tick changes it - set through the
     * layout params only when the px differ (a new track, a duration that became known, a font-scale
     * change via the panel's refit, a position passing a stale duration's shape - qa-r9-revision-check N6).
     */
    private fun fixWidth(view: TextView, widest: String) {
        val px = ceil(Layout.getDesiredWidth(widest, view.paint)).toInt() + ceil(activity.resources.displayMetrics.density).toInt()
        val params = view.layoutParams
        if (params.width != px) {
            params.width = px
            view.layoutParams = params
        }
    }
}
