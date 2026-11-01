package com.kidslauncher.mdm.calls

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.kidslauncher.mdm.R

/**
 * The emergency menu's sheet (design 73, mockup variant A) in Vibb night: "Call an emergency number", the rows
 * [emergencyRows] gives (the first real emergency number large and red on top), Cancel. A tap on a row closes the
 * sheet and hands [onTap] the row's [EmergencyTap] - the sheet is the confirm. A row the call rules block now is
 * dimmed and not clickable. Only [EmergencyCall.confirm] shows it.
 */
object EmergencySheet {

    fun show(activity: Activity, rows: List<EmergencyRow>, onTap: (EmergencyTap) -> Unit): Dialog {
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.sheet_emergency, null)
        val dialog = Dialog(activity, R.style.KidSheetDialog)
        dialog.setContentView(view)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        val list = view.findViewById<LinearLayout>(R.id.emergency_rows)
        val defaultName = activity.getString(R.string.emergency_menu_default_name)
        for (row in rows) {
            val item = inflater.inflate(R.layout.item_emergency_row, list, false)
            val text = emergencyRowText(row, defaultName)
            val number = item.findViewById<TextView>(R.id.emergency_row_number)
            val name = item.findViewById<TextView>(R.id.emergency_row_name)
            val sub = item.findViewById<TextView>(R.id.emergency_row_sub)
            val glyph = item.findViewById<ImageView>(R.id.emergency_row_glyph)
            number.text = text.number.orEmpty()
            number.visibility = if (text.number == null) android.view.View.GONE else android.view.View.VISIBLE
            name.text = text.label
            sub.setText(if (row.kind == EmergencyRowKind.EMERGENCY) R.string.emergency_menu_always else R.string.emergency_menu_rules)
            if (row.kind != EmergencyRowKind.EMERGENCY) sub.setTextColor(activity.getColor(R.color.kid_accent))
            glyph.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.kid_emergency_text))
            if (row.big) {
                item.setBackgroundResource(R.drawable.bg_emergency_row_big)
                item.minimumHeight = (76 * activity.resources.displayMetrics.density).toInt()
                number.textSize = 32f
                val white = activity.getColor(R.color.kid_ink)
                number.setTextColor(white)
                name.setTextColor(white)
                name.textSize = 18f
                sub.setTextColor(activity.getColor(R.color.kid_emergency_big_sub))
                glyph.imageTintList = ColorStateList.valueOf(white)
            }
            item.contentDescription = activity.getString(
                R.string.emergency_menu_row_description,
                listOfNotNull(text.number, text.label.ifEmpty { null }).joinToString(" "),
                sub.text,
            )
            item.accessibilityDelegate = ButtonRole
            val tap = emergencyTap(row)
            if (tap == EmergencyTap.Nothing) {
                item.isEnabled = false
                item.alpha = 0.4f
            } else {
                item.setOnClickListener {
                    dialog.dismiss()
                    onTap(tap)
                }
            }
            list.addView(item)
        }
        view.findViewById<TextView>(R.id.emergency_cancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
        return dialog
    }

    /** Each row announces as a button (as the lock's Nødsamtale pills, qa-34-s8-code L3). */
    private object ButtonRole : android.view.View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.Button::class.java.name
        }
    }
}
