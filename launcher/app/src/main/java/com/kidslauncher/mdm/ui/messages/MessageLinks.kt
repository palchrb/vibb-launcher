package com.kidslauncher.mdm.ui.messages

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.map.MapLinkActivity
import com.kidslauncher.mdm.map.MapLinkForms
import com.kidslauncher.mdm.map.MapLinks
import com.kidslauncher.mdm.map.MapLinksRuntime

/**
 * Web links in a message bubble (design 110 §15 Q1, the user: "Alle linker i meldinger bør vel kunne trykkes på?
 * burde se ut som en vanlig link vel?"): every `http`/`https` link underlined, in the bubble's own ink, and tappable;
 * phone numbers, e-mail addresses and street addresses stay plain text. The one way a message opens something
 * (`KidScreensEscapeTest`):
 *
 * - a map link, while the parent's switch is on, goes straight to Vibb's own handler ([MapLinkActivity]) - no
 *   Android resolution, no phone step needed;
 * - any other link is a plain `VIEW` to the one app Android names for it (an allowed browser, or an allowed app that
 *   holds the link) - never a chooser; with none, the kid's calm note.
 *
 * The text keeps its long press (the reactions): only a press that starts on a link is the link's.
 */
object MessageLinks {

    /** [body] into [view], its links underlined and tappable. */
    @SuppressLint("ClickableViewAccessibility")
    fun show(view: TextView, body: String) {
        val links = MapLinks.webLinks(body)
        if (links.isEmpty()) {
            view.text = body
            view.setOnTouchListener(null)
            return
        }
        val text = SpannableString(body)
        for (range in links) {
            val url = body.substring(range.first, range.last + 1)
            text.setSpan(LinkSpan(url), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = text
        var pressed: LinkSpan? = null
        view.setOnTouchListener { v, event ->
            val tv = v as TextView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = spanAt(tv, event)
                    pressed != null
                }
                MotionEvent.ACTION_UP -> {
                    val span = pressed
                    pressed = null
                    if (span != null && spanAt(tv, event) == span) span.onClick(tv)
                    span != null
                }
                MotionEvent.ACTION_CANCEL -> {
                    val had = pressed != null
                    pressed = null
                    had
                }
                else -> pressed != null
            }
        }
    }

    private fun spanAt(view: TextView, event: MotionEvent): LinkSpan? {
        val layout = view.layout ?: return null
        val x = event.x.toInt() - view.totalPaddingLeft + view.scrollX
        val y = event.y.toInt() - view.totalPaddingTop + view.scrollY
        if (y < 0 || y > layout.height) return null
        val line = layout.getLineForVertical(y)
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        val spanned = view.text as? Spanned ?: return null
        return spanned.getSpans(offset, offset, LinkSpan::class.java).firstOrNull()
    }

    private class LinkSpan(val url: String) : ClickableSpan() {
        override fun onClick(widget: android.view.View) = open(widget.context, url)

        /** Underlined, in the bubble's own ink (readable on both bubbles). */
        override fun updateDrawState(ds: TextPaint) {
            ds.isUnderlineText = true
        }
    }

    /** Opens [url] (§15 Q1): a map link through Vibb's handler while the switch is on, else the one app Android
     * names, else the note. */
    fun open(context: Context, url: String) {
        try {
            if (MapLinkForms.isMapLink(url) && MapLinksRuntime.on(context)) {
                val https = MapLinkForms.upgrade(url) ?: url
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(https)).setClass(context, MapLinkActivity::class.java))
                return
            }
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val pkg = handler(context, view)
            if (pkg == null) {
                Toast.makeText(context, R.string.message_link_cant_open, Toast.LENGTH_SHORT).show()
                return
            }
            context.startActivity(view.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("MessageLinks", "Couldn't open a link: ${e.javaClass.simpleName}")
            Toast.makeText(context, R.string.message_link_cant_open, Toast.LENGTH_SHORT).show()
        }
    }

    /** The one app that opens [view]: Android's own answer when it names an app, else the first of the candidates
     * that isn't suspended - never Android's chooser. */
    private fun handler(context: Context, view: Intent): String? {
        val pm = context.packageManager
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        val usable = { info: ApplicationInfo -> info.flags and ApplicationInfo.FLAG_SUSPENDED == 0 && info.enabled }
        pm.resolveActivity(view, flags)?.activityInfo
            ?.takeIf { it.packageName != "android" && usable(it.applicationInfo) }
            ?.let { return it.packageName }
        return pm.queryIntentActivities(view, flags).map { it.activityInfo }
            .firstOrNull { it.packageName != "android" && usable(it.applicationInfo) }?.packageName
    }
}
