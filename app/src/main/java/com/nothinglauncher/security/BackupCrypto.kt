package com.nothinglauncher.security

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption (PBKDF2WithHmacSHA256 + AES-256-GCM) for protecting
 * Hidden Vault data in exported launcher backups.
 *
 * Pure JVM + javax.crypto implementation with zero external library footprint.
 */
object BackupCrypto {

    const val FORMAT_VERSION = 1
    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val KDF_ITERATIONS = 120_000
    const val MIN_PASSPHRASE_LEN = 6

    private const val MIN_ITERATIONS = 50_000
    private const val MAX_ITERATIONS = 500_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val MAX_CIPHERTEXT_BYTES = 64 * 1024
    private const val PACKAGE_MAX_LEN = 180

    private val AAD = "not-a-thing-vault-v1".toByteArray(Charsets.UTF_8)
    private val PACKAGE_REGEX = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$")
    private val random = SecureRandom()

    data class VaultPayload(
        val hiddenPackages: Set<String>,
        val hiddenLabels: Map<String, String>
    )

    /** Encrypts [payload] with [passphrase] into an authenticated AES-GCM envelope JSON object. */
    fun encryptVault(payload: VaultPayload, passphrase: CharArray): JSONObject {
        require(passphrase.size >= MIN_PASSPHRASE_LEN) {
            "Passphrase must be at least $MIN_PASSPHRASE_LEN characters"
        }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val keyBytes = deriveKey(passphrase, salt, KDF_ITERATIONS)
        try {
            val plaintextJson = JSONObject().apply {
                put("hiddenPackages", JSONArray(payload.hiddenPackages.sorted()))
                val labelsObj = JSONObject()
                for ((key, label) in payload.hiddenLabels) {
                    val pkg = key.substringBefore('/')
                    if (pkg in payload.hiddenPackages && label.isNotBlank()) {
                        labelsObj.put(key.take(256), label.take(64))
                    }
                }
                put("hiddenLabels", labelsObj)
            }.toString().toByteArray(Charsets.UTF_8)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val ciphertext = cipher.doFinal(plaintextJson)

            return JSONObject().apply {
                put("v", FORMAT_VERSION)
                put("kdf", KDF_ALGORITHM)
                put("iter", KDF_ITERATIONS)
                put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                put("ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            }
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }

    /**
     * Decrypts and authenticates an `"encryptedVault"` JSON envelope.
     * Returns `null` if the passphrase is incorrect or the envelope/tag is malformed or tampered.
     */
    fun decryptVault(envelope: JSONObject, passphrase: CharArray): VaultPayload? {
        if (passphrase.isEmpty()) return null
        if (envelope.optInt("v", -1) != FORMAT_VERSION) return null
        if (envelope.optString("kdf") != KDF_ALGORITHM) return null
        val iterations = envelope.optInt("iter", -1)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return null

        val salt = decodeStrictBase64(envelope.optString("salt")) ?: return null
        val iv = decodeStrictBase64(envelope.optString("iv")) ?: return null
        val ciphertext = decodeStrictBase64(envelope.optString("ct")) ?: return null
        if (salt.size !in SALT_BYTES..32) return null
        if (iv.size != IV_BYTES) return null
        if (ciphertext.size !in 16..MAX_CIPHERTEXT_BYTES) return null

        val keyBytes = deriveKey(passphrase, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val plainBytes = cipher.doFinal(ciphertext)
            val root = JSONObject(String(plainBytes, Charsets.UTF_8))
            val pkgs = linkedSetOf<String>()
            root.optJSONArray("hiddenPackages")?.let { arr ->
                val cap = arr.length().coerceAtMost(500)
                for (i in 0 until cap) {
                    val pkg = arr.optString(i).trim()
                    if (pkg.length <= PACKAGE_MAX_LEN && PACKAGE_REGEX.matches(pkg)) {
                        pkgs.add(pkg)
                    }
                }
            }
            val labels = linkedMapOf<String, String>()
            root.optJSONObject("hiddenLabels")?.let { obj ->
                val keys = obj.keys()
                var count = 0
                while (keys.hasNext() && count < 500) {
                    val key = keys.next()
                    val pkg = key.substringBefore('/')
                    if (key.length <= 256 && pkg in pkgs) {
                        val lbl = obj.optString(key).trim().take(64)
                        if (lbl.isNotEmpty()) labels[key] = lbl
                    }
                    count++
                }
            }
            VaultPayload(hiddenPackages = pkgs, hiddenLabels = labels)
        } catch (_: Exception) {
            null
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun decodeStrictBase64(raw: String?): ByteArray? {
        if (raw.isNullOrBlank()) return null
        return try {
            Base64.decode(raw, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
