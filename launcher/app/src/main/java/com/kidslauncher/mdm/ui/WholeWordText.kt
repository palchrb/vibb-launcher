package com.kidslauncher.mdm.ui

import android.content.Context
import android.text.StaticLayout
import android.text.TextPaint
import android.text.method.TransformationMethod
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.min

/**
 * A word is never cut between letters (emulator round 2026-10-14 N7): at font scale 2.0 Home's
 * contact names broke as "Mamm/a" and English tile labels as "Chro/me", "Ph/one" - Android's line
 * breaker splits a word wider than the line anywhere when the language's hyphenation patterns don't
 * know it (names never, nb words in an en phone neither).
 *
 * The rule, calm and legible first: Android's own layout as long as no line ends inside a word
 * without a hyphen (so "Innstillin-ger" stays hyphenated); else the label shrinks in [STEPS] steps,
 * at most to [FLOOR_SHARE] of its size, until no word is cut; and when even that leaves one cut,
 * each word too wide for a line ends in an ellipsis at that smallest size ("Melding…") - an
 * ellipsis or a hyphen, never a bare letter split. Pure; [WholeWordTextView] feeds it.
 */
object WholeWords {
    /** The smallest share of the label's size the rule shrinks to (the scale still shows: 2.0 -> 1.5). */
    const val FLOOR_SHARE = 0.75f

    /** Sizes tried below the label's own, evenly down to the floor. */
    const val STEPS = 5

    const val ELLIPSIS = "…"

    private fun isWordChar(c: Char): Boolean = Character.isLetterOrDigit(c) || when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** A line ending at [lineEnd] cuts a word: letters on both sides and no hyphen drawn. */
    fun cutsWord(text: CharSequence, lineEnd: Int, hyphenated: Boolean): Boolean =
        !hyphenated && lineEnd in 1 until text.length && isWordChar(text[lineEnd - 1]) && isWordChar(text[lineEnd])

    /** The sizes tried, from [basePx] down to [FLOOR_SHARE] of it. */
    fun sizes(basePx: Float): List<Float> = (0..STEPS).map { basePx * (1f - (1f - FLOOR_SHARE) * it / STEPS) }

    /** The size to show and whether the overlong words must end in an ellipsis. */
    data class Fit(val sizePx: Float, val ellipsized: Boolean)

    /** The largest of [sizes] at which [cutsAt] finds no cut word; else the floor with an ellipsis. */
    fun fit(basePx: Float, cutsAt: (Float) -> Boolean): Fit {
        val all = sizes(basePx)
        return all.firstOrNull { !cutsAt(it) }?.let { Fit(it, false) } ?: Fit(all.last(), true)
    }

    /** Every word [fits] refuses becomes its longest prefix that fits with [ELLIPSIS] (at least one
     * letter); spaces, punctuation and words that fit are left as they are. */
    fun ellipsizeWords(text: String, fits: (String) -> Boolean): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (!isWordChar(text[i])) {
                out.append(text[i++])
                continue
            }
            var end = i
            while (end < text.length && isWordChar(text[end])) end++
            val word = text.substring(i, end)
            out.append(if (fits(word)) word else shorten(word, fits))
            i = end
        }
        return out.toString()
    }

    private fun shorten(word: String, fits: (String) -> Boolean): String {
        var cut = word.length - 1
        while (cut > 1 && !fits(word.substring(0, cut) + ELLIPSIS)) cut--
        // Never split a surrogate pair.
        if (cut in 1 until word.length && Character.isLowSurrogate(word[cut])) cut--
        return word.substring(0, maxOf(1, cut)) + ELLIPSIS
    }
}

/**
 * A TextView that follows [WholeWords] (Home's tile labels and the contact names on Home and in the
 * phone book). Measures with an offline [StaticLayout] in the view's own break settings and applies
 * a size or an ellipsis only when it changes, so a layout pass settles. The text itself is never
 * changed: an ellipsis is a [TransformationMethod] (`text` and the accessibility text stay whole).
 */
class WholeWordTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** The label's own size (XML or a caller's setTextSize), which the rule shrinks from. */
    private var basePx = textSize
    private var adjusting = false
    private var fittedFor: Triple<String, Int, Float>? = null

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        if (!adjusting) {
            basePx = textSize
            fittedFor = null
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = lineWidth(widthMeasureSpec)
        if (width > 0) fitWords(width)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun lineWidth(spec: Int): Int {
        var width = if (View.MeasureSpec.getMode(spec) == View.MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else View.MeasureSpec.getSize(spec)
        if (maxWidth in 0 until Int.MAX_VALUE) width = min(width, maxWidth)
        if (width == Int.MAX_VALUE) return 0
        return width - compoundPaddingLeft - compoundPaddingRight
    }

    private fun fitWords(width: Int) {
        val source = text?.toString().orEmpty()
        val key = Triple(source, width, basePx)
        if (key == fittedFor) return
        fittedFor = key
        val fit = WholeWords.fit(basePx) { cutsAt(source, it, width) }
        val method = if (fit.ellipsized) {
            val atFloor = TextPaint(paint).apply { textSize = fit.sizePx }
            WordEllipsis(source, WholeWords.ellipsizeWords(source) { StaticLayout.getDesiredWidth(it, atFloor) <= width })
        } else {
            null
        }
        adjusting = true
        try {
            if (textSize != fit.sizePx) setTextSize(TypedValue.COMPLEX_UNIT_PX, fit.sizePx)
            if (transformationMethod != method) transformationMethod = method
        } finally {
            adjusting = false
        }
    }

    /** Whether Android's own layout of [source] at [sizePx] cuts a word within the lines shown. */
    private fun cutsAt(source: String, sizePx: Float, width: Int): Boolean {
        if (source.isEmpty()) return false
        val atSize = TextPaint(paint).apply { textSize = sizePx }
        val layout = StaticLayout.Builder.obtain(source, 0, source.length, atSize, width)
            .setBreakStrategy(breakStrategy)
            .setHyphenationFrequency(hyphenationFrequency)
            .setIncludePad(includeFontPadding)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .build()
        val shown = if (maxLines in 1 until layout.lineCount) maxLines else layout.lineCount
        // A drawn hyphen (no public API names it) makes the line wider than its own text.
        val hyphen = atSize.measureText("-") / 2f
        return (0 until shown - 1).any {
            val end = layout.getLineEnd(it)
            val hyphenated = layout.getLineWidth(it) - StaticLayout.getDesiredWidth(source, layout.getLineStart(it), end, atSize) > hyphen
            WholeWords.cutsWord(source, end, hyphenated)
        }
    }

    /** Shows [display] for exactly [source]; any other text as it is. */
    private data class WordEllipsis(val source: String, val display: String) : TransformationMethod {
        override fun getTransformation(text: CharSequence?, view: View?): CharSequence? =
            if (text?.toString() == source) display else text

        override fun onFocusChanged(
            view: View?, sourceText: CharSequence?, focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?,
        ) = Unit
    }
}
