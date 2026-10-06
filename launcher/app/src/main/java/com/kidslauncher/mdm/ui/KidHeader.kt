package com.kidslauncher.mdm.ui

import android.view.View
import androidx.activity.ComponentActivity
import com.kidslauncher.mdm.databinding.IncludeKidHeaderBinding

/**
 * Binds the shared full-page header (`include_kid_header.xml`: back button, title, optional text
 * action) - the phone book, kid Settings, Wi-Fi networks and Bluetooth devices all use it, so they
 * can't drift apart again (fix round 2026-10-06: the phone book's back arrow sat 16 dp further
 * left and lower than kid Settings'). [SHOW_BACK_BUTTON] is the one switch should the round back
 * buttons go in favour of system Back alone; system Back works on these pages either way (the
 * button only forwards to it). The lock screens and a call screen with a call keep Back off by
 * design.
 */
object KidHeader {
    const val SHOW_BACK_BUTTON = true

    fun bind(
        activity: ComponentActivity,
        header: IncludeKidHeaderBinding,
        title: Int,
        action: Int? = null,
        onAction: (() -> Unit)? = null,
    ) {
        header.kidHeaderBack.visibility = if (SHOW_BACK_BUTTON) View.VISIBLE else View.GONE
        header.kidHeaderBack.setOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
        header.kidHeaderTitle.setText(title)
        if (action != null && onAction != null) {
            header.kidHeaderAction.setText(action)
            header.kidHeaderAction.visibility = View.VISIBLE
            header.kidHeaderAction.setOnClickListener { onAction() }
        } else {
            header.kidHeaderAction.visibility = View.GONE
        }
    }
}
