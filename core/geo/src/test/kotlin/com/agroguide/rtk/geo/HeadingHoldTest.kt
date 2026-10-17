package com.agroguide.rtk.geo

import kotlin.test.Test
import kotlin.test.assertEquals

class HeadingHoldTest {
    @Test fun `uses course while moving`() {
        val h = HeadingHold()
        assertEquals(90.0, h.update(courseDeg = 90.0, speedMps = 2.0), 1e-9)
    }

    @Test fun `keeps last heading when the receiver reports no course at a stop`() {
        // u-blox leaves COG empty when stationary; this used to become 0 = north
        // and spun the heading-up view every time the tractor stopped.
        val h = HeadingHold()
        h.update(90.0, 2.0)
        assertEquals(90.0, h.update(null, 0.0), 1e-9)
        assertEquals(90.0, h.update(null, 0.0), 1e-9)
    }

    @Test fun `ignores noisy course at crawl speed`() {
        val h = HeadingHold()
        h.update(180.0, 2.0)
        assertEquals(180.0, h.update(37.0, 0.1), 1e-9)   // jitter while nearly stopped
    }

    @Test fun `follows the new course once moving again`() {
        val h = HeadingHold()
        h.update(180.0, 2.0)
        h.update(null, 0.0)
        assertEquals(0.0, h.update(0.0, 1.5), 1e-9)
    }

    @Test fun `zero before any valid course`() {
        assertEquals(0.0, HeadingHold().update(null, 0.0), 1e-9)
    }
}
