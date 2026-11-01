package io.github.iamthebarrow.nibtexa.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Git access token, encrypted with an AES-GCM key that lives in the Android Keystore and
 * never leaves it. Only the ciphertext is stored, in [file] (no-backup storage). The token is
 * never logged; callers must not log it either.
 */
class TokenStore(private val file: File, private val alias: String = KEY_ALIAS) {

    val hasToken: Boolean get() = file.isFile

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv.size.toByte().let { byteArrayOf(it) } + cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, "${file.name}.tmp")
        file.parentFile?.mkdirs()
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Cannot store the token")
        }
    }

    /** The token, or null if none is stored or it can no longer be decrypted (key removed). */
    fun load(): String? {
        if (!file.isFile) return null
        return try {
            val sealed = file.readBytes()
            val ivSize = sealed[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(TAG_BITS, sealed, 1, ivSize))
            String(cipher.doFinal(sealed, 1 + ivSize, sealed.size - 1 - ivSize), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    fun clear() {
        file.delete()
        keyStore().deleteEntry(alias)
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nibtexa-git-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
