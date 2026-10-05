package com.kidslauncher.mdm.play

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.kidslauncher.mdm.R

private const val LOG_TAG = "PlayLinkBlocked"

/**
 * Answers `market://` and `https://play.google.com/store/...` links (handy step 7, §4). The device
 * owner makes it the persistent preferred activity for them ([com.kidslauncher.mdm.server.AppEnforcer]),
 * which only works because the manifest declares matching intent filters. It is never lifted
 * (QA #2: clearing our persistent preferred activities would also drop the HOME pin) - instead,
 * whenever the Play Store isn't suspended (install mode, the phone isn't managed, an override),
 * the link is passed on to it explicitly. Otherwise the kid gets a short "ask a parent" screen.
 *
 * Only implicit intents are steered here; Play opened explicitly from inside an app's task is
 * stopped by the Play Store's suspension (QA #1).
 */
class PlayLinkBlockedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data != null && isPlayLink(data.scheme, data.host, data.path) && playStoreOpen()) {
            try {
                startActivity(
                    // A fresh intent: never pass on the caller's extras, ClipData or URI grants
                    // under our identity.
                    Intent(Intent.ACTION_VIEW, data).setPackage(PLAY_STORE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't pass the link on to the Play Store", e)
            }
            finish()
            return
        }
        setContentView(blockedView())
    }

    /** Installed and not suspended. */
    private fun playStoreOpen(): Boolean = try {
        packageManager.getApplicationInfo(PLAY_STORE, 0)
        !packageManager.isPackageSuspended(PLAY_STORE)
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        false
    }

    private fun blockedView(): LinearLayout {
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(getColor(R.color.kid_ground))
            addView(TextView(this@PlayLinkBlockedActivity).apply {
                text = getString(R.string.play_link_blocked_title)
                setTextColor(getColor(R.color.kid_ink))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@PlayLinkBlockedActivity).apply {
                text = getString(R.string.play_link_blocked_text)
                setTextColor(getColor(R.color.kid_ink_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(32))
            })
            addView(Button(this@PlayLinkBlockedActivity).apply {
                text = getString(android.R.string.ok)
                setOnClickListener { finish() }
            })
        }
    }
}
