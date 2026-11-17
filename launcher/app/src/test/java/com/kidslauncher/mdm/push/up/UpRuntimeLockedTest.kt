package com.kidslauncher.mdm.push.up

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 108 §5.3 (L6): the main process starts before the first unlock (`BootCallReceiver`, `KidInCallService`), and
 * UP's store is credential-encrypted - nothing touches it, the server or the apps before `isUserUnlocked`.
 */
class UpRuntimeLockedTest {
    @Test
    fun `before the first unlock no entry touches the store, the server or an app`() {
        val phone = FakeUpPhone()
        val engine = UpEngine(
            unlocked = { false },
            store = { throw AssertionError("the store before the unlock") },
            view = { UpFixtures.view("on") },
            server = { throw AssertionError("the server before the unlock") },
            phone = phone,
        )
        engine.handle(UpRequest.Register("io.element.android.x", "tok", "@emma:example.org", null))
        engine.handle(UpRequest.Unregister("io.element.android.x", "tok"))
        engine.handle(UpRequest.Ack(null, "tok", "id"))
        engine.packageGone("io.element.android.x")
        engine.policyAccepted(UpFixtures.view("on"), listOf("c0ffee00000000000000000000000001"), 0)
        assertFalse(engine.requestPull("sse"))
        assertFalse(engine.pullStep())
        assertTrue(phone.sent.isEmpty())
    }

    @Test
    fun `the runtime's own ways in check the unlock first`() {
        val source = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
            .resolve("com/kidslauncher/mdm/push/up/UpRuntime.kt").readText()
        // The engine is built with the unlock check, and every direct store access of the runtime is behind it.
        assertTrue(source.contains("unlocked = { isUserUnlocked(app) },"))
        for (fn in listOf("fun requestPull(", "fun diagnostics(")) {
            val body = source.substringAfter(fn).substringBefore("\n    }\n")
            assertTrue(fn, body.indexOf("isUserUnlocked(app)") in 0 until (body.indexOf("UpStore.of").takeIf { it >= 0 } ?: Int.MAX_VALUE))
            assertTrue(fn, body.contains("if (!isUserUnlocked(app)) return"))
        }
        val note = source.substringAfter("fun noteOldConnector(").substringBefore("\n    }\n")
        assertTrue(note.indexOf("isUserUnlocked(app)") in 0 until note.indexOf("UpStore.of(app)"))
        // Never direct-boot-aware: the components aren't started before the first unlock either.
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        for (name in listOf(".push.up.UpReceiver", ".push.up.UpLinkActivity", ".push.up.UpPackageReceiver")) {
            val entry = manifest.substringAfter("android:name=\"$name\"").substringBefore(">")
            assertFalse(name, "directBootAware" in entry)
        }
    }
}
