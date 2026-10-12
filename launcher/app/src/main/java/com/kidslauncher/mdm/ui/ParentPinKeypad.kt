package com.kidslauncher.mdm.ui

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.lock.PinKeypadLayout

/** Up to this many digits: parent PINs saved before design 24's 10-digit bound can be longer. */
const val PARENT_PIN_MAX_DIGITS = 32

/** The dot row shows this many dots at most, then "+n". */
private const val DOTS_SHOWN = 12

/** One more digit, never past [PARENT_PIN_MAX_DIGITS]; only 0-9. */
fun appendParentDigit(pin: String, digit: Char): String =
    if (digit !in '0'..'9' || pin.length >= PARENT_PIN_MAX_DIGITS) pin else pin + digit

/** The dot row for [count] digits. */
fun parentPinDots(count: Int): String =
    "●".repeat(count.coerceIn(0, DOTS_SHOWN)) + if (count > DOTS_SHOWN) " +${count - DOTS_SHOWN}" else ""

/**
 * The parent PIN's own digit keypad (design 24b §1.8, QA #5): every parent-PIN dialog - the time
 * lock, the Settings gate, the pause, install mode and the PIN lock's "Parent code" - types on
 * these twelve keys with a dot row, never into an `EditText`. So the PIN doesn't depend on a
 * keyboard the kid can disable, and no keyboard the kid enabled in a window ever sees it. The views
 * are data-sensitive (only accessibility tools see them) and [secure] keeps the dialog out of
 * screenshots and screen recordings.
 */
class ParentPinKeypad private constructor(private val root: View) {
    private val dots: TextView = root.findViewById(R.id.parent_pin_dots)
    private val keys = mutableListOf<View>()

    /** What was typed so far. */
    var pin: String = ""
        private set

    init {
        build(root.findViewById(R.id.parent_pin_keys))
        root.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        render()
    }

    fun clear() {
        pin = ""
        render()
    }

    fun setEnabled(enabled: Boolean) {
        keys.forEach { it.isEnabled = enabled }
        root.alpha = if (enabled) 1f else 0.5f
    }

    private fun render() {
        dots.text = parentPinDots(pin.length)
        dots.contentDescription = root.context.getString(R.string.parent_pin_keypad_dots, pin.length)
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, root.resources.displayMetrics).toInt()

    private fun build(container: LinearLayout) {
        val context = root.context
        val bold = ResourcesCompat.getFont(context, R.font.nunito_bold)
        val size = dp(PinKeypadLayout.MIN_DP + 4f)
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del").chunked(3).forEachIndexed { rowIndex, row ->
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (rowIndex > 0) topMargin = dp(PinKeypadLayout.GAP_DP) }
            }
            for (label in row) {
                val cell = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val key: View = when (label) {
                    "" -> View(context)
                    "del" -> ImageView(context).apply {
                        setImageResource(R.drawable.ic_pin_backspace)
                        imageTintList = ColorStateList.valueOf(Color.BLACK)
                        scaleType = ImageView.ScaleType.CENTER
                        contentDescription = context.getString(R.string.pin_lock_delete)
                        setBackgroundResource(R.drawable.bg_parent_pin_key)
                        setOnClickListener {
                            pin = pin.dropLast(1)
                            render()
                        }
                        setOnLongClickListener {
                            clear()
                            true
                        }
                    }
                    else -> TextView(context).apply {
                        text = label
                        typeface = bold
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextColor(Color.BLACK)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                        setBackgroundResource(R.drawable.bg_parent_pin_key)
                        contentDescription = label
                        setOnClickListener {
                            pin = appendParentDigit(pin, label[0])
                            render()
                        }
                    }
                }
                key.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
                cell.addView(key, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                if (label.isNotEmpty()) keys += key
                rowView.addView(cell)
            }
            container.addView(rowView)
        }
    }

    companion object {
        /** Builds the keys inside an inflated `include_parent_pin_keypad`. */
        fun bind(root: View): ParentPinKeypad = ParentPinKeypad(root)

        /** No screenshots or screen recording of a parent-PIN dialog - before it is shown. */
        fun secure(dialog: Dialog) {
            dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
