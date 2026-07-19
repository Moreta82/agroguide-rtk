package com.agroguide.rtk.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatteryVendorsTest {
    @Test fun `maps aggressive vendors case-insensitively`() {
        assertEquals(BatteryVendors.VendorAdvice.MIUI, BatteryVendors.adviceFor("Xiaomi"))
        assertEquals(BatteryVendors.VendorAdvice.MIUI, BatteryVendors.adviceFor("redmi"))
        assertEquals(BatteryVendors.VendorAdvice.MIUI, BatteryVendors.adviceFor("POCO"))
        assertEquals(BatteryVendors.VendorAdvice.EMUI, BatteryVendors.adviceFor("HUAWEI"))
        assertEquals(BatteryVendors.VendorAdvice.EMUI, BatteryVendors.adviceFor("Honor"))
        assertEquals(BatteryVendors.VendorAdvice.ONE_UI, BatteryVendors.adviceFor("samsung"))
    }

    @Test fun `unknown vendors return null`() {
        assertNull(BatteryVendors.adviceFor("Google"))
        assertNull(BatteryVendors.adviceFor(""))
    }
}
