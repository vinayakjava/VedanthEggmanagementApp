package com.eggbusinessmanager.data.backup.format

import com.eggbusinessmanager.domain.backup.BackupException
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupHeader
import com.eggbusinessmanager.domain.backup.BackupTrigger
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

class BackupFileFormatTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Few iterations keep the JVM tests fast; the format stores the real count per file. */
    private val iterations = 1_000

    private fun header(payload: ByteArray, salt: ByteArray = BackupCrypto.randomBytes(16)) = BackupHeader(
        formatVersion = 1,
        createdAt = Instant.parse("2026-11-08T09:05:00Z"),
        appVersionName = "1.0.0",
        appVersionCode = 10_000,
        schemaVersion = 1,
        deviceModel = "Test Phone",
        trigger = BackupTrigger.MANUAL,
        kdfIterations = iterations,
        salt = salt,
        payloadLength = payload.size.toLong(),
        payloadSha256 = BackupCrypto.sha256Hex(ByteArrayInputStream(payload)),
    )

    private fun failure(block: () -> Unit): BackupFailure = assertThrows(BackupException::class.java) { block() }.failure

    /** A complete file built the way the engine builds it. */
    private fun backupBytes(passphrase: String, payload: ByteArray, key: ByteArray = BackupCrypto.randomBytes(32)): ByteArray {
        val h = header(payload)
        val headerBytes = BackupFileFormat.encodeHeader(h)
        val (nonce, wrapped) = BackupCrypto.wrap(BackupCrypto.deriveKek(passphrase, h.salt, iterations), key, headerBytes)
        val file = temp.newFile().apply { writeBytes(payload) }
        return ByteArrayOutputStream().also { BackupFileFormat.write(it, headerBytes, nonce, wrapped, file) }.toByteArray()
    }

    @Test
    fun `round trip - header, wrapped key and payload come back exactly`() {
        val payload = ByteArray(5_000) { (it % 251).toByte() }
        val key = BackupCrypto.randomBytes(32)
        val bytes = backupBytes("Ramesh-backup-26", payload, key)

        val opened = BackupFileFormat.open(ByteArrayInputStream(bytes))
        assertThat(opened.header.deviceModel).isEqualTo("Test Phone")
        assertThat(opened.header.payloadLength).isEqualTo(5_000)
        val kek = BackupCrypto.deriveKek("Ramesh-backup-26", opened.header.salt, opened.header.kdfIterations)
        assertThat(BackupCrypto.unwrap(kek, opened.nonce, opened.wrappedKey, opened.headerBytes)).isEqualTo(key)
        val stored = bytes.copyOfRange(opened.payloadOffset.toInt(), bytes.size)
        assertThat(stored).isEqualTo(payload)
        assertThat(BackupCrypto.sha256Hex(ByteArrayInputStream(stored))).isEqualTo(opened.header.payloadSha256)
    }

    @Test
    fun `the header holds versions and KDF data only, never business data`() {
        val text = BackupFileFormat.encodeHeader(header(byteArrayOf(1))).toString(Charsets.UTF_8)
        assertThat(text).contains("\"formatVersion\":1")
        assertThat(text).contains("\"alg\":\"PBKDF2WithHmacSHA256\"")
        assertThat(text).doesNotContain("Patil")
        val decoded = BackupFileFormat.decodeHeader(text.toByteArray())
        assertThat(decoded).isEqualTo(header(byteArrayOf(1)).copy(salt = decoded.salt))
    }

    @Test
    fun `a wrong passphrase fails the key check`() {
        val bytes = backupBytes("Ramesh-backup-26", byteArrayOf(1, 2, 3))
        val opened = BackupFileFormat.open(ByteArrayInputStream(bytes))
        val wrongKek = BackupCrypto.deriveKek("ramesh-backup-26", opened.header.salt, opened.header.kdfIterations)
        assertThat(failure { BackupCrypto.unwrap(wrongKek, opened.nonce, opened.wrappedKey, opened.headerBytes) })
            .isEqualTo(BackupFailure.WRONG_PASSPHRASE)
    }

    @Test
    fun `an altered header is detected even with the right passphrase`() {
        val bytes = backupBytes("Ramesh-backup-26", byteArrayOf(1, 2, 3))
        val opened = BackupFileFormat.open(ByteArrayInputStream(bytes))
        val tampered = opened.headerBytes.toString(Charsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":0").toByteArray()
        val kek = BackupCrypto.deriveKek("Ramesh-backup-26", opened.header.salt, opened.header.kdfIterations)
        assertThat(failure { BackupCrypto.unwrap(kek, opened.nonce, opened.wrappedKey, tampered) })
            .isEqualTo(BackupFailure.WRONG_PASSPHRASE)
    }

    @Test
    fun `the passphrase is normalised so look-alike input still matches`() {
        val salt = BackupCrypto.randomBytes(16)
        // "ﬁ" (U+FB01 ligature) normalises to "fi" under NFKC.
        fun kek(text: String) = BackupCrypto.deriveKek(text, salt, iterations)
        assertThat(kek("ﬁsh-eggs-2026")).isEqualTo(kek("fish-eggs-2026"))
        assertThat(kek("fish-eggs-2026")).isNotEqualTo(kek("fish-eggs-2027"))
    }

    @Test
    fun `files that are not backups are rejected`() {
        val pdf = "%PDF-1.7 hello".toByteArray()
        assertThat(failure { BackupFileFormat.open(ByteArrayInputStream(pdf)) }).isEqualTo(BackupFailure.NOT_A_BACKUP)
        assertThat(failure { BackupFileFormat.open(ByteArrayInputStream(BackupFileFormat.MAGIC)) }).isEqualTo(BackupFailure.NOT_A_BACKUP)
        assertThat(failure { BackupFileFormat.open(ByteArrayInputStream(ByteArray(0))) }).isEqualTo(BackupFailure.NOT_A_BACKUP)
        val hugeHeader = BackupFileFormat.MAGIC + byteArrayOf(0x7F, 0, 0, 0)
        assertThat(failure { BackupFileFormat.open(ByteArrayInputStream(hugeHeader)) }).isEqualTo(BackupFailure.NOT_A_BACKUP)
        val garbage = BackupFileFormat.MAGIC + byteArrayOf(0, 0, 0, 3) + "{x}".toByteArray()
        assertThat(failure { BackupFileFormat.open(ByteArrayInputStream(garbage)) }).isEqualTo(BackupFailure.NOT_A_BACKUP)
        // A truncated file (header fine, wrapped key cut off).
        val good = backupBytes("Ramesh-backup-26", byteArrayOf(1))
        val opened = BackupFileFormat.open(ByteArrayInputStream(good))
        assertThat(failure { BackupFileFormat.open(ByteArrayInputStream(good.copyOf(opened.payloadOffset.toInt() - 10))) })
            .isEqualTo(BackupFailure.NOT_A_BACKUP)
    }

    @Test
    fun `a newer format version asks for an app update`() {
        val text = BackupFileFormat.encodeHeader(header(byteArrayOf(1))).toString(Charsets.UTF_8)
            .replace("\"formatVersion\":1", "\"formatVersion\":2")
        assertThat(failure { BackupFileFormat.decodeHeader(text.toByteArray()) }).isEqualTo(BackupFailure.NEWER_VERSION)
    }

    @Test
    fun `unknown header fields and triggers from later versions are tolerated`() {
        val text = BackupFileFormat.encodeHeader(header(byteArrayOf(1))).toString(Charsets.UTF_8)
            .replace("\"trigger\":\"MANUAL\"", "\"trigger\":\"SCHEDULED_WEEKLY\",\"extra\":42")
        assertThat(BackupFileFormat.decodeHeader(text.toByteArray()).trigger).isEqualTo(BackupTrigger.MANUAL)
    }

    @Test
    fun `sha256 matches a known vector and respects the limit`() {
        assertThat(BackupCrypto.sha256Hex(ByteArrayInputStream("abc".toByteArray())))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(BackupCrypto.sha256Hex(ByteArrayInputStream("abcdef".toByteArray()), limit = 3))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
