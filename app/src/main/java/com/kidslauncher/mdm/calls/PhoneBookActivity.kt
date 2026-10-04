package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.ui.UIObjectActivity
import com.kidslauncher.mdm.R

/**
 * The phone app's dialling screen - the DIAL handler the dialer role requires. Shows the phone
 * book; a `tel:` number from another app asks before calling. No free keypad.
 */
class PhoneBookActivity : UIObjectActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallPolicyStore.ensureLoaded(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        setContentView(ScrollView(this).apply { addView(list) })
        setTitle(R.string.calls_phone_book)
        render()
        handleNumber(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNumber(intent)
    }

    private fun render() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            setText(R.string.calls_phone_book_empty)
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun handleNumber(intent: Intent?) {
        val number = PhoneNumbers.numberFromHandle(intent?.data?.toString()) ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.calls_confirm_title, number))
            .setPositiveButton(R.string.calls_call) { _, _ -> CallSystem.placeCall(this, number) }
            .setNegativeButton(R.string.calls_cancel, null)
            .show()
    }

    companion object {
        fun intent(context: Context) = Intent(context, PhoneBookActivity::class.java)
    }
}
