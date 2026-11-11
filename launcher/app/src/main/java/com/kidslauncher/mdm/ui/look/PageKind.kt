package com.kidslauncher.mdm.ui.look

import android.app.Activity
import com.kidslauncher.mdm.ui.day.DayActivity
import com.kidslauncher.mdm.ui.day.EventActivity
import com.kidslauncher.mdm.ui.day.EventEditActivity
import com.kidslauncher.mdm.ui.day.LightDayActivity
import com.kidslauncher.mdm.ui.day.LightEventActivity
import com.kidslauncher.mdm.ui.day.LightEventEditActivity
import com.kidslauncher.mdm.ui.messages.LightMessagesActivity
import com.kidslauncher.mdm.ui.messages.LightThreadActivity
import com.kidslauncher.mdm.ui.messages.MessagesActivity
import com.kidslauncher.mdm.ui.messages.ThreadActivity

/**
 * The table (design 78 §12.2 R4): each page that can be the top of a task of the launcher's own - Kalender's three
 * and, since step 2, Meldinger's list and thread - as its night class -
 * the class as it always was - and its light twin. Android draws such a page's starting window from the manifest
 * theme of the class it starts, before any code of ours, so the look is in the class. This file is the only place
 * that names a page class as `::class` (PageLookScanTest): every start asks [current], and everything that knows a
 * page task by its root asks [has].
 */
enum class PageKind(private val night: Class<out Activity>, private val light: Class<out Activity>) {
    DAY(DayActivity::class.java, LightDayActivity::class.java),
    EVENT(EventActivity::class.java, LightEventActivity::class.java),
    EVENT_EDIT(EventEditActivity::class.java, LightEventEditActivity::class.java),
    MESSAGES(MessagesActivity::class.java, LightMessagesActivity::class.java),
    THREAD(ThreadActivity::class.java, LightThreadActivity::class.java),
    ;

    /** This page's class in [look]. */
    fun of(look: LookMode): Class<out Activity> = if (look == LookMode.LIGHT) light else night

    /** The class a start of this page names: the applied look's (R5, the pages' intent builders). A start asked for
     * holds a waiting change back until the page exists ([PageLook.pageStartAsked], step 1's code QA L5). */
    fun current(): Class<out Activity> = of(PageLook.applied).also { PageLook.pageStartAsked() }

    /** Whether [className] is one of this page's two classes. */
    fun has(className: String?): Boolean = className == night.name || className == light.name

    companion object {
        /** The look [page] is the variant of; null for a class without a row - a page with one class, in Home's
         * task (R9). */
        fun lookOf(page: Class<*>): LookMode? = when {
            entries.any { it.light == page } -> LookMode.LIGHT
            entries.any { it.night == page } -> LookMode.DARK
            else -> null
        }
    }
}
