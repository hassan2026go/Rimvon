package com.rimvon.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

/**
 * End-to-end encryption: static ECDH P-256 identity key per account,
 * HKDF-SHA256 -> AES-256-GCM with a random 96-bit IV per message.
 * The relay server only ever sees ciphertext.
 */
object Crypto {
    private val rnd = SecureRandom()

    fun genKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        return g.generateKeyPair()
    }

    fun pubB64(pub: PublicKey): String = b64(pub.encoded)

    fun parsePub(b64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(b64)))

    fun parsePriv(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    /** ECDH + HKDF -> 32-byte AES key shared by the two accounts. */
    fun sharedKey(priv: PrivateKey, theirPubB64: String): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(parsePub(theirPubB64), true)
        val secret = ka.generateSecret()
        val prk = hmac(ByteArray(32), secret)
        return hmac(prk, "rimvon-v1".toByteArray() + byteArrayOf(1))
    }

    fun encrypt(key: ByteArray, plain: ByteArray): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return b64(iv + c.doFinal(plain))
    }

    fun decrypt(key: ByteArray, envB64: String): ByteArray {
        val raw = unb64(envB64)
        require(raw.size > 28) { "short envelope" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return c.doFinal(raw, 12, raw.size - 12)
    }

    /** Must match the server: first 16 chars of base64(sha256(pubkey bytes)). */
    fun fingerprint(pubB64: String): String =
        b64(MessageDigest.getInstance("SHA-256").digest(unb64(pubB64))).substring(0, 16)

    /** Same 30-digit code on both phones if (and only if) both see the same two keys. */
    fun safetyNumber(a: String, b: String): String {
        val (x, y) = if (a <= b) a to b else b to a
        val h = MessageDigest.getInstance("SHA-256").digest((x + "|" + y).toByteArray())
        val groups = (0 until 6).map { i ->
            val v = ((h[i * 3].toInt() and 0xFF) shl 16) or
                ((h[i * 3 + 1].toInt() and 0xFF) shl 8) or (h[i * 3 + 2].toInt() and 0xFF)
            "%05d".format(v % 100000)
        }
        return groups.joinToString(" ")
    }

    fun randomToken(): String = b64(ByteArray(32).also { rnd.nextBytes(it) })
}

/** Encrypts small secrets (private key, auth token) with a non-exportable Android Keystore key. */
class Vault(@Suppress("unused") private val ctx: Context) {
    private val alias = "rimvon_vault_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return b64(c.iv + c.doFinal(plain))
    }

    fun open(sealed: String): ByteArray {
        val raw = unb64(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return c.doFinal(raw, 12, raw.size - 12)
    }
}
