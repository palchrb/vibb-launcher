package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Design r10 §2-§4: the phone's secrets sealed under one AES-GCM key - a JVM key standing in for the Keystore's. */
class SealedPrefsTest {

    /** AES/GCM like the Keystore's: a random 12-byte IV per seal, a 128-bit tag. [sealFailures]/[openFailures] throw
     * that many times first; [key] `null` = missing (only a seal makes one, as the Keystore box). */
    class FakeBox : SecretBox {
        var key: ByteArray? = null
        var sealFailures = 0
        var openFailures = 0
        var recreated = 0
        var broken = false

        override fun keyBroken() = broken || key == null

        override fun seal(aad: String, plain: ByteArray): ByteArray {
            if (sealFailures > 0) {
                sealFailures--
                throw ProviderException("keystore busy")
            }
            val key = key ?: Random.nextBytes(32).also { key = it }
            val iv = Random.nextBytes(12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad.toByteArray())
            return iv + cipher.doFinal(plain)
        }

        override fun open(aad: String, blob: ByteArray): ByteArray {
            if (openFailures > 0) {
                openFailures--
                throw ProviderException("keystore busy")
            }
            val key = key ?: throw IllegalStateException("no key")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
            cipher.updateAAD(aad.toByteArray())
            return cipher.doFinal(blob, 12, blob.size - 12)
        }

        override fun recreate() {
            recreated++
            key = Random.nextBytes(32)
        }
    }

    private val token = "0123456789abcdef0123456789abcdef"
    private val aad = "default/mdm.device_token"
    private val key = "mdm.device_token"
    private val prefs = ParentPinStoreTest.FakePrefs()
    private val box = FakeBox()
    private val logged = mutableListOf<String>()
    private val sealed = SealedPrefs(box) { message, _ -> logged += message }

    /** Another process: the same files and key, no memo. */
    private fun restarted() = SealedPrefs(box) { message, _ -> logged += message }

    @Test
    fun `a sealed value round-trips and the plaintext isn't on disk`() {
        assertTrue(sealed.edit(prefs) { it.putSealed(aad, key, token) })
        val stored = prefs.map[key] as String
        assertTrue(stored.startsWith(SealedPrefs.PREFIX))
        assertFalse(token in stored)
        assertEquals(Sealed.Value(token), restarted().read(prefs, aad, key))
        assertEquals(Sealed.Absent, sealed.read(prefs, aad, "other"))
        assertFalse("the value never prints", token in Sealed.Value(token).toString())
    }

    @Test
    fun `a blob under another field's AAD or bad Base64 is unreadable, logged once, and left alone`() {
        sealed.edit(prefs) { it.putSealed(aad, key, token) }
        prefs.map["moved"] = prefs.map[key]
        val other = restarted()
        assertEquals(Sealed.Unreadable, other.read(prefs, "window_relock/token", "moved"))
        prefs.map["bad"] = "ks1:!!not base64"
        assertEquals(Sealed.Unreadable, other.read(prefs, "pin_lock_state/pin_hash", "bad"))
        assertEquals(Sealed.Unreadable, other.read(prefs, "pin_lock_state/pin_hash", "bad"))
        assertEquals("ks1:!!not base64", prefs.map["bad"])
        assertEquals(2, logged.size)
    }

    @Test
    fun `a plaintext without the prefix is unreadable, logged once, and left alone`() {
        prefs.map[key] = token
        assertEquals(Sealed.Unreadable, sealed.read(prefs, aad, key))
        assertEquals(Sealed.Unreadable, sealed.read(prefs, aad, key))
        assertEquals(token, prefs.map[key])
        assertEquals(0, prefs.commits)
        assertEquals(1, logged.size)
    }

    @Test
    fun `a failing open keeps the stored string and isn't memoised - the next good read heals`() {
        sealed.edit(prefs) { it.putSealed(aad, key, token) }
        val stored = prefs.map[key]
        val other = restarted()
        box.openFailures = 1
        assertEquals(Sealed.Unreadable, other.read(prefs, aad, key))
        assertEquals(stored, prefs.map[key])
        assertEquals(Sealed.Value(token), other.read(prefs, aad, key))
    }

    @Test
    fun `an edit whose seal fails commits nothing - never a plaintext fallback`() {
        box.sealFailures = 1
        assertFalse(sealed.edit(prefs) { it.raw.putBoolean("active", true); it.putSealed(aad, key, token) })
        assertEquals(0, prefs.commits)
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun `the token's write retries once, then makes a new key, then gives up writing nothing`() {
        box.broken = true
        box.sealFailures = 1
        assertTrue(sealed.putRecovering(prefs, aad, key, token))
        assertEquals(0, box.recreated)
        box.sealFailures = 2
        assertTrue(sealed.putRecovering(prefs, aad, key, token))
        assertEquals(1, box.recreated)
        assertEquals(Sealed.Value(token), restarted().read(prefs, aad, key))
        val fresh = ParentPinStoreTest.FakePrefs()
        box.sealFailures = 3
        assertFalse(sealed.putRecovering(fresh, aad, key, token))
        assertEquals(2, box.recreated)
        assertTrue(fresh.map.isEmpty())
    }

    /** Code QA Q-4: two transient errors with a key that still works never cost that key (the kid PIN, the reset token). */
    @Test
    fun `two transient seal failures with a usable key write nothing and keep the key`() {
        sealed.edit(prefs) { it.putSealed("pin_lock_state/pin_hash", "pin_hash", token) }
        val keyBefore = box.key
        box.sealFailures = 2
        assertFalse(sealed.putRecovering(prefs, aad, key, token))
        assertEquals(0, box.recreated)
        assertTrue(box.key === keyBefore)
        assertFalse(key in prefs.map)
        assertEquals(Sealed.Value(token), restarted().read(prefs, "pin_lock_state/pin_hash", "pin_hash"))
    }

    /** Code QA Q-3: a stored token never reads as absent for the first-launch reset - not even one no key opens. */
    @Test
    fun `a stored token is present whatever it holds, a blank one is not`() {
        assertFalse(tokenStored(null))
        assertFalse(tokenStored(""))
        assertFalse(tokenStored("  "))
        assertTrue(tokenStored(token))
        assertTrue(tokenStored("ks1:" + "A".repeat(40)))
        prefs.map[key] = "ks1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        assertEquals(Sealed.Unreadable, restarted().read(prefs, aad, key))
        assertTrue(tokenStored(prefs.getString(key, null)))
        val present = sources().getValue("server/SealedPrefs.kt").substringAfter("fun present(): Boolean").substringBefore("\n")
        assertEquals(" = tokenStored(prefs().getString(key(), null))", present)
    }

    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    private fun sources(): Map<String, String> = File(main, "java").walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .associate { it.relativeTo(File(main, "java/com/kidslauncher/mdm")).path to it.readText() }

    /** Every production source set (main, debug, release), keyed `set/path` (code QA Q-2). */
    private fun allSources(): Map<String, String> = listOf("main", "debug", "release").flatMap { set ->
        val dir = File(main.parentFile, "$set/java")
        if (!dir.isDirectory) return@flatMap emptyList()
        dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .map { "$set/" + it.relativeTo(File(dir, "com/kidslauncher/mdm")).path to it.readText() }.toList()
    }.toMap()

    /** Code QA Q-2: no raw read or write of a sealed field by its key or AAD outside the field's owner. */
    @Test
    fun `the sealed fields' keys and AADs are named only by their owners, in every source set`() {
        val all = allSources()
        assertTrue(all.keys.any { it.startsWith("debug/") })
        fun owners(text: String) = all.filterValues { text in it }.keys
        assertEquals(setOf("main/server/SealedPrefs.kt"), owners("mdm.device_token"))
        assertEquals(setOf("main/server/WindowRelockStore.kt"), owners("\"window_relock/token\""))
        assertEquals(setOf("main/lock/PinLockStore.kt"), owners("\"pin_lock_state/"))
        assertEquals(setOf("main/server/SealedPrefs.kt"), all.filterValues { Regex("""\bdeviceToken\(""").containsMatchIn(it) }.keys)
        for ((path, text) in all.filterValues { "\"window_relock\"" in it }) {
            for (raw in listOf("getString(\"token\"", "putString(\"token\"")) assertFalse("$path: $raw", raw in text)
        }
    }

    /** QA R10-10: a raw read would send `Bearer ks1:...` and look like a dead server. */
    @Test
    fun `only SealedPrefs names the token's generated getter or setter, and the sites read it sealed`() {
        val all = sources()
        assertEquals(setOf("server/SealedPrefs.kt"), all.filterValues { Regex("""\bdeviceToken\(""").containsMatchIn(it) }.keys)
        assertTrue(all.values.none { "settings_mdm_device_token_key" in it })
        // Design 129 §2.1 (H4): the files that touch the sealed token at all - its owner, the one reader
        // (DeviceCredentials) and the one enrolment; the first-launch reset clears the whole file without naming it.
        assertEquals(
            setOf("server/SealedPrefs.kt", "server/DeviceCredential.kt", "server/Enrollment.kt"),
            all.filterValues { Regex("""\bDeviceToken\.""").containsMatchIn(it) }.keys,
        )
        assertEquals(setOf("server/DeviceCredential.kt"), all.filterValues { Regex("""DeviceToken\.(get|state)\(\)""").containsMatchIn(it) }.keys)
        // Every server call takes its credential from DeviceCredentials.current - the bearer, or the hardware key.
        val sites = all.mapValues { Regex("""DeviceCredentials\.current\(""").findAll(it.value).count() }.filterValues { it > 0 }
        assertEquals(
            mapOf(
                // Design 98 §2.2 step 2: the sync and Flymodus's report-only post.
                "server/MdmSyncWorker.kt" to 2, "server/CommandListenerService.kt" to 1, "server/AppInstallReceiver.kt" to 1,
                "server/AppDownloads.kt" to 1, "server/PowerEvents.kt" to 1, "music/MusicSync.kt" to 2,
                "music/MusicFileDownloads.kt" to 1, "music/MusicBridgeService.kt" to 2,
                // Design 75: the lock weather's pull.
                "weather/WeatherPull.kt" to 1,
                // Design 108: app push's device API calls.
                "push/up/UpRuntime.kt" to 1,
                // Design 110: the map links' resolve call.
                "map/MapLinkActivity.kt" to 1,
                // Design 125 step 2: the Apple Wi-Fi lookup's ask.
                "places/WifiPlaces.kt" to 1,
            ),
            sites,
        )
        // Design 129 §5.2 (code QA H1): the registration flow's own calls take the key even while it is suspected lost.
        assertEquals(setOf("server/KeyRegistration.kt"), all.filterValues { "DeviceCredentials.forRegistration(" in it }.keys)
        // The writer is the one enrolment; the one removal is a bound key's (§5.5).
        assertEquals(setOf("server/Enrollment.kt"), all.filterValues { "DeviceToken.set(" in it }.keys)
        assertEquals(setOf("server/DeviceCredential.kt"), all.filterValues { "DeviceToken.clear(" in it }.keys)
    }

    @Test
    fun `the kid PIN and the reset token are read and written only sealed`() {
        val all = sources()
        assertEquals(setOf("lock/PinLockStore.kt"), all.filterValues { "\"pin_hash\"" in it || "\"pin_salt\"" in it }.keys)
        val store = all.getValue("lock/PinLockStore.kt")
        val relock = all.getValue("server/WindowRelockStore.kt")
        for (raw in listOf("getString(PIN_HASH", "putString(PIN_HASH", "getString(PIN_SALT", "putString(PIN_SALT")) assertFalse(raw, raw in store)
        for (raw in listOf("getString(TOKEN", "putString(TOKEN")) assertFalse(raw, raw in relock)
        assertTrue(store.contains("sealed.read(p, HASH_AAD, PIN_HASH)"))
        assertTrue(relock.contains("sealed.read(prefs, TOKEN_AAD, TOKEN)"))
        assertTrue(relock.contains("sealed.edit(prefs) { it.putSealed(TOKEN_AAD, TOKEN, hex) }"))
    }

    @Test
    fun `the key is AES-256-GCM in the TEE, with no user auth, no unlocked-device rule and no StrongBox`() {
        val text = sources().getValue("server/SealedPrefs.kt")
        val spec = text.substringAfter("KeyGenParameterSpec.Builder(").substringBefore(".build()")
        for (part in listOf("SECRETS_KEY_ALIAS", "BLOCK_MODE_GCM", "ENCRYPTION_PADDING_NONE", "setKeySize(256)")) assertTrue(part, part in spec)
        for (never in listOf("setUserAuthentication", "setUnlockedDeviceRequired", "setIsStrongBoxBacked", "setRandomizedEncryptionRequired")) {
            assertFalse(never, never in text)
        }
        // Code QA Q-10: the AAD on both sides, and the Keystore's own random IV in seal (no IV passed in).
        assertEquals(2, Regex(Regex.escape("cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))")).findAll(text).count())
        val seal = text.substringAfter("override fun seal(aad: String, plain: ByteArray)").substringBefore("override fun open(")
        assertTrue(seal.contains("cipher.init(Cipher.ENCRYPT_MODE, key() ?: create().also { cached = it })"))
        assertFalse(seal.contains("GCMParameterSpec"))
        assertTrue(seal.contains("val iv = cipher.iv"))
        assertEquals("secrets_v1", SECRETS_KEY_ALIAS)
    }
}
