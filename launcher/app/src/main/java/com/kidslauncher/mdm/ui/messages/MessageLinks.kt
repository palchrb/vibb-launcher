package com.kidslauncher.mdm.ui.messages

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.logins.LinkLookup
import com.kidslauncher.mdm.logins.freeBrowserOf
import com.kidslauncher.mdm.logins.linkTargetOf
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
 * - any other link is a plain `VIEW` to the one allowed app that declares it (design 119 §5: [linkTargetOf] over
 *   [com.kidslauncher.mdm.logins.LinkLookup]'s per-package lookup, so an unverified app is found while a browser
 *   exists; the allowed set is [messageLinkAllowed]: the allowlist and Vibb One, never the login browser), else a
 *   free browser the parent ticked - never Play services, never a chooser, nothing during a time rule; with none, the
 *   kid's calm note.
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

    /** Opens [url] (§15 Q1): a map link through Vibb's handler while the switch is on, else the one allowed app that
     * declares it, else the note. The lookup runs off the main thread (one query per allowed package, design 119 H1). */
    fun open(context: Context, url: String) {
        try {
            if (MapLinkForms.isMapLink(url) && MapLinksRuntime.on(context)) {
                val https = MapLinkForms.upgrade(url) ?: url
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(https)).setClass(context, MapLinkActivity::class.java))
                return
            }
        } catch (e: Exception) {
            Log.w("MessageLinks", "Couldn't open a map link: ${e.javaClass.simpleName}")
            cantOpen(context)
            return
        }
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            // Code QA L4: an intent filter's scheme is lowercase ("HTTPS://" matches nothing).
            val view = try {
                Intent(Intent.ACTION_VIEW, Uri.parse(url).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)
            } catch (e: Exception) {
                null
            }
            val pkg = try {
                view?.let { handler(app, it) }
            } catch (e: Exception) {
                Log.w("MessageLinks", "Couldn't look a link up: ${e.javaClass.simpleName}")
                null
            }
            main.post { start(context, view, pkg) }
        }.start()
    }

    /** On the main thread: the target, or the note - only while the page is still there. */
    private fun start(context: Context, view: Intent?, pkg: String?) {
        if ((context as? android.app.Activity)?.isFinishing == true) return
        try {
            if (view == null || pkg == null) {
                cantOpen(context)
                return
            }
            context.startActivity(view.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("MessageLinks", "Couldn't open a link: ${e.javaClass.simpleName}")
            cantOpen(context)
        }
    }

    /** The kid's calm note (§7.4, code QA L5): one sheet, one OK, in the page's own dialog look. */
    private fun cantOpen(context: Context) {
        AlertDialog.Builder(context)
            .setMessage(R.string.message_link_cant_open)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** The one app that opens [view] (design 119 §5): [linkTargetOf] over the per-package lookup, else a free browser. */
    private fun handler(context: Context, view: Intent): String? {
        val allowlist = com.kidslauncher.mdm.server.currentPolicyDecision().policy?.allowlist
        val own = context.packageName
        val locked = com.kidslauncher.mdm.preferences.LauncherPreferences.mdm().lockReason() != com.kidslauncher.mdm.server.LockReason.NONE
        if (locked) return null
        val found = LinkLookup.find(context, view, allowlist, own)
        val allowed = messageLinkAllowed(allowlist, own)
        return linkTargetOf(found.approved, found.declaring, allowed, locked, own, found.browsers.toSet())
            ?: freeBrowserOf(found.browsers, allowed, own)
    }
}

/**
 * The apps a message's web link may open (design 119 §5): the policy's allowlist and Vibb One - never the login
 * browser, whatever its role (a message never opens Chrome) -, `null` on a phone without an allowlist (nothing is
 * restricted there).
 */
fun messageLinkAllowed(allowlist: List<String>?, ownPackage: String): Set<String>? = allowlist?.let { it.toSet() + ownPackage }
