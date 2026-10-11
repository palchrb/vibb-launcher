package com.kidslauncher.mdm.music

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import android.widget.FrameLayout
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ViewLockMediaBinding
import com.kidslauncher.mdm.databinding.ViewLockMediaRowBinding
import com.kidslauncher.mdm.lock.LockMediaUi
import com.kidslauncher.mdm.lock.LockStage
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.lock.lockMediaTicks
import com.kidslauncher.mdm.lock.lockMediaUi
import com.kidslauncher.mdm.lock.lockStageAfterChange
import com.kidslauncher.mdm.lock.lockStageAtLockUp
import com.kidslauncher.mdm.lock.lockStageDue
import com.kidslauncher.mdm.ui.KidInsets
import java.util.Locale

/**
 * The PIN lock's media view and its keypad stage (design 21 §3, design 20's two-stage lock, mockup
 * Lock.dc.html v12; the rules are the pure lock/LockMedia.kt). The media view lies in the lock's
 * frame over the keypad (and under the VoIP ring screen); the keypad's back arrow and compact row
 * are in activity_pin_lock.xml. Watching the session between the lock's onStart and onStop; every
 * time the lock comes up with the screen on ([lockStageDue]) it shows the media view while the
 * music app plays, else the keypad.
 *
 * It weakens nothing (LockMediaGuardTest): the session's play/pause, previous and next, "Lås opp"
 * and the back arrow (between the stages), and the lock's own emergency call ([onEmergency]) are
 * all it does - it never opens the music app, its session activity, a setting or a notification.
 */
class MusicLockPanel(
    private val activity: Activity,
    frame: FrameLayout,
    /** The keypad stage under the media view: hidden from accessibility while covered. */
    private val keypad: View,
    private val back: View,
    private val row: ViewLockMediaRowBinding,
    /** The keypad stage with a live session (back arrow and compact row): the clock and date give way. */
    private val onMediaKeypad: (Boolean) -> Unit,
    private val onEmergency: () -> Unit,
) {
    private val media = ViewLockMediaBinding.inflate(activity.layoutInflater, frame, true)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var stage = LockStage.KEYPAD
    /** `PinLockRuntime.screenOffs` when the stage was last decided (null = not yet). */
    private var decidedAt: Int? = null
    private var ui = LockMediaUi(mediaView = false, backArrow = false, compactRow = false)
    private var session: MusicSession? = null
    private var usable = false
    private var shownArt: Bitmap? = null
    private var artShown = false
    private val ticker = Runnable { render(lookUp = false) }
    private val changed: () -> Unit = { render() }

    init {
        KidInsets.apply(media.root)
        val locale = activity.resources.configuration.locales[0] ?: Locale.getDefault()
        val datePattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        media.lockMediaDate.format12Hour = datePattern
        media.lockMediaDate.format24Hour = datePattern
        media.lockMediaPlay.setOnClickListener { playPause() }
        media.lockMediaPrevious.setOnClickListener { MusicSessions.previous(activity, MusicSessions.reading.controller) }
        media.lockMediaNext.setOnClickListener { MusicSessions.next(activity, MusicSessions.reading.controller) }
        media.lockMediaUnlock.setOnClickListener { toStage(LockStage.KEYPAD) }
        media.lockMediaEmergency.setOnClickListener { onEmergency() }
        back.setOnClickListener { toStage(LockStage.MEDIA) }
        row.lockMediaRowPlay.setOnClickListener { playPause() }
    }

    /** onStart: watching the session; the stage decided if the lock comes up now ([resume]). */
    fun start() {
        if (!started) {
            started = true
            MusicSessions.addListener(activity, changed)
        }
        resume()
    }

    /**
     * onResume (and onStart): when the lock comes up with the screen on ([lockStageDue]) - the media
     * view while the music app plays, else the keypad; otherwise the stage the kid is on.
     */
    fun resume() {
        if (!started) return
        val interactive = activity.getSystemService(PowerManager::class.java)?.isInteractive == true
        val offs = PinLockRuntime.screenOffs
        lookUp()
        if (lockStageDue(interactive, decidedAt, offs)) {
            stage = lockStageAtLockUp(session)
            decidedAt = offs
        }
        render(lookUp = false)
    }

    /** onStop (and onDestroy): nothing watched or ticking while the lock isn't shown. */
    fun stop() {
        handler.removeCallbacks(ticker)
        if (!started) return
        started = false
        MusicSessions.removeListener(changed)
    }

    private fun lookUp() {
        val reading = MusicSessions.reading
        usable = MusicSessions.usable(activity, reading.session?.packageName)
        session = shownSession(reading.access, usable, reading.session)
    }

    private fun toStage(next: LockStage) {
        stage = next
        render(lookUp = false)
    }

    private fun playPause() = MusicSessions.playPause(activity, MusicSessions.reading.controller, session)

    private fun render(lookUp: Boolean = true) {
        handler.removeCallbacks(ticker)
        if (lookUp) lookUp() else session = shownSession(MusicSessions.reading.access, usable, MusicSessions.reading.session)
        stage = lockStageAfterChange(stage, session)
        val next = lockMediaUi(stage, session)
        if (next.backArrow != ui.backArrow) onMediaKeypad(next.backArrow)
        ui = next
        media.root.visibility = if (ui.mediaView) View.VISIBLE else View.GONE
        keypad.importantForAccessibility = if (ui.mediaView) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        back.visibility = if (ui.backArrow) View.VISIBLE else View.GONE
        row.root.visibility = if (ui.compactRow) View.VISIBLE else View.GONE
        val s = session ?: return
        val playIcon = if (s.playing) R.drawable.ic_music_pause else R.drawable.ic_music_play
        val playLabel = activity.getString(if (s.playing) R.string.music_pause else R.string.music_play)
        val art = MusicSessions.reading.art
        if (!artShown || art !== shownArt) {
            artShown = true
            shownArt = art
            media.lockMediaArt.setImageBitmap(art)
            row.lockMediaRowArt.setImageBitmap(art)
            media.lockMediaGlyph.visibility = if (art == null) View.VISIBLE else View.GONE
            row.lockMediaRowGlyph.visibility = if (art == null) View.VISIBLE else View.GONE
        }
        media.lockMediaItem.text = s.item ?: s.entry
        media.lockMediaEntry.text = if (s.item != null) s.entry else null
        val position = positionNow(s, SystemClock.elapsedRealtime())
        media.lockMediaProgress.progress = progressPermille(position, s.durationMs)
        media.lockMediaProgress.contentDescription =
            if (s.durationMs > 0) activity.getString(R.string.music_time_of, clockText(position), clockText(s.durationMs)) else clockText(position)
        media.lockMediaPlay.setImageResource(playIcon)
        media.lockMediaPlay.contentDescription = playLabel
        row.lockMediaRowEntry.text = s.entry ?: s.item
        row.lockMediaRowPlay.setImageResource(playIcon)
        row.lockMediaRowPlay.contentDescription = playLabel
        if (lockMediaTicks(ui, s)) handler.postDelayed(ticker, 1_000)
    }
}
