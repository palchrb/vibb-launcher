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
import com.kidslauncher.mdm.calls.learnElementRoom

/**
 * Learns a phone-book contact's Element X DM room from Element X's own notifications (design 15,
 * the rule is [learnElementRoom]). The one reader in [BadgeListenerService] that looks past the
 * package and flags - and only for Element X's notifications in our own user: it reads the tag,
 * the messaging person's key, each message bundle's `sender_person` key and the group flag, never
 * a title, name or message body (ElementRoomsTest scans this file). Nothing here is logged or
 * reported; the pair goes to [ElementRoomStore] (CE prefs) only.
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

    private fun facts(sbn: StatusBarNotification): ElementDmFacts {
        val extras = sbn.notification.extras
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java).orEmpty()
        return ElementDmFacts(
            tag = sbn.tag,
            selfKey = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person::class.java)?.key,
            // Only each bundle's sender person - not MessagingStyle's parser, which copies the body.
            // A bundle without one (the kid's own message) is skipped; one without a key stays null
            // (the rule then learns nothing).
            senderKeys = messages.filterIsInstance<Bundle>()
                .filter { it.containsKey(SENDER_PERSON) }
                .map { it.getParcelable(SENDER_PERSON, Person::class.java)?.key },
            group = if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
            } else {
                null
            },
        )
    }

    /** `Notification.MessagingStyle.Message`'s bundle key for the sender (hidden constant). */
    private const val SENDER_PERSON = "sender_person"
}
