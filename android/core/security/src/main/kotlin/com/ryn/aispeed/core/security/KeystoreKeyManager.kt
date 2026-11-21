package com.ryn.aispeed.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates and seals the 256-bit SQLCipher database passphrase using Android Keystore (AES-256-GCM).
 */
class KeystoreKeyManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateDatabasePassphrase(): ByteArray {
        val existingCipher = prefs.getString(KEY_DB_BLOB, null)
        val existingIv = prefs.getString(KEY_DB_IV, null)
        if (existingCipher != null && existingIv != null) {
            return decrypt(
                Base64.decode(existingCipher, Base64.NO_WRAP),
                Base64.decode(existingIv, Base64.NO_WRAP)
            )
        }

        val rawPassphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (cipherText, iv) = encrypt(rawPassphrase)
        prefs.edit()
            .putString(KEY_DB_BLOB, Base64.encodeToString(cipherText, Base64.NO_WRAP))
            .putString(KEY_DB_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
        return rawPassphrase
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainBytes: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        return cipher.doFinal(plainBytes) to cipher.iv
    }

    private fun decrypt(cipherBytes: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherBytes)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ryn_ai_speed_db_master_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "ryn_secure_vault"
        private const val KEY_DB_BLOB = "db_passphrase_blob"
        private const val KEY_DB_IV = "db_passphrase_iv"
    }
}
