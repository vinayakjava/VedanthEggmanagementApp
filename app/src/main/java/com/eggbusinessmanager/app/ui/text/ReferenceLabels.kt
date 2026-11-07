package com.eggbusinessmanager.app.ui.text

import androidx.annotation.StringRes
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.domain.model.account.IndianState
import com.eggbusinessmanager.domain.model.account.SecurityQuestion

/** Localized text for stable domain keys. Exhaustive `when` so a new key fails to compile until labelled. */
object ReferenceLabels {

    @StringRes
    fun question(question: SecurityQuestion): Int = when (question) {
        SecurityQuestion.FIRST_SCHOOL -> R.string.sq_first_school
        SecurityQuestion.BIRTH_TOWN -> R.string.sq_birth_town
        SecurityQuestion.MOTHER_NATIVE_VILLAGE -> R.string.sq_mother_native_village
        SecurityQuestion.FIRST_TEACHER -> R.string.sq_first_teacher
        SecurityQuestion.CHILDHOOD_FRIEND -> R.string.sq_childhood_friend
        SecurityQuestion.FAVOURITE_DISH -> R.string.sq_favourite_dish
        SecurityQuestion.FIRST_VEHICLE -> R.string.sq_first_vehicle
        SecurityQuestion.GRANDFATHER_NAME -> R.string.sq_grandfather_name
        SecurityQuestion.FIRST_JOB_PLACE -> R.string.sq_first_job_place
        SecurityQuestion.CHILDHOOD_NICKNAME -> R.string.sq_childhood_nickname
        SecurityQuestion.FAVOURITE_FILM -> R.string.sq_favourite_film
        SecurityQuestion.FIRST_SHOP_LOCATION -> R.string.sq_first_shop_location
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per state/UT.
    @StringRes
    fun state(state: IndianState): Int = when (state) {
        IndianState.ANDHRA_PRADESH -> R.string.state_andhra_pradesh
        IndianState.ARUNACHAL_PRADESH -> R.string.state_arunachal_pradesh
        IndianState.ASSAM -> R.string.state_assam
        IndianState.BIHAR -> R.string.state_bihar
        IndianState.CHHATTISGARH -> R.string.state_chhattisgarh
        IndianState.GOA -> R.string.state_goa
        IndianState.GUJARAT -> R.string.state_gujarat
        IndianState.HARYANA -> R.string.state_haryana
        IndianState.HIMACHAL_PRADESH -> R.string.state_himachal_pradesh
        IndianState.JHARKHAND -> R.string.state_jharkhand
        IndianState.KARNATAKA -> R.string.state_karnataka
        IndianState.KERALA -> R.string.state_kerala
        IndianState.MADHYA_PRADESH -> R.string.state_madhya_pradesh
        IndianState.MAHARASHTRA -> R.string.state_maharashtra
        IndianState.MANIPUR -> R.string.state_manipur
        IndianState.MEGHALAYA -> R.string.state_meghalaya
        IndianState.MIZORAM -> R.string.state_mizoram
        IndianState.NAGALAND -> R.string.state_nagaland
        IndianState.ODISHA -> R.string.state_odisha
        IndianState.PUNJAB -> R.string.state_punjab
        IndianState.RAJASTHAN -> R.string.state_rajasthan
        IndianState.SIKKIM -> R.string.state_sikkim
        IndianState.TAMIL_NADU -> R.string.state_tamil_nadu
        IndianState.TELANGANA -> R.string.state_telangana
        IndianState.TRIPURA -> R.string.state_tripura
        IndianState.UTTAR_PRADESH -> R.string.state_uttar_pradesh
        IndianState.UTTARAKHAND -> R.string.state_uttarakhand
        IndianState.WEST_BENGAL -> R.string.state_west_bengal
        IndianState.ANDAMAN_AND_NICOBAR_ISLANDS -> R.string.state_andaman_and_nicobar_islands
        IndianState.CHANDIGARH -> R.string.state_chandigarh
        IndianState.DADRA_NAGAR_HAVELI_AND_DAMAN_DIU -> R.string.state_dadra_nagar_haveli_and_daman_diu
        IndianState.DELHI -> R.string.state_delhi
        IndianState.JAMMU_AND_KASHMIR -> R.string.state_jammu_and_kashmir
        IndianState.LADAKH -> R.string.state_ladakh
        IndianState.LAKSHADWEEP -> R.string.state_lakshadweep
        IndianState.PUDUCHERRY -> R.string.state_puducherry
    }
}
