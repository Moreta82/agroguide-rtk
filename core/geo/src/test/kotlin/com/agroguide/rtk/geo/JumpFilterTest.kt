package com.agroguide.rtk.geo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JumpFilterTest {
    private val f = JumpFilter()

    @Test fun `first fix is always accepted`() {
        assertTrue(f.accept(EnuPoint(0.0, 0.0), speedMps = 0.0, monotonicMs = 0))
    }

    @Test fun `plausible movement at speed is accepted`() {
        f.accept(EnuPoint(0.0, 0.0), 5.0, 0)
        assertTrue(f.accept(EnuPoint(0.0, 5.2), 5.0, 1000))  // 5.2 m in 1 s at 5 m/s
    }

    @Test fun `teleport is rejected but filter recovers after persistence`() {
        f.accept(EnuPoint(0.0, 0.0), 2.0, 0)
        assertFalse(f.accept(EnuPoint(0.0, 500.0), 2.0, 1000))   // 500 m jump: reject (anchor, 1)
        assertFalse(f.accept(EnuPoint(0.0, 501.0), 2.0, 2000))   // consistent: reject (2)
        assertFalse(f.accept(EnuPoint(0.0, 502.0), 2.0, 3000))   // consistent: reject (3)
        assertTrue(f.accept(EnuPoint(0.0, 503.0), 2.0, 4000))    // 4th consistent -> relocate
    }

    @Test fun `stationary jitter within margin passes`() {
        f.accept(EnuPoint(0.0, 0.0), 0.0, 0)
        assertTrue(f.accept(EnuPoint(0.3, -0.2), 0.0, 1000))
    }

    @Test fun `long gap widens the gate`() {
        f.accept(EnuPoint(0.0, 0.0), 5.0, 0)
        // 30 s without fixes at 5 m/s -> 150 m is plausible
        assertTrue(f.accept(EnuPoint(0.0, 150.0), 5.0, 30_000))
    }
}
