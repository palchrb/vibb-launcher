package com.kidslauncher.mdm.server

/*
 * The Tailscale auth key's life on the phone (security review M12) - pure, tested in TailnetKeyTest.
 * The key from the setup QR (or typed in Settings) is only needed to create this phone's tailnet
 * node: tsnet stores the node's identity in its state directory, and once the node exists it never
 * reads the key again (tsnet.Server.AuthKey: "If the node is already created (from state previously
 * stored in Store), then this field is not used"). So the key is deleted as soon as the phone is on
 * the tailnet, and later starts come up from the stored node state with an empty key. A photo of
 * the QR is then the only copy left - which is why the parent uses one-time, tagged keys.
 */

/** What [TsnetClient.connectFromPreferences] does now. */
sealed interface TailnetConnect {
    /** No key and never joined: nothing to connect to. */
    data object Nothing : TailnetConnect

    /** Connected already in this process. */
    data object Connected : TailnetConnect

    /** Bring the node up with [authKey]; "" = from the node state stored at the first join. */
    data class Up(val authKey: String) : TailnetConnect
}

/** [storedKey] the key in the preferences; [joined] the phone has been on the tailnet before. */
fun tailnetConnect(storedKey: String?, joined: Boolean, connected: Boolean): TailnetConnect = when {
    connected -> TailnetConnect.Connected
    !storedKey.isNullOrBlank() -> TailnetConnect.Up(storedKey)
    joined -> TailnetConnect.Up("")
    else -> TailnetConnect.Nothing
}

/** Whether the stored key goes now: the node is on the tailnet (tsnet won't read it again). */
fun forgetTailnetKey(storedKey: String?, connected: Boolean): Boolean = connected && !storedKey.isNullOrBlank()
