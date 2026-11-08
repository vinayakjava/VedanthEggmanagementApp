package com.eggbusinessmanager.data.backup.format

import com.eggbusinessmanager.domain.backup.BackupException
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupHeader
import com.eggbusinessmanager.domain.backup.BackupTrigger
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.ebmbackup` binary layout (contracts/backup-format.md):
 * magic(8) · headerLength(uint32 BE) · header JSON · wrapNonce(12) · wrappedKey(48) · payload.
 * The header is plaintext without business data and is authenticated as GCM associated data.
 */
object BackupFileFormat {
    /** ASCII `EBMBAK` followed by 0x00 0x01. */
    val MAGIC: ByteArray = "EBMBAK".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 1)
    const val MAX_HEADER = 64 * 1024
    const val NONCE_LENGTH = 12
    const val WRAPPED_KEY_LENGTH = 48

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    internal data class Kdf(val alg: String, val iterations: Int, val salt: String)

    @Serializable
    internal data class HeaderJson(
        val formatVersion: Int,
        val createdAt: String,
        val appVersionName: String,
        val appVersionCode: Long,
        val schemaVersion: Int,
        val deviceModel: String,
        val trigger: String,
        val kdf: Kdf,
        val payloadLength: Long,
        val payloadSha256: String,
    )

    fun encodeHeader(header: BackupHeader): ByteArray = json.encodeToString(
        HeaderJson.serializer(),
        HeaderJson(
            formatVersion = header.formatVersion,
            createdAt = header.createdAt.toString(),
            appVersionName = header.appVersionName,
            appVersionCode = header.appVersionCode,
            schemaVersion = header.schemaVersion,
            deviceModel = header.deviceModel,
            trigger = header.trigger.name,
            kdf = Kdf(BackupCrypto.KDF_ALGORITHM, header.kdfIterations, Base64.getEncoder().encodeToString(header.salt)),
            payloadLength = header.payloadLength,
            payloadSha256 = header.payloadSha256,
        ),
    ).toByteArray(Charsets.UTF_8)

    /** The parsed file start. [headerBytes] are kept exactly as stored: they are the GCM associated data. */
    class Opened(
        val header: BackupHeader,
        val headerBytes: ByteArray,
        val nonce: ByteArray,
        val wrappedKey: ByteArray,
        val payloadOffset: Long,
    )

    /**
     * Step 1 of restore validation: magic, header and format version.
     * Throws NOT_A_BACKUP for anything that is not our file, NEWER_VERSION for a newer format.
     */
    fun open(file: File): Opened = file.inputStream().buffered().use { open(it) }

    fun open(input: InputStream): Opened = try {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        if (!magic.contentEquals(MAGIC)) throw BackupException(BackupFailure.NOT_A_BACKUP)
        val length = data.readInt()
        if (length <= 0 || length > MAX_HEADER) throw BackupException(BackupFailure.NOT_A_BACKUP)
        val headerBytes = ByteArray(length).also(data::readFully)
        val header = decodeHeader(headerBytes)
        val nonce = ByteArray(NONCE_LENGTH).also(data::readFully)
        val wrapped = ByteArray(WRAPPED_KEY_LENGTH).also(data::readFully)
        Opened(header, headerBytes, nonce, wrapped, (MAGIC.size + Int.SIZE_BYTES + length + NONCE_LENGTH + WRAPPED_KEY_LENGTH).toLong())
    } catch (e: EOFException) {
        throw BackupException(BackupFailure.NOT_A_BACKUP, "File too short", e)
    }

    internal fun decodeHeader(bytes: ByteArray): BackupHeader {
        val dto = try {
            json.decodeFromString(HeaderJson.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            notABackup("Unreadable header", e)
        } catch (e: IllegalArgumentException) {
            notABackup("Unreadable header", e)
        }
        if (dto.formatVersion > BackupHeader.FORMAT_VERSION) throw BackupException(BackupFailure.NEWER_VERSION)
        if (dto.formatVersion < 1 || dto.kdf.alg != BackupCrypto.KDF_ALGORITHM) notABackup("Unknown format")
        return try {
            BackupHeader(
                formatVersion = dto.formatVersion,
                createdAt = Instant.parse(dto.createdAt),
                appVersionName = dto.appVersionName,
                appVersionCode = dto.appVersionCode,
                schemaVersion = dto.schemaVersion,
                deviceModel = dto.deviceModel,
                // Unknown triggers from later app versions are read as manual backups.
                trigger = BackupTrigger.entries.firstOrNull { it.name == dto.trigger } ?: BackupTrigger.MANUAL,
                kdfIterations = dto.kdf.iterations,
                salt = Base64.getDecoder().decode(dto.kdf.salt),
                payloadLength = dto.payloadLength,
                payloadSha256 = dto.payloadSha256,
            )
        } catch (e: DateTimeParseException) {
            notABackup("Bad header date", e)
        } catch (e: IllegalArgumentException) {
            notABackup("Bad header salt", e)
        }
    }

    private fun notABackup(reason: String, cause: Throwable? = null): Nothing =
        throw BackupException(BackupFailure.NOT_A_BACKUP, reason, cause)

    /** Writes a complete backup: [payload] is copied after the wrapped key. Does not close [out]. */
    fun write(out: OutputStream, headerBytes: ByteArray, nonce: ByteArray, wrappedKey: ByteArray, payload: File) {
        require(nonce.size == NONCE_LENGTH && wrappedKey.size == WRAPPED_KEY_LENGTH)
        out.write(MAGIC)
        out.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(headerBytes.size).array())
        out.write(headerBytes)
        out.write(nonce)
        out.write(wrappedKey)
        payload.inputStream().use { it.copyTo(out) }
        out.flush()
    }
}

/** Key derivation, key wrapping and hashing for backups (contracts/backup-format.md § Cryptography). */
object BackupCrypto {
    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 600_000
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    /** KEK = PBKDF2-HMAC-SHA256(NFKC passphrase, salt, iterations) → 32 bytes. */
    fun deriveKek(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = Normalizer.normalize(passphrase, Normalizer.Form.NFKC).toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BYTES * Byte.SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    /** AES-256-GCM(KEK, K) with the header as associated data; returns nonce and wrapped key. */
    fun wrap(kek: ByteArray, key: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = randomBytes(BackupFileFormat.NONCE_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce to cipher.doFinal(key)
    }

    /** Step 2: a wrong passphrase (or an altered header) fails the GCM tag check. */
    fun unwrap(kek: ByteArray, nonce: ByteArray, wrapped: ByteArray, aad: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(wrapped)
    } catch (e: AEADBadTagException) {
        throw BackupException(BackupFailure.WRONG_PASSPHRASE, "Key unwrap failed", e)
    } catch (e: GeneralSecurityException) {
        throw BackupException(BackupFailure.CORRUPT, "Key unwrap failed", e)
    }

    fun sha256Hex(input: InputStream, limit: Long = Long.MAX_VALUE): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = limit
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            digest.update(buffer, 0, read)
            remaining -= read
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
