package com.eggbusinessmanager.domain.settings

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.ValidationErrors
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.repository.AccountRepository
import com.eggbusinessmanager.domain.repository.BusinessProfileRepository
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.SettingsRepository
import com.eggbusinessmanager.domain.usecase.auth.PasswordConfirmedChange
import com.eggbusinessmanager.domain.validation.FieldValidators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject

/** How amounts are shown. Amounts are always stored in paise; this only changes display and PDFs. */
enum class CurrencySymbol(val text: String) { RUPEE_SIGN("₹"), RS("Rs.") }

/** Indian (1,00,000.00) or international (100,000.00) digit grouping. */
enum class DigitGrouping { INDIAN, INTERNATIONAL }

/** Date display formats; month names follow the app language. */
enum class DateStyle(val pattern: String) {
    DAY_MONTH_NAME("d MMM yyyy"),
    DD_MM_YYYY_SLASH("dd/MM/yyyy"),
    DD_MM_YYYY_DASH("dd-MM-yyyy"),
    ISO("yyyy-MM-dd"),
}

/**
 * App preferences that travel with backups (stored in `app_setting`): display formats and the unit
 * pre-selected on Sell/Buy.
 */
data class AppPreferences(
    val currencySymbol: CurrencySymbol = CurrencySymbol.RUPEE_SIGN,
    val grouping: DigitGrouping = DigitGrouping.INDIAN,
    val dateStyle: DateStyle = DateStyle.DAY_MONTH_NAME,
    /** Null: no unit pre-selected. */
    val defaultUnitId: Long? = null,
)

interface AppPreferencesRepository {
    fun observePreferences(): Flow<AppPreferences>

    suspend fun setPreferences(preferences: AppPreferences)

    suspend fun setAutoLockMinutes(minutes: Int)
}

class ObservePreferencesUseCase @Inject constructor(private val repository: AppPreferencesRepository) {
    operator fun invoke(): Flow<AppPreferences> = repository.observePreferences()
}

class UpdatePreferencesUseCase @Inject constructor(private val repository: AppPreferencesRepository) {
    suspend operator fun invoke(preferences: AppPreferences) = repository.setPreferences(preferences)
}

class SetAutoLockMinutesUseCase @Inject constructor(private val repository: AppPreferencesRepository) {
    suspend operator fun invoke(minutes: Int) {
        require(minutes in SettingsRepository.AUTO_LOCK_CHOICES) { "Unsupported auto-lock $minutes" }
        repository.setAutoLockMinutes(minutes)
    }
}

// ---- Shop and owner profile ----

/** Editable shop details, as typed. */
data class ShopProfileForm(
    val shopName: String = "",
    val address: String = "",
    val city: String = "",
    val state: IndianState? = null,
    val pinCode: String = "",
)

/** Editable owner details. [currentPassword] is needed only when the mobile number or email changes. */
data class OwnerProfileForm(
    val ownerName: String = "",
    val mobile: String = "",
    val email: String = "",
    val currentPassword: String = "",
)

/** Profile writes; the account's mobile and email are its sign-in identifiers. */
interface ProfileRepository {
    suspend fun updateShop(profile: BusinessProfile, at: Instant)

    suspend fun updateContact(mobile: String, email: String?, at: Instant)
}

class GetShopProfileFormUseCase @Inject constructor(private val profiles: BusinessProfileRepository) {
    suspend operator fun invoke(): ShopProfileForm = profiles.observe().first()?.let {
        ShopProfileForm(it.shopName, it.address.orEmpty(), it.city, it.state, it.pinCode)
    } ?: ShopProfileForm()
}

/** Shop name, address, city, state and PIN code, with the same rules as signup (FR-002). */
class UpdateShopProfileUseCase @Inject constructor(
    private val profiles: BusinessProfileRepository,
    private val repository: ProfileRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(form: ShopProfileForm): DomainResult<Unit> = catchingResult {
        val current = profiles.observe().first() ?: return DomainResult.Err(DomainError.NoAccount)
        val errors = ValidationErrors().apply {
            add(Field.SHOP_NAME, FieldValidators.requiredTextCode(form.shopName, FieldValidators.NAME_MIN, FieldValidators.NAME_MAX))
            add(Field.ADDRESS, FieldValidators.optionalTextCode(form.address, FieldValidators.ADDRESS_MAX))
            add(Field.CITY, FieldValidators.requiredTextCode(form.city, FieldValidators.CITY_MIN, FieldValidators.CITY_MAX))
            if (form.state == null) add(Field.STATE, ValidationCode.REQUIRED)
            add(Field.PIN_CODE, FieldValidators.pinCodeCode(form.pinCode))
        }
        errors.asFailure()?.let { return it }
        val updated = current.copy(
            shopName = form.shopName.trim(),
            address = form.address.trim().ifEmpty { null },
            city = form.city.trim(),
            state = checkNotNull(form.state),
            pinCode = form.pinCode.trim(),
        )
        repository.updateShop(updated, clock.now())
        DomainResult.Ok(Unit)
    }
}

class GetOwnerProfileFormUseCase @Inject constructor(
    private val profiles: BusinessProfileRepository,
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(): OwnerProfileForm {
        val credentials = accounts.credentials()
        return OwnerProfileForm(
            ownerName = profiles.observe().first()?.ownerName.orEmpty(),
            mobile = credentials?.mobile.orEmpty(),
            email = credentials?.email.orEmpty(),
        )
    }
}

/**
 * Owner name, mobile and email (FR-002). The mobile number and email are used to sign in, so changing
 * either needs the current password (with the usual lockout); a name change alone does not.
 */
class UpdateOwnerProfileUseCase @Inject constructor(
    private val profiles: BusinessProfileRepository,
    private val accounts: AccountRepository,
    private val repository: ProfileRepository,
    private val confirmed: PasswordConfirmedChange,
    private val clock: Clock,
) {
    suspend operator fun invoke(form: OwnerProfileForm): DomainResult<Unit> = catchingResult {
        val profile = profiles.observe().first() ?: return DomainResult.Err(DomainError.NoAccount)
        val credentials = accounts.credentials() ?: return DomainResult.Err(DomainError.NoAccount)
        val errors = ValidationErrors().apply {
            add(Field.OWNER_NAME, FieldValidators.requiredTextCode(form.ownerName, FieldValidators.NAME_MIN, FieldValidators.NAME_MAX))
            add(Field.MOBILE, FieldValidators.mobileCode(form.mobile))
            add(Field.EMAIL, FieldValidators.optionalEmailCode(form.email))
        }
        errors.asFailure()?.let { return it }
        val mobile = checkNotNull(FieldValidators.normalizeMobile(form.mobile))
        val email = FieldValidators.normalizeEmail(form.email)
        val renamed = profile.copy(ownerName = form.ownerName.trim())
        if (mobile == credentials.mobile && email == credentials.email) {
            repository.updateShop(renamed, clock.now())
            DomainResult.Ok(Unit)
        } else {
            confirmed.run(form.currentPassword, validate = { _, _ -> }) { at ->
                repository.updateContact(mobile, email, at)
                repository.updateShop(renamed, at)
            }
        }
    }
}
