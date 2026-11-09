package com.kidslauncher.mdm.sms

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import com.kidslauncher.mdm.calls.MmscRule

/**
 * Release build: no fake MMS server and no stand-in - the MMSC list is the policy's alone (design 77 D10), and every
 * download and send is the platform's. The debug build's version (src/debug) serves the emulator. Never add anything to
 * this file.
 */
internal object MmsDebug {
    fun mmscRules(): List<MmscRule> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun download(context: Context, location: String, uri: Uri, result: PendingIntent): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun send(context: Context, uri: Uri, result: PendingIntent): Boolean = false
}
