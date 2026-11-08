package com.eggbusinessmanager.domain.backup

import java.time.Instant
import java.time.LocalDate

/** Where a backup is written or read (FR-044). */
enum class BackupDestination { DEVICE, GOOGLE_DRIVE }

/**
 * Why a backup was made. SAFETY backups are taken automatically just before a restore replaces the data
 * (FR-051); they stay in the app's private storage so the previous data can always be brought back.
 */
enum class BackupTrigger { MANUAL, AUTOMATIC, SAFETY }

enum class BackupOperation { BACKUP, RESTORE }

enum class BackupStatus { IN_PROGRESS, SUCCESS, FAILED }

/** How the last confirmed restore ended, reported once after the app restarts. */
enum class RestoreOutcome { RESTORED, ROLLED_BACK }

/** Every way a backup or restore can fail, each with its own message (contracts/backup-format.md). */
enum class BackupFailure {
    NO_PASSPHRASE,
    NO_NETWORK,
    AUTH_REQUIRED,
    DRIVE_FULL,
    LOCATION_UNAVAILABLE,
    LOW_STORAGE,
    WRONG_PASSPHRASE,
    CORRUPT,
    NEWER_VERSION,
    NOT_A_BACKUP,
    INTERRUPTED,

    /** Another backup or restore is running right now. */
    BUSY,
    UNKNOWN,
}

/** A typed backup failure; never carries passphrases or keys in its message. */
class BackupException(val failure: BackupFailure, message: String = failure.name, cause: Throwable? = null) :
    Exception(message, cause)

/** One row of the backup history (data-model.md § backup_record). */
data class BackupRecord(
    val id: Long,
    val operation: BackupOperation,
    val destination: BackupDestination,
    val trigger: BackupTrigger,
    val status: BackupStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val sizeBytes: Long?,
    val fileName: String?,
    val driveFileId: String?,
    val schemaVersion: Int,
    val failure: BackupFailure?,
)

/**
 * The plaintext header of a backup file: versions, KDF parameters and integrity data only, never business
 * data (contracts/backup-format.md). The header bytes are authenticated as AES-GCM associated data.
 */
data class BackupHeader(
    val formatVersion: Int,
    val createdAt: Instant,
    val appVersionName: String,
    val appVersionCode: Long,
    val schemaVersion: Int,
    val deviceModel: String,
    val trigger: BackupTrigger,
    val kdfIterations: Int,
    val salt: ByteArray,
    val payloadLength: Long,
    val payloadSha256: String,
) {
    override fun equals(other: Any?): Boolean = other is BackupHeader &&
        formatVersion == other.formatVersion && createdAt == other.createdAt && appVersionName == other.appVersionName &&
        appVersionCode == other.appVersionCode && schemaVersion == other.schemaVersion && deviceModel == other.deviceModel &&
        trigger == other.trigger && kdfIterations == other.kdfIterations && salt.contentEquals(other.salt) &&
        payloadLength == other.payloadLength && payloadSha256 == other.payloadSha256

    override fun hashCode(): Int = payloadSha256.hashCode()

    companion object {
        const val FORMAT_VERSION = 1
        const val EXTENSION = "ebmbackup"
        const val MIME_TYPE = "application/octet-stream"
    }
}

/** What a backup contains, read after decryption, shown before anything is replaced (US9-6). */
data class BackupSummary(
    val createdAt: Instant,
    val shopName: String?,
    val ownerName: String?,
    val customers: Int,
    val entries: Int,
    val lastEntryDate: LocalDate?,
    val schemaVersion: Int,
    val deviceModel: String,
)

/** Counts of the data on this phone now, shown next to the backup's so nothing is overwritten by surprise. */
data class CurrentDataSummary(val shopName: String?, val customers: Int, val entries: Int, val lastEntryDate: LocalDate?)

/** A backup file produced locally, ready to copy or upload. [path] is a private app file. */
data class BackupArtifact(val path: String, val fileName: String, val sizeBytes: Long, val header: BackupHeader)

/** A backup stored in the app's hidden Google Drive folder, listed without downloading it. */
data class RemoteBackup(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val sizeBytes: Long,
    val trigger: BackupTrigger,
    val appVersion: String?,
    val device: String?,
)

/** Passphrase rules (FR-045): long enough, confirmed, and different from the login password. */
object BackupPassphraseRules {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 64

    enum class Problem { TOO_SHORT, TOO_LONG, MISMATCH, SAME_AS_PASSWORD }

    fun check(passphrase: String, confirmation: String, sameAsLoginPassword: Boolean): Problem? = when {
        passphrase.length < MIN_LENGTH -> Problem.TOO_SHORT
        passphrase.length > MAX_LENGTH -> Problem.TOO_LONG
        passphrase != confirmation -> Problem.MISMATCH
        sameAsLoginPassword -> Problem.SAME_AS_PASSWORD
        else -> null
    }
}

/** `EggBusiness_<shop-slug>_<yyyyMMdd-HHmm>.ebmbackup` (contracts/backup-format.md § File name). */
object BackupFileName {
    private const val MAX_SLUG = 24

    fun of(shopName: String?, createdAt: java.time.ZonedDateTime): String {
        val ascii = java.text.Normalizer.normalize(shopName.orEmpty(), java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-')
            .take(MAX_SLUG)
            .trim('-')
            .ifEmpty { "shop" }
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(createdAt)
        return "EggBusiness_${ascii}_$stamp.${BackupHeader.EXTENSION}"
    }
}
