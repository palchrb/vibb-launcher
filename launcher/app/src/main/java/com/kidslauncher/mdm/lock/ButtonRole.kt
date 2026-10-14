package com.kidslauncher.mdm.lock

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button

/**
 * TalkBack reads a clickable `LinearLayout` (the lock's Nødsamtale pills: the keypad's and the VoIP
 * ring card's) without a role. This delegate gives it the Button role - "Nødsamtale, knapp"
 * (qa-34-s8-code L3). It changes only what TalkBack says; the click path is the view's own.
 */
object ButtonRole : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = Button::class.java.name
    }
}
