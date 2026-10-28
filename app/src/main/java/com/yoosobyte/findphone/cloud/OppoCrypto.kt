package com.yoosobyte.findphone.cloud

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `find.oppo.com` 的请求信封。
 *
 * 网页端的做法是：
 * 1. 生成两个 16 位随机字符串（字符集 `a-z0-9`），分别作为 AES Key / IV；
 * 2. 对这两个字符串做 Base64，得到 24 字符的字符串；
 * 3. 用 512 位 RSA 公钥（PKCS#1 v1.5）加密这两个 Base64 字符串；
 * 4. 用 AES-128-CTR / NoPadding 加密 JSON；
 * 5. 请求体 = Base64(RSA(keyB64)) + "." + Base64(RSA(ivB64)) + "." + Base64(AES(json))。
 *
 * 响应体用同一个 AES Key / IV 解密。
 */
internal object OppoCrypto {

    private const val PUBLIC_KEY_BASE64 =
        "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAKMjoNhKnpqt47eBRdaccgOlCYqkBIvqBzLkRTakY8/3Bz79EYcmpd3LJ61KoougpkYEGTNZ/pfEisXNKoG+uC8CAwEAAQ=="

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val KEY_LENGTH_CHARS = 16

    private val random = SecureRandom()

    data class Envelope(
        val key: ByteArray,
        val iv: ByteArray,
        val body: String,
    )

    fun seal(payload: JSONObject): Envelope {
        val keyChars = randomString()
        val ivChars = randomString()
        val keyB64 = base64(keyChars.toByteArray(Charsets.US_ASCII))
        val ivB64 = base64(ivChars.toByteArray(Charsets.US_ASCII))

        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, publicKey())
        val encryptedKey = rsa.doFinal(keyB64.toByteArray(Charsets.US_ASCII))
        val encryptedIv = rsa.doFinal(ivB64.toByteArray(Charsets.US_ASCII))

        val keyBytes = keyChars.toByteArray(Charsets.US_ASCII)
        val ivBytes = ivChars.toByteArray(Charsets.US_ASCII)
        val aes = Cipher.getInstance("AES/CTR/NoPadding")
        aes.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivBytes),
        )
        val cipherText = aes.doFinal(payload.toString().toByteArray(Charsets.UTF_8))

        return Envelope(
            key = keyBytes,
            iv = ivBytes,
            body = base64(encryptedKey) + "." + base64(encryptedIv) + "." + base64(cipherText),
        )
    }

    fun decrypt(body: String, key: ByteArray, iv: ByteArray): String {
        val clean = body.filterNot { it.isWhitespace() }
        val cipherText = Base64.decode(clean, Base64.DEFAULT)
        val aes = Cipher.getInstance("AES/CTR/NoPadding")
        aes.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        return String(aes.doFinal(cipherText), Charsets.UTF_8)
    }

    /**
     * 与网页端 `x()` 完全一致的 djb2 变体：
     * `a = 5381; a += (int32)(a << 5) + charCode; return a & 0x7fffffff`。
     *
     * 注意 JS 里 `a` 本身可以超过 32 位，只有 `a << 5` 会被截断成 int32，
     * 所以这里用 Long 保存累加值，再按 32 位取移位结果。
     */
    fun ctoken(tokenKey: String): Int {
        var accumulator = 5381L
        for (char in tokenKey) {
            val low = accumulator and 0xFFFFFFFFL
            val shifted = (low shl 5) and 0xFFFFFFFFL
            val signedShift = if (shifted >= 0x80000000L) shifted - 0x100000000L else shifted
            accumulator += signedShift + char.code
        }
        return (accumulator and 0x7FFFFFFFL).toInt()
    }

    private fun randomString(): String {
        val builder = StringBuilder(KEY_LENGTH_CHARS)
        repeat(KEY_LENGTH_CHARS) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return builder.toString()
    }

    private fun base64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    @Volatile
    private var cachedKey: java.security.PublicKey? = null

    private fun publicKey(): java.security.PublicKey {
        cachedKey?.let { return it }
        val der = Base64.decode(PUBLIC_KEY_BASE64, Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(der))
        cachedKey = key
        return key
    }
}
