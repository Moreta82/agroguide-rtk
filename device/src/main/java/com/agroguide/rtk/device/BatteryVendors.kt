package com.agroguide.rtk.device

/**
 * Vendors whose battery managers are known to kill foreground services.
 * Pure mapping (Build.MANUFACTURER -> advice) so it stays unit-testable.
 */
object BatteryVendors {
    enum class VendorAdvice { MIUI, EMUI, ONE_UI }

    fun adviceFor(manufacturer: String): VendorAdvice? = when (manufacturer.lowercase()) {
        "xiaomi", "redmi", "poco" -> VendorAdvice.MIUI
        "huawei", "honor" -> VendorAdvice.EMUI
        "samsung" -> VendorAdvice.ONE_UI
        else -> null
    }
}
