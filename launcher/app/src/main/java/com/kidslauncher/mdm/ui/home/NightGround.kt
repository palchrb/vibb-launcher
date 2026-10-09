package com.kidslauncher.mdm.ui.home

import android.app.Activity
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.WindowCompat
import com.kidslauncher.mdm.R

/**
 * Home's face while the PIN lock is LOCKED, or not decided yet with a kid PIN (design 16c): the
 * night ground with the breathing Vibb mark (`splash_vibb_breathe.xml`, the boot cover's), centred,
 * from `activity_home_night.xml` - nothing to touch. Breathes only while Home is resumed.
 * [onFirstFrame] runs at its first drawn frame ([drawn]) - an `OnDrawListener`, which runs only when
 * a frame is really drawn (not with the display off). Design 16e: with the window focused too, the
 * boot's 3 s start (qa-16e-fix2 #1).
 */
class NightGround(private val activity: Activity, private val onFirstFrame: () -> Unit = {}) {
    val root: View = LayoutInflater.from(activity).inflate(R.layout.activity_home_night, FrameLayout(activity), false)
    private val logo = root.findViewById<ImageView>(R.id.home_night_mark).drawable as? AnimatedVectorDrawable
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val next = Runnable { if (running) logo?.start() }
    private val loop = object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            // A pause between breaths (the generated animation is one breath, < 1 s).
            if (running) handler.postDelayed(next, BREATH_PAUSE_MS)
        }
    }

    /** The ground has drawn a frame. */
    var drawn = false
        private set
    private val firstDraw = object : ViewTreeObserver.OnDrawListener {
        override fun onDraw() {
            if (drawn) return
            drawn = true
            // Not inside the draw (and the listener isn't removable there).
            handler.post {
                root.viewTreeObserver.removeOnDrawListener(this)
                onFirstFrame()
            }
        }
    }

    init {
        logo?.registerAnimationCallback(loop)
        // On the window's own tree observer, each time the ground is (re)attached until it drew.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (!drawn) v.viewTreeObserver.addOnDrawListener(firstDraw)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnDrawListener(firstDraw)
            }
        })
    }

    /** Light status/navigation-bar icons on the night ground (Home's content sets them by ink). */
    fun applyBars() {
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    fun start() {
        if (running) return
        running = true
        logo?.start()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(next)
        logo?.stop()
    }

    private companion object {
        const val BREATH_PAUSE_MS = 700L
    }
}
