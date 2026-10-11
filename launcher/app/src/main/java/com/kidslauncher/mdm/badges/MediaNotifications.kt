package com.kidslauncher.mdm.badges

import android.app.Notification

/**
 * Whether [notification] is a player's media notification (design 21 QA #6: a paused player's is
 * neither ongoing nor unread, so it must not add 1 to the app's badge). Only the presence of the
 * media-session key is looked at - never a value, title or text (BadgeCountsTest scans this file;
 * the listener itself reads no extras, ElementRoomsTest).
 */
fun isMediaNotification(notification: Notification): Boolean =
    try {
        notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
    } catch (e: Exception) {
        false
    }
