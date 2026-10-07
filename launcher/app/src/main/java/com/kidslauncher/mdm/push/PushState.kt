package com.kidslauncher.mdm.push

/**
 * The live state of the SSE command stream, in memory (design 19: the stream is the only way the
 * server nudges this phone). Written by `CommandListenerService` on the main thread, read by the
 * backstop alarm. Nothing push-related is stored any more; the `push_state` preferences of the
 * FCM era are deleted once ([FirebaseCleanup]).
 */
object PushState {
    /** The stream is open (between `onOpen` and the drop being noticed). */
    @Volatile
    var sseConnected: Boolean = false
}
