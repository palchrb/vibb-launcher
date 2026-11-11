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
import com.kidslauncher.mdm.ui.look.nightOver

/**
 * The emergency menu's sheet (design 73, mockup variant A) in Vibb night: "Call an emergency number", the rows
 * [emergencyRows] gives (the first real emergency number large and red on top), Cancel. A tap on a row closes the
 * sheet and hands [onTap] the row's [EmergencyTap] - the sheet is the confirm. A row the call rules block now is
 * dimmed and not clickable. Only [EmergencyCall.confirm] shows it.
 */
object EmergencySheet {

    fun show(activity: Activity, rows: List<EmergencyRow>, onTap: (EmergencyTap) -> Unit): Dialog {
        // Emergency looks the same everywhere, the phone book in Lys included (design 78 §2).
        val night = nightOver(activity)
        val inflater = LayoutInflater.from(night)
        val view = inflater.inflate(R.layout.sheet_emergency, null)
        val dialog = Dialog(night, R.style.KidSheetDialog)
        dialog.setContentView(view)
        dialog.setUpKidSheet(view)
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
            sub.setText(
                when (row.kind) {
                    EmergencyRowKind.EMERGENCY -> R.string.emergency_menu_always
                    EmergencyRowKind.LOCKED -> R.string.emergency_menu_unlock
                    else -> R.string.emergency_menu_rules
                },
            )
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
        capRows(activity, view)
        dialog.show()
        return dialog
    }

    /**
     * Code QA M1: the rows get what the screen has left after the system bars (96 dp, the Jelly Star's status and
     * navigation bars at most), the title, Cancel and the sheet's own padding - measured at the current font scale -,
     * at least 120 dp; more rows scroll, Cancel always shows. The large row is the first, at the top.
     */
    private fun capRows(activity: Activity, sheet: android.view.View) {
        val metrics = activity.resources.displayMetrics
        val dp = { v: Int -> (v * metrics.density).toInt() }
        val width = android.view.View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, android.view.View.MeasureSpec.AT_MOST)
        val free = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        val title = sheet.findViewById<android.view.View>(R.id.emergency_title).apply { measure(width, free) }
        val cancel = sheet.findViewById<android.view.View>(R.id.emergency_cancel).apply { measure(width, free) }
        // The sheet's padding (12 + 16), the handle (5), the title's margins (12 + 4) and Cancel's (6).
        val reserved = dp(96) + title.measuredHeight + cancel.measuredHeight + dp(12 + 16 + 5 + 12 + 4 + 6)
        sheet.findViewById<EmergencyRowsScroll>(R.id.emergency_scroll).maxHeightPx =
            maxOf(metrics.heightPixels - reserved, dp(120))
    }

    /** Each row announces as a button (as the lock's Nødsamtale pills, qa-34-s8-code L3). */
    private object ButtonRole : android.view.View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.Button::class.java.name
        }
    }
}
