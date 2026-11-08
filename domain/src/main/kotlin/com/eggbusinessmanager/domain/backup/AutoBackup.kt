package com.eggbusinessmanager.domain.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupFrequency { DAILY, WEEKLY, MONTHLY }

/**
 * Automatic backup preferences (Phase 10). Device-only: they are not part of a backup, so restoring data
 * from another phone never changes this phone's schedule. Automatic backups go to Google Drive.
 */
data class AutoBackupSettings(
    val enabled: Boolean = false,
    val frequency: BackupFrequency = BackupFrequency.DAILY,
    val preferredTime: LocalTime = LocalTime.of(2, 0),
    val dayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    /** 1–28 so every month has the day. */
    val dayOfMonth: Int = 1,
    val destination: BackupDestination = BackupDestination.GOOGLE_DRIVE,
    /** Automatic backups kept in Drive; older automatic ones are deleted. Manual backups are never deleted. */
    val keepLast: Int = 10,
    val wifiOnly: Boolean = false,
    val onlyWhileCharging: Boolean = false,
) {
    init {
        require(dayOfMonth in 1..MAX_DAY_OF_MONTH) { "Day of month must be 1–$MAX_DAY_OF_MONTH" }
        require(keepLast in RETENTION_CHOICES) { "Unsupported retention $keepLast" }
    }

    companion object {
        const val MAX_DAY_OF_MONTH = 28
        val RETENTION_CHOICES = listOf(5, 10, 20, 30)
    }
}

/**
 * The backup slots implied by the settings: daily at the preferred time, weekly on the chosen weekday, or
 * monthly on the chosen day. These are the *earliest* times a backup may start: Android runs background
 * work when conditions allow (network, battery, Doze), so the actual time can be later (FR-049).
 */
object BackupSchedule {

    /** The first slot strictly after [now]. */
    fun next(settings: AutoBackupSettings, now: Instant, zone: ZoneId): Instant {
        val local = now.atZone(zone)
        var candidate = slotOnOrBefore(settings, local)
        while (!candidate.toInstant().isAfter(now)) candidate = advance(settings, candidate)
        return candidate.toInstant()
    }

    /** The latest slot at or before [now]: the start of the current period. */
    fun previous(settings: AutoBackupSettings, now: Instant, zone: ZoneId): Instant {
        val local = now.atZone(zone)
        var candidate = slotOnOrBefore(settings, local)
        while (candidate.toInstant().isAfter(now)) candidate = back(settings, candidate)
        return candidate.toInstant()
    }

    /** A slot in the same day/week/month as [local] (may be after it). */
    private fun slotOnOrBefore(settings: AutoBackupSettings, local: ZonedDateTime): ZonedDateTime {
        val date: LocalDate = when (settings.frequency) {
            BackupFrequency.DAILY -> local.toLocalDate()
            BackupFrequency.WEEKLY -> local.toLocalDate().with(TemporalAdjusters.previousOrSame(settings.dayOfWeek))
            BackupFrequency.MONTHLY -> local.toLocalDate().withDayOfMonth(settings.dayOfMonth)
        }
        return ZonedDateTime.of(date, settings.preferredTime, local.zone)
    }

    private fun advance(settings: AutoBackupSettings, slot: ZonedDateTime): ZonedDateTime = at(
        settings,
        when (settings.frequency) {
            BackupFrequency.DAILY -> slot.toLocalDate().plusDays(1)
            BackupFrequency.WEEKLY -> slot.toLocalDate().plusWeeks(1)
            BackupFrequency.MONTHLY -> slot.toLocalDate().plusMonths(1).withDayOfMonth(settings.dayOfMonth)
        },
        slot.zone,
    )

    private fun back(settings: AutoBackupSettings, slot: ZonedDateTime): ZonedDateTime = at(
        settings,
        when (settings.frequency) {
            BackupFrequency.DAILY -> slot.toLocalDate().minusDays(1)
            BackupFrequency.WEEKLY -> slot.toLocalDate().minusWeeks(1)
            BackupFrequency.MONTHLY -> slot.toLocalDate().minusMonths(1).withDayOfMonth(settings.dayOfMonth)
        },
        slot.zone,
    )

    /** Local wall-clock time; on a DST gap Java moves it forward, which is fine for "around this time". */
    private fun at(settings: AutoBackupSettings, date: LocalDate, zone: ZoneId) = ZonedDateTime.of(date, settings.preferredTime, zone)
}

/** Where automatic backup settings are kept on this phone. */
interface AutoBackupSettingsRepository {
    fun observe(): Flow<AutoBackupSettings>

    suspend fun save(settings: AutoBackupSettings)
}

/**
 * Schedules the background work (WorkManager in the app). [schedule] replaces any pending run, so there is
 * never more than one; [ensureScheduled] keeps an existing one.
 */
interface AutoBackupScheduler {
    fun schedule(settings: AutoBackupSettings, at: Instant)

    fun ensureScheduled(settings: AutoBackupSettings, at: Instant)

    fun cancel()
}

/** Tells the owner, outside the app, that automatic backups keep failing. */
interface BackupNotifier {
    fun notifyFailure(failure: BackupFailure)

    fun clear()
}

/**
 * One backup or restore at a time across the app — manual, automatic and restore staging — so two runs
 * can never write or replace data concurrently (duplicate prevention).
 */
@Singleton
class BackupLock @Inject constructor() {
    val mutex = Mutex()
}
