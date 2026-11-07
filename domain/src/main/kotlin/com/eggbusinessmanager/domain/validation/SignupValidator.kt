package com.eggbusinessmanager.domain.validation

import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.ValidationErrors
import com.eggbusinessmanager.domain.model.account.BusinessProfile
import com.eggbusinessmanager.domain.model.account.SignupForm
import com.eggbusinessmanager.domain.model.account.ValidSignup

/**
 * Signup validation per screen (account, shop, security) and for the whole form (FR-002, FR-012).
 * The screens call the step functions for inline messages; SignUpUseCase calls [signup].
 */
object SignupValidator {

    /** Signup step 1: owner, mobile, email, password. */
    fun accountStep(form: SignupForm): ValidationErrors = ValidationErrors().apply {
        add(Field.OWNER_NAME, FieldValidators.requiredTextCode(form.ownerName, FieldValidators.NAME_MIN, FieldValidators.NAME_MAX))
        add(Field.MOBILE, FieldValidators.mobileCode(form.mobile))
        add(Field.EMAIL, FieldValidators.optionalEmailCode(form.email))
        AccountValidator.newPassword(this, form.password, form.confirmPassword, FieldValidators.normalizeMobile(form.mobile))
    }

    /** Signup step 2: shop details. */
    fun shopStep(form: SignupForm): ValidationErrors = ValidationErrors().apply {
        add(Field.SHOP_NAME, FieldValidators.requiredTextCode(form.shopName, FieldValidators.NAME_MIN, FieldValidators.NAME_MAX))
        add(Field.ADDRESS, FieldValidators.optionalTextCode(form.address, FieldValidators.ADDRESS_MAX))
        add(Field.CITY, FieldValidators.requiredTextCode(form.city, FieldValidators.CITY_MIN, FieldValidators.CITY_MAX))
        if (form.state == null) add(Field.STATE, ValidationCode.REQUIRED)
        add(Field.PIN_CODE, FieldValidators.pinCodeCode(form.pinCode))
    }

    /** Signup step 3: recovery PIN and security questions. */
    fun securityStep(form: SignupForm): ValidationErrors = ValidationErrors().apply {
        AccountValidator.newRecoveryPin(this, form.recoveryPin, form.confirmRecoveryPin)
        AccountValidator.securityAnswers(this, form.securityAnswers, form.password)
    }

    /** Validates the whole signup and returns it normalized. */
    fun signup(form: SignupForm): DomainResult<ValidSignup> {
        val errors = ValidationErrors()
        listOf(accountStep(form), shopStep(form), securityStep(form)).forEach { step ->
            step.toMap().forEach { (field, code) -> errors.add(field, code) }
        }
        return errors.result {
            ValidSignup(
                mobile = checkNotNull(FieldValidators.normalizeMobile(form.mobile)),
                email = FieldValidators.normalizeEmail(form.email),
                password = form.password,
                recoveryPin = form.recoveryPin,
                profile = BusinessProfile(
                    shopName = form.shopName.trim(),
                    ownerName = form.ownerName.trim(),
                    address = form.address.trim().ifEmpty { null },
                    city = form.city.trim(),
                    state = checkNotNull(form.state),
                    pinCode = form.pinCode.trim(),
                ),
                securityAnswers = form.securityAnswers.map {
                    checkNotNull(it.question) to SecurityAnswerNormalizer.normalize(it.answer)
                },
            )
        }
    }
}
