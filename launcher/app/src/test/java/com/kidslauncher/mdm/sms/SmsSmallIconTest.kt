package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.appdisplay.MessagesLook
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsSmallIconTest {
    @Test
    fun `the small icon is Meldinger's glyph, its own bubble with dots by default`() {
        assertEquals(R.drawable.ic_kid_sms, smsSmallIcon(MessagesLook()))
        assertEquals(R.drawable.ic_launcher_foreground, smsSmallIcon(MessagesLook(glyph = R.drawable.ic_launcher_foreground)))
    }
}
