package me.vibb.music.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Storytel login on the phone (design 21 §2): AES-GCM under an Android Keystore key, in
 * noBackupFilesDir, never logged; generation 0 deletes it. Phase 2 uses it to log in.
 */
object StorytelLogin {
    private const val ALIAS = "vibb_storytel"
    private const val AAD = "storytel-v1"

    data class Login(val generation: Long, val email: String, val password: String) {
        /** The generation only: the generated one printed the email and the password (design 26b §0, QA L9). */
        override fun toString(): String = "Login(generation=$generation, email=<redacted>, password=<redacted>)"
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, "storytel.bin")

    /** The stored login's generation (kept in clear before the sealed part), 0 without one. */
    fun generation(context: Context): Long =
        runCatching { DataInputStream(file(context).inputStream()).use { it.readLong() } }.getOrNull() ?: 0

    fun has(context: Context): Boolean = file(context).isFile

    fun load(context: Context): Login? {
        val bytes = runCatching { file(context).readBytes() }.getOrNull() ?: return null
        return runCatching { open(key(), bytes) }.getOrNull()
    }

    /** [generation] 0 (or no email): delete the stored login. */
    fun store(context: Context, generation: Long, email: String?, password: String?) {
        if (generation == 0L || email == null || password == null) {
            file(context).delete()
            return
        }
        val tmp = File(context.noBackupFilesDir, "storytel.bin.tmp")
        tmp.writeBytes(seal(key(), Login(generation, email, password)))
        if (!tmp.renameTo(file(context))) tmp.delete()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** generation, IV, ciphertext of "email\u0000password" (AAD "storytel-v1"). Pure but for [key]. */
    fun seal(key: SecretKey, login: Login): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD.toByteArray())
        val sealed = cipher.doFinal("${login.email}\u0000${login.password}".toByteArray())
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeLong(login.generation)
            d.writeInt(cipher.iv.size)
            d.write(cipher.iv)
            d.write(sealed)
        }
        return out.toByteArray()
    }

    fun open(key: SecretKey, bytes: ByteArray): Login {
        val d = DataInputStream(bytes.inputStream())
        val generation = d.readLong()
        val iv = ByteArray(d.readInt().also { require(it in 12..16) }).also { d.readFully(it) }
        val sealed = d.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray())
        val (email, password) = String(cipher.doFinal(sealed)).split('\u0000', limit = 2)
        return Login(generation, email, password)
    }
}
