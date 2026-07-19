package com.agroguide.rtk.ntrip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceTableTest {
    private val body = """
        CAS;caster.example.com;2101;Example;Op;0;ESP;40.0;-3.0;0.0.0.0;0;http://example.com
        NET;IGN;IGN;B;N;http://ign.es;none;mail@ign.es;none
        STR;MADR;Madrid;RTCM 3.2;1005(30),1074(1);2;GPS+GLO;IGN;ESP;40.40;-3.70;1;1;sNTRIP;none;B;N;9600;
        STR;LEON;Leon;RTCM 3.2;1005(30);2;GPS;IGN;ESP;42.60;-5.57;0;0;sNTRIP;none;N;N;9600;
        ENDSOURCETABLE
    """.trimIndent()

    @Test fun `parses STR records only`() {
        val t = SourceTable.parse(body)
        assertEquals(2, t.size)
        val m = t[0]
        assertEquals("MADR", m.mountPoint)
        assertEquals("Madrid", m.identifier)
        assertEquals("RTCM 3.2", m.format)
        assertEquals(40.40, m.latDeg!!, 1e-9)
        assertEquals(-3.70, m.lonDeg!!, 1e-9)
        assertTrue(m.requiresGga)          // nmea flag = 1
        assertTrue(m.requiresAuth)         // auth = B (basic)
        val l = t[1]
        assertEquals("LEON", l.mountPoint)
        assertEquals(false, l.requiresGga)
        assertEquals(false, l.requiresAuth)
    }

    @Test fun `tolerates malformed lines`() {
        assertEquals(0, SourceTable.parse("STR;\ngarbage\n").size + SourceTable.parse("").size)
    }
}
