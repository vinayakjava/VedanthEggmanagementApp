package com.eggbusinessmanager.domain.model.account

/**
 * Indian states and union territories for the shop address (FR-002). Constant names are stored in
 * `business_profile.state_code` and must never be renamed; display names live in :app resources.
 */
enum class IndianState(val isUnionTerritory: Boolean = false) {
    ANDHRA_PRADESH,
    ARUNACHAL_PRADESH,
    ASSAM,
    BIHAR,
    CHHATTISGARH,
    GOA,
    GUJARAT,
    HARYANA,
    HIMACHAL_PRADESH,
    JHARKHAND,
    KARNATAKA,
    KERALA,
    MADHYA_PRADESH,
    MAHARASHTRA,
    MANIPUR,
    MEGHALAYA,
    MIZORAM,
    NAGALAND,
    ODISHA,
    PUNJAB,
    RAJASTHAN,
    SIKKIM,
    TAMIL_NADU,
    TELANGANA,
    TRIPURA,
    UTTAR_PRADESH,
    UTTARAKHAND,
    WEST_BENGAL,
    ANDAMAN_AND_NICOBAR_ISLANDS(isUnionTerritory = true),
    CHANDIGARH(isUnionTerritory = true),
    DADRA_NAGAR_HAVELI_AND_DAMAN_DIU(isUnionTerritory = true),
    DELHI(isUnionTerritory = true),
    JAMMU_AND_KASHMIR(isUnionTerritory = true),
    LADAKH(isUnionTerritory = true),
    LAKSHADWEEP(isUnionTerritory = true),
    PUDUCHERRY(isUnionTerritory = true),
    ;

    companion object {
        fun fromStored(code: String?): IndianState? = entries.firstOrNull { it.name == code }
    }
}
