package com.kidslauncher.mdm.lock

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val LOG_TAG = "HomeFront"

/**
 * Every "bring Home to the front" start (design 16, QA #1): the boot start, the lock leaving
 * through Home, the lock asking Home to root lock task, the update, a role change and the end of
 * Play's install mode. An explicit component (`Intent(context, HomeActivity::class.java)`) is
 * HOME-typed only for system, recents or resolver callers, so it made a STANDARD Home task and a
 * later Home key or the stock launcher's hand-over a second, HOME-typed one (singleTask doesn't
 * match across activity types). A MAIN + HOME intent restricted to our package, with no
 * component and HOME as its only category, is resolved by the platform to HomeActivity and typed
 * HOME - our other HOME, the boot cover (16b), is disabled whenever our process runs unlocked,
 * except after `ACTION_SHUTDOWN`, when no Home is started at all ([BootCover.shuttingDown]).
 * `HomeFrontTest` keeps every start on this path.
 *
 * Design 32 (QA M2): the lock-up's start, while dark, has no open animation (`animate = false`) - a
 * transition queued while the display sleeps plays at the next screen-on (bug 25b-B). The intent
 * stays typed HOME: the flag adds no category or component.
 *
 * Design 59d step B (Q59d-7): the face's leave starts Home with no slide either (`noAnimation`) - unlike `animate =
 * false` it still ends the dark mark and still runs on the main thread. `HomeFrontTest` pins the two callers.
 */
object HomeFront {

    private val main = Handler(Looper.getMainLooper())

    /** Marks our own starts (launcher round 7 §4): Home's `onNewIntent` scrolls to the top only for
     * the kid's Home press, never for these (the lock leaving, the boot, the lock-up, an update). */
    const val EXTRA_BROUGHT = "me.vibb.launcher.extra.HOME_BROUGHT"

    /**
     * Design 59d step B (the user's rc.2 recording: Home drawn on black for 3 frames after the pad, then its wallpaper):
     * the face's unanimated leave brought Home while the keyguard goes away, and Android's wallpaper window isn't shown
     * yet under Home's transparent window. So Home's next resume draws its own ground until it stops
     * (`WallpaperGround`'s `ownGround`). Set only by a `noAnimation` start (the face's leave); taken by Home's resume.
     */
    @Volatile
    private var ownGroundDue = false

    /** Home's resume: whether the face's leave just brought it ([ownGroundDue]) - read once. */
    fun takeOwnGroundDue(): Boolean = ownGroundDue.also { ownGroundDue = false }

    fun intent(context: Context, animate: Boolean = true, noAnimation: Boolean = false): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(context.packageName)
            .addFlags(if (animate && !noAnimation) Intent.FLAG_ACTIVITY_NEW_TASK else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_BROUGHT, true)

    /** Design 59d step A (qa-59b-code B-1): a Home start after a policy edge (the relock, install mode's end, the update,
     * a role change) - as Android's face with its keyguard locked it waits for the unlock
     * ([KeyguardCover.afterAndroidUnlock]); at once otherwise. */
    fun bringAfterUnlock(context: Context, why: String) =
        KeyguardCover.afterAndroidUnlock(context, "Home ($why)") { bring(it, why) }

    /**
     * Starts our Home; `false` if the platform refused. [animate] false: no open transition - only
     * design 32's lock-up passes it. Every other start ends the lock-up's dark mark first
     * ([PinLockRuntime.darkLockUp]): that Home is wanted in front - the lock asking it to root lock
     * task, an update, a relock - and its locked resume shows the lock again, as before design 32.
     *
     * Any thread, but such a start from another thread runs on the main thread (qa-32-s1-code L2):
     * the lock-up marks and starts there in one pass, so a background end-and-start can't land
     * between them and leave Home, with the dark mark, over the lock. Posted, it answers `true` (the
     * background callers ignore the answer; the boot's, which reads it, runs on the main thread).
     */
    fun bring(context: Context, why: String, animate: Boolean = true, noAnimation: Boolean = false): Boolean {
        if (animate && Looper.myLooper() != Looper.getMainLooper()) {
            val app = context.applicationContext
            main.post { bring(app, why, noAnimation = noAnimation) }
            return true
        }
        return if (BootCover.shuttingDown) {
            Log.i(LOG_TAG, "Not bringing Home to the front during shutdown: $why")
            false
        } else try {
            if (animate) PinLockRuntime.endDarkLockUp()
            if (noAnimation) ownGroundDue = true
            context.startActivity(intent(context, animate, noAnimation))
            Log.i(LOG_TAG, "Home brought to the front: $why")
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't bring Home to the front ($why)", e)
            false
        }
    }
}
