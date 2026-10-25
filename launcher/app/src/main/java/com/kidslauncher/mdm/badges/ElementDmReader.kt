package com.kidslauncher.mdm.badges

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.os.Process
import android.service.notification.StatusBarNotification
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.ElementDmFacts
import com.kidslauncher.mdm.calls.ElementRoomStore
import com.kidslauncher.mdm.calls.MessagePackages
import com.kidslauncher.mdm.calls.elementGlanceContact
import com.kidslauncher.mdm.calls.learnElementRoom

/**
 * Learns a phone-book contact's Element X DM room from Element X's own notifications (design 15,
 * the rule is [learnElementRoom]). The one reader in [BadgeListenerService] that looks past the
 * package and flags - and only for Element X's notifications in our own user: it reads the tag,
 * the messaging person's key, each message bundle's `sender_person` key and the group flag, never
 * a title, name or message body (ElementRoomsTest scans this file). Nothing here is logged or
 * reported; the pair goes to [ElementRoomStore] (CE prefs) only.
 *
 * Since 2026-10-25 the same facts also serve the lock's glance ([glanceContact]): a DM's tag is looked up among the
 * learned rooms, and a group's newest `sender_person` key - read here all along, for every Element X notification, groups
 * included - is now also matched in memory against the phone book's Element MXIDs. Only our own contact's id goes on, to
 * the glance's memory ([GlanceStore]); nothing new is read, and nothing is stored, logged or sent.
 */
object ElementDmReader {

    /** Main thread (listener callbacks): bundle reads and an async prefs write only. */
    fun learn(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName != MessagePackages.ELEMENT_X || sbn.user != Process.myUserHandle()) return
        val facts = try {
            facts(sbn)
        } catch (e: Exception) {
            return
        }
        val learned = learnElementRoom(sbn.packageName, facts, CallPolicyStore.state) ?: return
        // The store checks the contact again against the rules it reads under its lock.
        ElementRoomStore.learn(context, learned)
    }

    /**
     * The lock's glance (2026-10-25): the phone-book contact an Element X row stands for ([elementGlanceContact]: a DM by
     * its learned room, a group by its newest sender's key) - the same facts [learn] reads, nothing more. Called by
     * [GlanceReader] only from the sender level up; null for any other package or user, or on any exception.
     */
    fun glanceContact(context: Context, sbn: StatusBarNotification): Long? {
        if (sbn.packageName != MessagePackages.ELEMENT_X || sbn.user != Process.myUserHandle()) return null
        return try {
            elementGlanceContact(facts(sbn), CallPolicyStore.state, ElementRoomStore.rooms(context))
        } catch (e: Exception) {
            null
        }
    }

    private fun facts(sbn: StatusBarNotification): ElementDmFacts {
        val extras = sbn.notification.extras
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java).orEmpty()
        return ElementDmFacts(
            tag = sbn.tag,
            selfKey = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person::class.java)?.key,
            // Only each bundle's sender person - not MessagingStyle's parser, which copies the body.
            // The bundles are the glance's "from others" (qa-launcher-glance-b-code Q-2: the same rule as
            // GlanceReader, so a group's photo and its name come from the same message): a bundle with
            // neither key (the kid's own message) is skipped; one without a sender person or its key
            // stays null (the rule then learns nothing, the glance shows the app's icon).
            senderKeys = messages.filterIsInstance<Bundle>()
                .filter { it.containsKey(SENDER_PERSON) || it.containsKey(SENDER) }
                .map { it.getParcelable(SENDER_PERSON, Person::class.java)?.key },
            group = if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
            } else {
                null
            },
        )
    }

    /** `Notification.MessagingStyle.Message`'s bundle keys for the sender (hidden constants): the
     * person, and the older plain name ([SENDER] only checked for presence, never read). */
    private const val SENDER_PERSON = "sender_person"
    private const val SENDER = "sender"
}
