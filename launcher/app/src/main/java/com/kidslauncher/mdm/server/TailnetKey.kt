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

/*
 * Security review S-L10 (round L4b): a Go panic or abort after `connect` returned kills the process outside any
 * try - the crash guard's pending flag is cleared by then. So the time a connect returned is kept, and the next
 * process counts the death that followed as a crash when Android's own record of it ([android.app.ApplicationExitInfo])
 * says the process ended itself with Go's status 2 or by a signal within [TSNET_DEATH_WINDOW_MS]: a Go `exit(2)` or abort, or a native
 * crash. Android's kills (low memory, an update, a force stop, a reboot) never count.
 */

/** A death this soon after a connect returned counts toward the crash guard. */
const val TSNET_DEATH_WINDOW_MS = 10 * 60 * 1000L

/** The exit reasons a tsnet crash leaves: Go's own exit, a signal (SIGABRT), a native crash. */
val TSNET_CRASH_EXITS = setOf(
    android.app.ApplicationExitInfo.REASON_EXIT_SELF,
    android.app.ApplicationExitInfo.REASON_SIGNALED,
    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
)

/** Go's runtime exits with this on a panic or a fatal error; our own crash handler's `exitProcess(1)` never counts. */
const val GO_FATAL_EXIT_STATUS = 2

/** The last process's death ([exitAtMs], [reason], [status]; null = none recorded) after a connect returned at
 * [returnedAtMs] counts as a tsnet crash. A `REASON_EXIT_SELF` counts only with Go's status ([GO_FATAL_EXIT_STATUS],
 * the QA fix round's M1): a Kotlin crash ends the process with 1 and is no tsnet crash. */
fun deathAfterConnectIsCrash(returnedAtMs: Long, exitAtMs: Long?, reason: Int?, status: Int?): Boolean =
    returnedAtMs > 0 && exitAtMs != null && reason in TSNET_CRASH_EXITS &&
        (reason != android.app.ApplicationExitInfo.REASON_EXIT_SELF || status == GO_FATAL_EXIT_STATUS) &&
        exitAtMs >= returnedAtMs && exitAtMs - returnedAtMs <= TSNET_DEATH_WINDOW_MS

/**
 * The crash guard's streak after this process's first look at the last one ([wasLeftPending]: a connect never
 * returned; [lastConnect]: the last connect returned and the process lived on (false) or died soon after (true), null
 * when no connect returned since the last look). A connect that returned and lived past the window ends the streak.
 */
fun crashStreakAfter(count: Int, wasLeftPending: Boolean, lastConnect: Boolean?): Int = when {
    wasLeftPending || lastConnect == true -> count + 1
    lastConnect == false -> 0
    else -> count
}

/** The `leave_tailnet` command (S-L10): its name and answers - the server words them (`locate::reword`). */
const val LEAVE_TAILNET_COMMAND = "leave_tailnet"
const val LEFT_TAILNET = "left the tailnet"
const val NOT_ON_TAILNET = "not on the tailnet"
const val LEAVE_TAILNET_FAILED = "failed to leave the tailnet"
const val TAILNET_ONLY_SERVER = "server is reached only over the tailnet"

/**
 * The QA fix round's M2: [serverUrl]'s host is a Tailscale address (100.64.0.0/10, fd7a:115c:a1e0::/48) - off the
 * tailnet that address is the carrier's CGNAT or the Wi-Fi's gateway, so the phone refuses to leave
 * ([TAILNET_ONLY_SERVER]). An IP literal only, never a look-up; a name (`*.ts.net`, with its certificate) is no such
 * address.
 */
fun tailnetOnlyServer(serverUrl: String?): Boolean {
    val host = try {
        java.net.URI(serverUrl?.trim() ?: return false).host
    } catch (e: Exception) {
        null
    } ?: return false
    return isTailnetAddress(ipLiteral(host)?.address ?: return false)
}

/** The answer when the phone won't leave ([tailnetOnlyServer]). */
fun leaveTailnetRefusal(): Pair<Boolean, String> = false to TAILNET_ONLY_SERVER

/** The answer: [wasOn] the phone had a key, the joined mark, node state or a running client; [cleared] all of it went. */
fun leaveTailnetAnswer(wasOn: Boolean, cleared: Boolean): Pair<Boolean, String> = when {
    !cleared -> false to LEAVE_TAILNET_FAILED
    wasOn -> true to LEFT_TAILNET
    else -> true to NOT_ON_TAILNET
}
