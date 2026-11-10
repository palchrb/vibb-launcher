package com.kidslauncher.mdm.ui.search

import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.ui.NoTextActions

/** [text] with the search's matched part in peach (design 79 §4.1: 8.6:1 on the row's tile); as it is without a
 * match or with a span outside it. */
fun highlightMatch(context: android.content.Context, text: String, match: SearchMatch?): CharSequence {
    if (match == null || match.start >= match.end || match.end > text.length) return text
    return android.text.SpannableString(text).apply {
        setSpan(
            android.text.style.ForegroundColorSpan(context.getColor(R.color.kid_accent)),
            match.start, match.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

/**
 * The kid's search pill on a screen (design 79 §4.3, `view_kid_search.xml`): the phone book and Meldinger (its list and
 * "Ny melding") include it. Every keystroke calls [onQuery]; the "×" and Back (the screen's own callback, [clear]) empty
 * it and close the keyboard; the keyboard's Search closes it. The field never takes focus by itself (the container
 * takes the window's first focus), has no menu or text action ([NoTextActions]), no autofill and no suggestions. Its
 * text is kept across a rotation by the field itself. [announce] keeps the small count line under the pill - a polite
 * live region, at most once per [SEARCH_ANNOUNCE_MS] of typing.
 */
class KidSearchField(private val root: View, hint: Int, private val onQuery: (String) -> Unit) {
    private val input: EditText = root.findViewById(R.id.kid_search_field)
    private val clearButton: View = root.findViewById(R.id.kid_search_clear)
    private val count: TextView = root.findViewById(R.id.kid_search_count)
    private var countChangedAt: Long? = null
    private var pendingCount: Runnable? = null

    val query: String get() = input.text?.toString().orEmpty()

    init {
        input.setHint(hint)
        NoTextActions.apply(input)
        input.doAfterTextChanged {
            clearButton.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            onQuery(query)
        }
        clearButton.setOnClickListener { clear() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
    }

    val isShown: Boolean get() = root.visibility == View.VISIBLE

    fun setShown(shown: Boolean) {
        val visibility = if (shown) View.VISIBLE else View.GONE
        if (root.visibility == visibility) return
        if (!shown) hideKeyboard()
        root.visibility = visibility
    }

    /** Empties the pill and closes the keyboard (the "×", Back, after the request form's Send). */
    fun clear() {
        if (query.isNotEmpty()) input.setText("")
        hideKeyboard()
        input.clearFocus()
    }

    fun hideKeyboard() {
        root.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** The count line: "3 treff" while searching with hits ([hits] `null` = not searching, or nothing found - the
     * "Fant ingenting" view speaks then). */
    fun announce(hits: Int?) {
        pendingCount?.let { count.removeCallbacks(it) }
        pendingCount = null
        if (hits == null || hits <= 0) {
            count.visibility = View.GONE
            count.text = null
            return
        }
        val text = root.resources.getQuantityString(R.plurals.search_hits, hits, hits)
        if (count.visibility == View.VISIBLE && count.text?.toString() == text) return
        val now = SystemClock.elapsedRealtime()
        val at = searchAnnounceAt(countChangedAt, now)
        val show = Runnable {
            pendingCount = null
            countChangedAt = SystemClock.elapsedRealtime()
            count.visibility = View.VISIBLE
            count.text = text
        }
        if (at <= now) {
            show.run()
        } else {
            pendingCount = show
            count.postDelayed(show, at - now)
        }
    }
}
