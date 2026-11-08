package com.eggbusinessmanager.domain.settings

import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.usecase.auth.AuthHarness
import com.eggbusinessmanager.domain.usecase.auth.PasswordConfirmedChange
import com.eggbusinessmanager.testing.FakeBusinessProfileRepository
import com.eggbusinessmanager.testing.FakeProfileRepository
import com.eggbusinessmanager.testing.SignupForms
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileAndPreferencesTest {

    private val h = AuthHarness()
    private suspend fun signedUp() = h.signUp(SignupForms.valid).also { assertThat(it).isEqualTo(DomainResult.Ok(Unit)) }
    private val profiles get() = FakeBusinessProfileRepository(h.accounts.profile)
    private fun owner(profiles: FakeBusinessProfileRepository) =
        UpdateOwnerProfileUseCase(
            profiles,
            h.accounts,
            FakeProfileRepository(profiles, h.accounts),
            PasswordConfirmedChange(h.accounts, h.gate, h.hasher),
            h.clock,
        )

    private fun fields(result: DomainResult<Unit>) = ((result as DomainResult.Err).error as DomainError.Validation).fields

    @Test
    fun `shop details are validated like signup and saved trimmed`() = runTest {
        signedUp()
        val p = profiles
        val update = UpdateShopProfileUseCase(p, FakeProfileRepository(p, h.accounts), h.clock)
        val form = GetShopProfileFormUseCase(p)()
        assertThat(form.shopName).isEqualTo(SignupForms.valid.shopName)

        assertThat(fields(update(form.copy(shopName = " ", pinCode = "12", state = null))))
            .containsExactly(
                Field.SHOP_NAME, ValidationCode.REQUIRED,
                Field.PIN_CODE, ValidationCode.INVALID_FORMAT,
                Field.STATE, ValidationCode.REQUIRED,
            )
        val edited = form.copy(
            shopName = "  Patil Eggs & Sons ", address = " ", city = "Pune", state = IndianState.MAHARASHTRA, pinCode = "411001",
        )
        assertThat(update(edited))
            .isEqualTo(DomainResult.Ok(Unit))
        val saved = p.observe().first()!!
        assertThat(saved.shopName).isEqualTo("Patil Eggs & Sons")
        assertThat(saved.address).isNull()
        assertThat(saved.city).isEqualTo("Pune")
        assertThat(saved.ownerName).isEqualTo(SignupForms.valid.ownerName) // untouched
    }

    @Test
    fun `renaming the owner needs no password`() = runTest {
        signedUp()
        val p = profiles
        val form = GetOwnerProfileFormUseCase(p, h.accounts)()
        assertThat(owner(p)(form.copy(ownerName = "Sunil Patil"))).isEqualTo(DomainResult.Ok(Unit))
        assertThat(p.observe().first()!!.ownerName).isEqualTo("Sunil Patil")
    }

    @Test
    fun `changing the sign-in mobile or email needs the current password`() = runTest {
        signedUp()
        val p = profiles
        val form = GetOwnerProfileFormUseCase(p, h.accounts)()

        assertThat(fields(owner(p)(form.copy(mobile = "9123456789"))))
            .containsExactly(Field.CURRENT_PASSWORD, ValidationCode.REQUIRED)
        assertThat(owner(p)(form.copy(mobile = "9123456789", currentPassword = "wrong-pass1")))
            .isEqualTo(DomainResult.Err(DomainError.IncorrectPassword))
        assertThat(h.accounts.current!!.mobile).isEqualTo(form.mobile)

        assertThat(owner(p)(form.copy(mobile = "+91 91234 56789", email = " Ramesh@Example.com ", currentPassword = SignupForms.PASSWORD)))
            .isEqualTo(DomainResult.Ok(Unit))
        assertThat(h.accounts.current!!.mobile).isEqualTo("9123456789")
        assertThat(h.accounts.current!!.email).isEqualTo("ramesh@example.com")
        // The new mobile number now signs in.
        assertThat(h.signIn("9123456789", SignupForms.PASSWORD)).isEqualTo(DomainResult.Ok(Unit))
    }

    @Test
    fun `owner fields are validated`() = runTest {
        signedUp()
        val p = profiles
        val form = GetOwnerProfileFormUseCase(p, h.accounts)()
        assertThat(fields(owner(p)(form.copy(ownerName = "R", mobile = "12345", email = "not-an-email"))))
            .containsExactly(
                Field.OWNER_NAME, ValidationCode.TOO_SHORT,
                Field.MOBILE, ValidationCode.INVALID_FORMAT,
                Field.EMAIL, ValidationCode.INVALID_FORMAT,
            )
    }

    @Test
    fun `preferences default to rupee sign, Indian grouping and day-month-name dates`() = runTest {
        val stored = MutableStateFlow(AppPreferences())
        var lock = 0
        val repo = object : AppPreferencesRepository {
            override fun observePreferences() = stored
            override suspend fun setPreferences(preferences: AppPreferences) {
                stored.value = preferences
            }
            override suspend fun setAutoLockMinutes(minutes: Int) {
                lock = minutes
            }
        }
        assertThat(ObservePreferencesUseCase(repo)().first())
            .isEqualTo(AppPreferences(CurrencySymbol.RUPEE_SIGN, DigitGrouping.INDIAN, DateStyle.DAY_MONTH_NAME, null))
        UpdatePreferencesUseCase(repo)(AppPreferences(CurrencySymbol.RS, DigitGrouping.INTERNATIONAL, DateStyle.ISO, 3))
        assertThat(stored.value.defaultUnitId).isEqualTo(3)
        SetAutoLockMinutesUseCase(repo)(15)
        assertThat(lock).isEqualTo(15)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { SetAutoLockMinutesUseCase(repo)(7) } }
    }
}
