package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.calls.MmscRule

/**
 * Debug build: the emulator's fake MMS server, set by the debug receiver (src/debug) and added to the policy's MMSC
 * list in this process's memory only. The release build's twin (src/release) has none.
 */
internal object MmsDebug {
    @Volatile
    var hosts: List<MmscRule> = emptyList()

    fun mmscRules(): List<MmscRule> = hosts
}
