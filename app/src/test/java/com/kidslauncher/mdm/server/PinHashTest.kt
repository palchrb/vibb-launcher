package com.kidslauncher.mdm.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vector shared with kid-phone-server's `security::tests::pin_hash_shared_vector`: same PIN,
 * salt 00..0f, same PBKDF2 parameters - change both sides together. */
class PinHashTest {
    private val salt = "000102030405060708090a0b0c0d0e0f"
    private val hash1234 = "942eed8586f04aa8cc4b14537eb02bb601b671749b6f95c07a2d2261f83e75a7"

    @Test
    fun `the server's vector verifies`() {
        assertTrue(PinHash.verify("1234", hash1234, salt))
        assertTrue(PinHash.verify("1234", hash1234.uppercase(), salt))
        assertFalse(PinHash.verify("1235", hash1234, salt))
        assertTrue(PinHash.verify("654321", "6bb35e1b106559ec1566da7537868207b84e88e8837ec901f305348f8974986f", salt))
    }

    @Test
    fun `unusable pairs never verify`() {
        assertFalse(PinHash.usable(null, salt))
        assertFalse(PinHash.usable(hash1234, null))
        assertFalse(PinHash.usable(hash1234.drop(2), salt))
        assertFalse(PinHash.usable("zz" + hash1234.drop(2), salt))
        assertFalse(PinHash.usable(hash1234, "abc"))
        assertTrue(PinHash.usable(hash1234, salt))
        assertFalse(PinHash.verify("1234", hash1234.drop(2), salt))
        assertFalse(PinHash.verify("1234", hash1234, "nothex"))
    }

    @Test
    fun hex() {
        assertArrayEquals(byteArrayOf(0, 1, -1), PinHash.parseHex("0001ff"))
        assertNull(PinHash.parseHex(""))
        assertNull(PinHash.parseHex("0"))
        assertNull(PinHash.parseHex("0g"))
    }
}
