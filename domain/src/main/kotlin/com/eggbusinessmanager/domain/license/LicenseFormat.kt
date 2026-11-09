// Byte layouts (key payload, DER signatures, Base32) are defined by their bit and tag values.
@file:Suppress("MagicNumber")

package com.eggbusinessmanager.domain.license

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The lifetime license format, shared by the app (verify) and the owner's license tool (sign).
 *
 * A license key binds one license to one device:
 * `EV1-` + Crockford Base32 of `payload ‖ signature`, where
 * - payload = version (1 byte) ‖ license id (5 random bytes) ‖ device limit (1 byte) ‖ device hash (10 bytes)
 * - signature = ECDSA P-256 / SHA-256 over `DOMAIN ‖ payload`, as raw r ‖ s (64 bytes)
 *
 * The device hash is the first 10 bytes of SHA-256 over the public key of a key pair kept in the
 * phone's Android Keystore; the owner sees it as the 16-character Device code.
 */
object LicenseFormat {
    const val KEY_PREFIX = "EV1"
    private const val VERSION: Byte = 1
    const val LICENSE_ID_BYTES = 5
    const val DEVICE_HASH_BYTES = 10
    const val SIGNATURE_BYTES = 64
    private const val PAYLOAD_BYTES = 1 + LICENSE_ID_BYTES + 1 + DEVICE_HASH_BYTES
    const val MAX_DEVICE_LIMIT = 255
    private const val KEY_GROUP = 5
    private const val CODE_GROUP = 4
    private val DOMAIN = "EGGVYAPAR-LICENSE-V1".toByteArray(Charsets.US_ASCII)

    /** What a license key states. */
    data class Payload(val licenseId: ByteArray, val maxDevices: Int, val deviceHash: ByteArray) {
        init {
            require(licenseId.size == LICENSE_ID_BYTES) { "License id must be $LICENSE_ID_BYTES bytes" }
            require(deviceHash.size == DEVICE_HASH_BYTES) { "Device hash must be $DEVICE_HASH_BYTES bytes" }
            require(maxDevices in 1..MAX_DEVICE_LIMIT) { "Device limit must be 1–$MAX_DEVICE_LIMIT" }
        }

        val licenseIdText: String get() = licenseIdText(licenseId)

        fun bytes(): ByteArray = byteArrayOf(VERSION) + licenseId + byteArrayOf(maxDevices.toByte()) + deviceHash

        override fun equals(other: Any?): Boolean = other is Payload && licenseId.contentEquals(other.licenseId) &&
            maxDevices == other.maxDevices && deviceHash.contentEquals(other.deviceHash)

        override fun hashCode(): Int = licenseId.contentHashCode() * 31 + deviceHash.contentHashCode()
    }

    /** A decoded (not yet verified) key. */
    class Decoded(val payload: Payload, val signature: ByteArray) {
        val signedBytes: ByteArray get() = DOMAIN + payload.bytes()
    }

    // ---- Device code ----

    /** The device hash of a Keystore public key (X.509 encoded). */
    fun deviceHash(publicKeyEncoded: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded).copyOf(DEVICE_HASH_BYTES)

    /** E.g. `7K2Q-M9XP-4RTD-H8WN`. */
    fun deviceCode(deviceHash: ByteArray): String = Base32.encode(deviceHash).chunked(CODE_GROUP).joinToString("-")

    /** The device hash in a code as typed or pasted; null when it is not a device code. */
    fun parseDeviceCode(text: String): ByteArray? = Base32.decode(text)?.takeIf { it.size == DEVICE_HASH_BYTES }

    // ---- Keys ----

    fun signedBytes(payload: Payload): ByteArray = DOMAIN + payload.bytes()

    fun encodeKey(payload: Payload, signature: ByteArray): String {
        require(signature.size == SIGNATURE_BYTES) { "Signature must be raw r‖s" }
        return KEY_PREFIX + "-" + Base32.encode(payload.bytes() + signature).chunked(KEY_GROUP).joinToString("-")
    }

    /** Parses a key as pasted (any case, spaces, dashes, line breaks); null when it is not a key. */
    fun decodeKey(text: String): Decoded? {
        val cleaned = text.uppercase().filterNot { it.isWhitespace() || it == '-' }
        val bytes = cleaned.takeIf { it.startsWith(KEY_PREFIX) }?.let { Base32.decode(it.removePrefix(KEY_PREFIX)) }
            ?.takeIf { it.size == PAYLOAD_BYTES + SIGNATURE_BYTES && it[0] == VERSION }
        val maxDevices = bytes?.let { it[1 + LICENSE_ID_BYTES].toInt() and 0xFF }
        return if (bytes == null || maxDevices == null || maxDevices == 0) {
            null
        } else {
            val payload = Payload(
                licenseId = bytes.copyOfRange(1, 1 + LICENSE_ID_BYTES),
                maxDevices = maxDevices,
                deviceHash = bytes.copyOfRange(2 + LICENSE_ID_BYTES, PAYLOAD_BYTES),
            )
            Decoded(payload, bytes.copyOfRange(PAYLOAD_BYTES, bytes.size))
        }
    }

    /** `L-` + 8 Base32 characters, e.g. `L-7F3KQ2MZ`. */
    fun licenseIdText(licenseId: ByteArray): String = "L-" + Base32.encode(licenseId)

    fun parseLicenseId(text: String): ByteArray? =
        Base32.decode(text.trim().uppercase().removePrefix("L-"))?.takeIf { it.size == LICENSE_ID_BYTES }
}

/** ECDSA P-256 with SHA-256; signatures travel as raw r ‖ s (64 bytes) to keep keys short. */
object LicenseCrypto {
    private const val ALGORITHM = "SHA256withECDSA"
    private const val HALF = 32

    fun publicKey(base64X509: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64X509.trim())))

    fun privateKey(base64Pkcs8: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Pkcs8.trim())))

    fun sign(key: PrivateKey, data: ByteArray): ByteArray =
        derToRaw(Signature.getInstance(ALGORITHM).run { initSign(key); update(data); sign() })

    fun verify(key: PublicKey, data: ByteArray, raw: ByteArray): Boolean = try {
        Signature.getInstance(ALGORITHM).run { initVerify(key); update(data); verify(rawToDer(raw)) }
    } catch (ignored: java.security.SignatureException) {
        false // a malformed signature is simply not valid
    }

    /** DER `SEQUENCE { INTEGER r, INTEGER s }` → 32-byte r ‖ 32-byte s. */
    internal fun derToRaw(der: ByteArray): ByteArray {
        var i = 2 + if (der[1].toInt() and 0x80 != 0) der[1].toInt() and 0x7F else 0
        fun next(): ByteArray {
            val length = der[i + 1].toInt() and 0xFF
            val value = der.copyOfRange(i + 2, i + 2 + length)
            i += 2 + length
            return fixed(BigInteger(1, value))
        }
        return next() + next()
    }

    internal fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 2 * HALF) { "Raw signature must be 64 bytes" }
        fun integer(part: ByteArray): ByteArray {
            val value = BigInteger(1, part).toByteArray() // minimal, with a leading 0 when the top bit is set
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, HALF)) + integer(raw.copyOfRange(HALF, 2 * HALF))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun fixed(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().let { if (it.size > HALF) it.copyOfRange(it.size - HALF, it.size) else it }
        return ByteArray(HALF - bytes.size) + bytes
    }
}

/** Crockford Base32: no I, L, O, U; decoding forgives case and the look-alikes O→0, I/L→1. */
internal object Base32 {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val BITS = 5
    private const val MASK = 0x1F

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl Byte.SIZE_BITS) or (b.toInt() and 0xFF)
            bits += Byte.SIZE_BITS
            while (bits >= BITS) {
                out.append(ALPHABET[(buffer shr (bits - BITS)) and MASK])
                bits -= BITS
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (BITS - bits)) and MASK])
        return out.toString()
    }

    fun decode(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (raw in text.uppercase()) {
            if (raw == '-' || raw.isWhitespace()) continue
            val c = when (raw) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> raw
            }
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl BITS) or value
            bits += BITS
            if (bits >= Byte.SIZE_BITS) {
                out.write((buffer shr (bits - Byte.SIZE_BITS)) and 0xFF)
                bits -= Byte.SIZE_BITS
            }
        }
        return out.toByteArray()
    }
}
