package com.kidslauncher.mdm.ui.home

import android.app.Activity
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.WindowCompat
import com.kidslauncher.mdm.R

/**
 * Home's face while the PIN lock is LOCKED, or not decided yet with a kid PIN (design 16c): the
 * night ground with the breathing Vibb mark (`splash_vibb_breathe.xml`, the boot cover's), centred,
 * from `activity_home_night.xml` - nothing to touch. Breathes only while Home is resumed.
 */
class NightGround(private val activity: Activity) {
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

    init {
        logo?.registerAnimationCallback(loop)
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
