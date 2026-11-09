package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.calls.MmscRule

/**
 * Release build: no fake MMS server - the MMSC list is the policy's alone (design 77 D10). The debug build's version
 * (src/debug) serves the emulator. Never add anything to this file.
 */
internal object MmsDebug {
    fun mmscRules(): List<MmscRule> = emptyList()
}
