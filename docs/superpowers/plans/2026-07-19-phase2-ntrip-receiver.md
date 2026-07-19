# AgroGuide RTK — Phase 2 Implementation Plan (NTRIP client + receiver utilities)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** RTK corrections end-to-end: NTRIP v1/v2 client (sourcetable browser, credential profiles, GGA upload, auto-reconnect, RTCM traffic indicator) feeding the receiver over the existing BT socket, plus one-tap receiver optimization via UBX-CFG-VALSET with ACK confirmation.

**Architecture:** New pure-JVM module `:core:ntrip` holds all NTRIP protocol logic (request building, response/sourcetable parsing, chunked decoding, socket client) — testable against a localhost fake caster. UBX encoding and ACK scanning go into `:core:nmea` (`ubx` package). `:service` wires an `NtripSession` into `GuidanceService` (BT mode only): RTCM → `BtSppPositionSource.write()`, GGA lines tapped from the shared source flow for periodic upload. Profiles persist in DataStore as JSON (documented deviation: Room arrives in Phase 5 for jobs/parcels; profiles are a flat list with no relational needs). UI grows three tabs: Guiado / NTRIP / Receptor.

**Tech Stack additions:** kotlinx-serialization-json 1.8.0 (+ plugin), androidx.datastore-preferences 1.1.4. Everything else unchanged.

**Environment:** same as Phase 1 (`$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`, run `.\gradlew.bat` from repo root; run `clean` as a separate invocation if ever needed).

---

### Task 1: `:core:nmea` — UBX frame + CFG-VALSET presets (TDD)

**Files:**
- Create: `core/nmea/src/main/kotlin/com/agroguide/rtk/nmea/ubx/Ubx.kt`
- Test: `core/nmea/src/test/kotlin/com/agroguide/rtk/nmea/ubx/UbxTest.kt`

- [ ] **Step 1: Failing tests** (`UbxTest.kt`)

```kotlin
package com.agroguide.rtk.nmea.ubx

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class UbxTest {
    @Test fun `frame wraps payload with sync and fletcher checksum`() {
        // CFG-VALSET (06 8A), RAM layer, CFG-RATE-MEAS(0x30210001)=200ms.
        // Hand-computed: B5 62 06 8A 0A 00 00 01 00 00 01 00 21 30 C8 00 B5 81
        val payload = byteArrayOf(
            0x00, 0x01, 0x00, 0x00,                          // version, layers=RAM, reserved
            0x01, 0x00, 0x21, 0x30,                          // key 0x30210001 LE
            0xC8.toByte(), 0x00,                             // value 200 (U2 LE)
        )
        val frame = Ubx.frame(0x06, 0x8A, payload)
        assertContentEquals(
            byteArrayOf(
                0xB5.toByte(), 0x62, 0x06, 0x8A.toByte(), 0x0A, 0x00,
                0x00, 0x01, 0x00, 0x00, 0x01, 0x00, 0x21, 0x30, 0xC8.toByte(), 0x00,
                0xB5.toByte(), 0x81.toByte(),
            ),
            frame,
        )
    }

    @Test fun `valset optimize sets 5Hz and disables GSV on both UARTs, RAM plus flash`() {
        val frame = UbxValSet.optimize(rateHz = 5)
        // header
        assertEquals(0xB5.toByte(), frame[0]); assertEquals(0x62.toByte(), frame[1])
        assertEquals(0x06.toByte(), frame[2]); assertEquals(0x8A.toByte(), frame[3])
        val payload = frame.copyOfRange(6, frame.size - 2)
        assertEquals(0x00, payload[0].toInt())          // version
        assertEquals(0x05, payload[1].toInt())          // layers RAM|FLASH
        // cfgData: RATE_MEAS(U2)=200, GSV_UART1(U1)=0, GSV_UART2(U1)=0 -> 4+2 + 4+1 + 4+1 = 16 bytes
        assertEquals(4 + 16, payload.size)
        val body = payload.copyOfRange(4, payload.size)
        assertContentEquals(byteArrayOf(0x01, 0x00, 0x21, 0x30, 0xC8.toByte(), 0x00), body.copyOfRange(0, 6))
        assertContentEquals(byteArrayOf(0xC5.toByte(), 0x00, 0x91.toByte(), 0x20, 0x00), body.copyOfRange(6, 11))
        assertContentEquals(byteArrayOf(0xC6.toByte(), 0x00, 0x91.toByte(), 0x20, 0x00), body.copyOfRange(11, 16))
    }

    @Test fun `valset restoreDefaults sets 1Hz and reenables GSV`() {
        val payload = UbxValSet.restoreDefaults().let { it.copyOfRange(6, it.size - 2) }
        assertEquals(0x05, payload[1].toInt())
        val body = payload.copyOfRange(4, payload.size)
        // RATE_MEAS=1000 (0x03E8)
        assertContentEquals(byteArrayOf(0x01, 0x00, 0x21, 0x30, 0xE8.toByte(), 0x03), body.copyOfRange(0, 6))
        assertEquals(0x01, body[10].toInt())   // GSV_UART1 = 1
        assertEquals(0x01, body[15].toInt())   // GSV_UART2 = 1
    }

    @Test fun `checksum survives high bytes`() {
        val f = Ubx.frame(0x05, 0x01, byteArrayOf(0x06, 0x8A.toByte()))
        // verify Fletcher manually over 05 01 02 00 06 8A
        var a = 0; var b = 0
        for (x in byteArrayOf(0x05, 0x01, 0x02, 0x00, 0x06, 0x8A.toByte())) {
            a = (a + (x.toInt() and 0xFF)) and 0xFF; b = (b + a) and 0xFF
        }
        assertEquals(a.toByte(), f[f.size - 2]); assertEquals(b.toByte(), f[f.size - 1])
    }
}
```

- [ ] **Step 2: Run `:core:nmea:test`, expect compile failure.**

- [ ] **Step 3: Implement** (`Ubx.kt`)

```kotlin
package com.agroguide.rtk.nmea.ubx

/** UBX binary protocol framing (u-blox ZED-F9P). */
object Ubx {
    /** Wrap class/id/payload with sync chars and 8-bit Fletcher checksum. */
    fun frame(cls: Int, id: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = 0xB5.toByte(); out[1] = 0x62
        out[2] = cls.toByte(); out[3] = id.toByte()
        out[4] = (payload.size and 0xFF).toByte()
        out[5] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(out, 6)
        var a = 0; var b = 0
        for (i in 2 until 6 + payload.size) {
            a = (a + (out[i].toInt() and 0xFF)) and 0xFF
            b = (b + a) and 0xFF
        }
        out[6 + payload.size] = a.toByte()
        out[7 + payload.size] = b.toByte()
        return out
    }
}

/**
 * CFG-VALSET builder for the receiver settings AgroGuide manages.
 * Layers: RAM(1) | FLASH(4) so the change survives power cycles.
 */
object UbxValSet {
    private const val LAYER_RAM_FLASH = 0x05
    const val KEY_RATE_MEAS = 0x30210001          // U2, ms per measurement
    const val KEY_MSGOUT_GSV_UART1 = 0x209100C5   // U1, msgs per epoch
    const val KEY_MSGOUT_GSV_UART2 = 0x209100C6   // U1

    /** 5 Hz (or given rate), GSV off on both UARTs; GGA/RMC/VTG stay at default 1. */
    fun optimize(rateHz: Int = 5): ByteArray = valset(
        listOf(
            KeyValue(KEY_RATE_MEAS, 1000 / rateHz, 2),
            KeyValue(KEY_MSGOUT_GSV_UART1, 0, 1),
            KeyValue(KEY_MSGOUT_GSV_UART2, 0, 1),
        )
    )

    /** Back to receiver defaults for the keys we touch: 1 Hz, GSV enabled. */
    fun restoreDefaults(): ByteArray = valset(
        listOf(
            KeyValue(KEY_RATE_MEAS, 1000, 2),
            KeyValue(KEY_MSGOUT_GSV_UART1, 1, 1),
            KeyValue(KEY_MSGOUT_GSV_UART2, 1, 1),
        )
    )

    data class KeyValue(val key: Int, val value: Int, val valueBytes: Int)

    private fun valset(kvs: List<KeyValue>): ByteArray {
        val body = ArrayList<Byte>(4 + kvs.sumOf { 4 + it.valueBytes })
        body += 0x00; body += LAYER_RAM_FLASH.toByte(); body += 0x00; body += 0x00
        for (kv in kvs) {
            for (i in 0 until 4) body += ((kv.key shr (8 * i)) and 0xFF).toByte()
            for (i in 0 until kv.valueBytes) body += ((kv.value shr (8 * i)) and 0xFF).toByte()
        }
        return Ubx.frame(0x06, 0x8A, body.toByteArray())
    }
}
```

- [ ] **Step 4: Run tests → green. Commit** `feat(nmea): UBX framing and CFG-VALSET optimize/restore presets`

---

### Task 2: `:core:nmea` — UBX ACK scanner + BT raw tap (TDD for scanner)

**Files:**
- Create: `core/nmea/src/main/kotlin/com/agroguide/rtk/nmea/ubx/UbxAckScanner.kt`
- Test: `core/nmea/src/test/kotlin/com/agroguide/rtk/nmea/ubx/UbxAckScannerTest.kt`
- Modify: `device/src/main/java/com/agroguide/rtk/device/bt/BtSppPositionSource.kt`
- Modify: `device/src/main/java/com/agroguide/rtk/device/PositionSource.kt`

- [ ] **Step 1: Failing tests**

```kotlin
package com.agroguide.rtk.nmea.ubx

import kotlin.test.Test
import kotlin.test.assertEquals

class UbxAckScannerTest {
    // ACK-ACK for CFG-VALSET: B5 62 05 01 02 00 06 8A 98 C1  (checksum bytes irrelevant to scanner)
    private val ackAck = byteArrayOf(0xB5.toByte(), 0x62, 0x05, 0x01, 0x02, 0x00, 0x06, 0x8A.toByte(), 0x98.toByte(), 0xC1.toByte())
    private val ackNak = byteArrayOf(0xB5.toByte(), 0x62, 0x05, 0x00, 0x02, 0x00, 0x06, 0x8A.toByte(), 0x97.toByte(), 0xBA.toByte())

    @Test fun `detects ACK and NAK in one chunk`() {
        val s = UbxAckScanner()
        assertEquals(listOf(UbxAck(true, 0x06, 0x8A)), s.feed(ackAck))
        assertEquals(listOf(UbxAck(false, 0x06, 0x8A)), s.feed(ackNak))
    }

    @Test fun `detects ACK split across chunks and interleaved with NMEA text`() {
        val s = UbxAckScanner()
        val part1 = "\$GNGGA,x*00\r\n".toByteArray() + ackAck.copyOfRange(0, 4)
        val part2 = ackAck.copyOfRange(4, ackAck.size) + "\$GNRMC".toByteArray()
        assertEquals(emptyList(), s.feed(part1))
        assertEquals(listOf(UbxAck(true, 0x06, 0x8A)), s.feed(part2))
    }
}
```

- [ ] **Step 2: Run, expect failure; implement**

```kotlin
package com.agroguide.rtk.nmea.ubx

data class UbxAck(val ok: Boolean, val ackedCls: Int, val ackedId: Int)

/**
 * Scans a raw byte stream for UBX-ACK-ACK (05 01) / ACK-NAK (05 00) frames,
 * tolerant of NMEA/RTCM bytes around them and of chunk splits. Checksums are
 * not verified — a spoofed ACK on a point-to-point serial link is not a threat.
 */
class UbxAckScanner {
    private val window = ArrayDeque<Byte>()

    fun feed(chunk: ByteArray): List<UbxAck> {
        val out = mutableListOf<UbxAck>()
        for (b in chunk) {
            window.addLast(b)
            if (window.size > 10) window.removeFirst()
            if (window.size == 10) {
                val w = window.toList()
                val isAck = w[0] == 0xB5.toByte() && w[1] == 0x62.toByte() &&
                    w[2] == 0x05.toByte() && (w[3] == 0x01.toByte() || w[3] == 0x00.toByte()) &&
                    w[4] == 0x02.toByte() && w[5] == 0x00.toByte()
                if (isAck) {
                    out += UbxAck(w[3] == 0x01.toByte(), w[6].toInt() and 0xFF, w[7].toInt() and 0xFF)
                    window.clear()
                }
            }
        }
        return out
    }
}
```

- [ ] **Step 3: Surface ACKs from the BT source.** Add to `SourceEvent` (in `PositionSource.kt`):

```kotlin
    /** UBX ACK/NAK observed on the raw stream (receiver-utility feedback). */
    data class UbxAckEvent(val ok: Boolean, val ackedCls: Int, val ackedId: Int) : SourceEvent
```

In `BtSppPositionSource.readUntilStale`, instantiate `val ackScanner = UbxAckScanner()` next to the assembler and inside the read loop after `assembler.feed(...)` add:

```kotlin
                    for (ack in ackScanner.feed(buf.copyOf(n))) {
                        send(SourceEvent.UbxAckEvent(ack.ok, ack.ackedCls, ack.ackedId))
                    }
```

(`PositionPipeline`'s `when` on events: add `is SourceEvent.UbxAckEvent -> return@transform`.)

- [ ] **Step 4: Run `:core:nmea:test :device:testDebugUnitTest` → green. Commit** `feat(nmea,device): UBX ACK detection on the BT byte stream`

---

### Task 3: `:core:ntrip` module — protocol pieces (TDD)

**Files:**
- Create: `core/ntrip/build.gradle.kts` (copy of `core/nmea/build.gradle.kts`)
- Modify: `settings.gradle.kts` → `include(":app", ":core:geo", ":core:nmea", ":core:ntrip", ":device", ":service")`
- Modify: `device/build.gradle.kts` → add `api(project(":core:ntrip"))`
- Create: `core/ntrip/src/main/kotlin/com/agroguide/rtk/ntrip/NtripRequest.kt`
- Create: `core/ntrip/src/main/kotlin/com/agroguide/rtk/ntrip/SourceTable.kt`
- Create: `core/ntrip/src/main/kotlin/com/agroguide/rtk/ntrip/ChunkedDecoder.kt`
- Test: `core/ntrip/src/test/kotlin/com/agroguide/rtk/ntrip/NtripRequestTest.kt`
- Test: `core/ntrip/src/test/kotlin/com/agroguide/rtk/ntrip/SourceTableTest.kt`
- Test: `core/ntrip/src/test/kotlin/com/agroguide/rtk/ntrip/ChunkedDecoderTest.kt`

- [ ] **Step 1: Failing tests**

`NtripRequestTest.kt`:
```kotlin
package com.agroguide.rtk.ntrip

import kotlin.test.Test
import kotlin.test.assertEquals

class NtripRequestTest {
    private val creds = NtripCredentials("caster.example.com", 2101, "user", "pass")

    @Test fun `v1 stream request`() {
        val r = NtripRequest.stream(creds, "MOUNT", NtripVersion.V1)
        assertEquals(
            "GET /MOUNT HTTP/1.0\r\n" +
                "User-Agent: NTRIP AgroGuideRTK/1.0\r\n" +
                "Accept: */*\r\n" +
                "Authorization: Basic dXNlcjpwYXNz\r\n" +
                "\r\n",
            r,
        )
    }

    @Test fun `v2 stream request has Host and Ntrip-Version`() {
        val r = NtripRequest.stream(creds, "MOUNT", NtripVersion.V2)
        assertEquals(
            "GET /MOUNT HTTP/1.1\r\n" +
                "Host: caster.example.com:2101\r\n" +
                "Ntrip-Version: Ntrip/2.0\r\n" +
                "User-Agent: NTRIP AgroGuideRTK/1.0\r\n" +
                "Accept: */*\r\n" +
                "Authorization: Basic dXNlcjpwYXNz\r\n" +
                "\r\n",
            r,
        )
    }

    @Test fun `sourcetable request uses empty mountpoint and no auth when anonymous`() {
        val anon = NtripCredentials("h", 2101, "", "")
        val r = NtripRequest.sourceTable(anon, NtripVersion.V1)
        assertEquals(
            "GET / HTTP/1.0\r\nUser-Agent: NTRIP AgroGuideRTK/1.0\r\nAccept: */*\r\n\r\n",
            r,
        )
    }
}
```

`SourceTableTest.kt`:
```kotlin
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
```

`ChunkedDecoderTest.kt`:
```kotlin
package com.agroguide.rtk.ntrip

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ChunkedDecoderTest {
    @Test fun `decodes chunks across feed boundaries`() {
        val d = ChunkedDecoder()
        val out = mutableListOf<Byte>()
        out += d.feed("4\r\nAB".toByteArray()).toList()
        out += d.feed("CD\r\n3\r\nEF".toByteArray()).toList()
        out += d.feed("G\r\n0\r\n\r\n".toByteArray()).toList()
        assertContentEquals("ABCDEFG".toByteArray().toList(), out)
        assertTrue(d.finished)
    }

    @Test fun `handles chunk size split across feeds`() {
        val d = ChunkedDecoder()
        val out = mutableListOf<Byte>()
        out += d.feed("1".toByteArray()).toList()
        out += d.feed("0\r\n".toByteArray()).toList()          // size 0x10 = 16
        out += d.feed(ByteArray(16) { 0x55 }).toList()
        out += d.feed("\r\n".toByteArray()).toList()
        assertContentEquals(List(16) { 0x55.toByte() }, out)
    }
}
```

- [ ] **Step 2: Run `:core:ntrip:test`, expect compile failure. Implement:**

`NtripRequest.kt`:
```kotlin
package com.agroguide.rtk.ntrip

import java.util.Base64

enum class NtripVersion { V1, V2 }

data class NtripCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

/** Builds the exact request text for stream and sourcetable GETs. */
object NtripRequest {
    private const val AGENT = "NTRIP AgroGuideRTK/1.0"

    fun stream(c: NtripCredentials, mountPoint: String, v: NtripVersion): String =
        build(c, "/$mountPoint", v)

    fun sourceTable(c: NtripCredentials, v: NtripVersion): String = build(c, "/", v)

    private fun build(c: NtripCredentials, path: String, v: NtripVersion): String {
        val sb = StringBuilder()
        when (v) {
            NtripVersion.V1 -> {
                sb.append("GET ").append(path).append(" HTTP/1.0\r\n")
                sb.append("User-Agent: ").append(AGENT).append("\r\n")
                sb.append("Accept: */*\r\n")
            }
            NtripVersion.V2 -> {
                sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
                sb.append("Host: ").append(c.host).append(':').append(c.port).append("\r\n")
                sb.append("Ntrip-Version: Ntrip/2.0\r\n")
                sb.append("User-Agent: ").append(AGENT).append("\r\n")
                sb.append("Accept: */*\r\n")
            }
        }
        if (c.username.isNotEmpty() || c.password.isNotEmpty()) {
            val token = Base64.getEncoder().encodeToString("${c.username}:${c.password}".toByteArray())
            sb.append("Authorization: Basic ").append(token).append("\r\n")
        }
        sb.append("\r\n")
        return sb.toString()
    }
}
```

`SourceTable.kt`:
```kotlin
package com.agroguide.rtk.ntrip

/** One STR record from an NTRIP sourcetable. */
data class MountPoint(
    val mountPoint: String,
    val identifier: String,
    val format: String,
    val navSystem: String,
    val country: String,
    val latDeg: Double?,
    val lonDeg: Double?,
    val requiresGga: Boolean,
    val requiresAuth: Boolean,
)

object SourceTable {
    /** Parses sourcetable text; ignores CAS/NET/malformed lines. */
    fun parse(body: String): List<MountPoint> = body.lineSequence().mapNotNull { line ->
        val f = line.trim().split(';')
        if (f.size < 16 || f[0] != "STR" || f[1].isEmpty()) return@mapNotNull null
        MountPoint(
            mountPoint = f[1], identifier = f[2], format = f[3],
            navSystem = f[6], country = f[8],
            latDeg = f[9].toDoubleOrNull(), lonDeg = f[10].toDoubleOrNull(),
            requiresGga = f[11] == "1",
            requiresAuth = f[15] != "N",
        )
    }.toList()
}
```

`ChunkedDecoder.kt`:
```kotlin
package com.agroguide.rtk.ntrip

/** Incremental HTTP/1.1 chunked-transfer decoder for NTRIP v2 streams. */
class ChunkedDecoder {
    private enum class S { SIZE, DATA, DATA_CRLF, DONE }
    private var state = S.SIZE
    private val sizeBuf = StringBuilder()
    private var remaining = 0
    var finished = false; private set

    fun feed(chunk: ByteArray): ByteArray {
        val out = ArrayList<Byte>(chunk.size)
        for (b in chunk) {
            when (state) {
                S.SIZE -> {
                    val c = b.toInt().toChar()
                    if (c == '\n') {
                        remaining = sizeBuf.toString().trim().toIntOrNull(16) ?: 0
                        sizeBuf.setLength(0)
                        state = if (remaining == 0) S.DONE.also { finished = true } else S.DATA
                    } else if (c != '\r') sizeBuf.append(c)
                }
                S.DATA -> {
                    out += b
                    if (--remaining == 0) state = S.DATA_CRLF
                }
                S.DATA_CRLF -> if (b.toInt().toChar() == '\n') state = S.SIZE
                S.DONE -> {}
            }
        }
        return out.toByteArray()
    }
}
```

- [ ] **Step 3: Run `:core:ntrip:test` → green. Commit** `feat(ntrip): protocol module — requests, sourcetable parser, chunked decoder`

---

### Task 4: `:core:ntrip` — NtripClient with fake-caster tests

**Files:**
- Create: `core/ntrip/src/main/kotlin/com/agroguide/rtk/ntrip/NtripClient.kt`
- Test: `core/ntrip/src/test/kotlin/com/agroguide/rtk/ntrip/NtripClientTest.kt`

- [ ] **Step 1: Failing tests** — a minimal fake caster on `ServerSocket(0)`:

```kotlin
package com.agroguide.rtk.ntrip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NtripClientTest {

    @Test fun `v1 handshake then rtcm bytes flow to sink and gga is uploaded`() = runBlocking {
        val server = ServerSocket(0)
        val rtcm = byteArrayOf(0xD3.toByte(), 0x00, 0x03, 1, 2, 3, 9, 9, 9)
        val serverJob = async(Dispatchers.IO) {
            val s = server.accept()
            val reader = s.getInputStream().bufferedReader()
            val request = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            s.getOutputStream().write("ICY 200 OK\r\n".toByteArray())
            s.getOutputStream().write(rtcm)
            s.getOutputStream().flush()
            // wait for one uploaded GGA line
            val gga = reader.readLine()
            s.close(); server.close()
            request to gga
        }
        val received = mutableListOf<Byte>()
        val client = NtripClient(
            NtripCredentials("127.0.0.1", server.localPort, "u", "p"),
            mountPoint = "TEST", version = NtripVersion.V1,
            ggaIntervalMs = 50,
        )
        val session = launch {
            client.run(
                rtcmSink = { received += it.toList() },
                ggaProvider = { "\$GNGGA,demo*00" },
            )
        }
        val (request, gga) = withTimeout(5000) { serverJob.await() }
        session.cancel()
        assertTrue(request.first().startsWith("GET /TEST HTTP/1.0"))
        assertEquals("\$GNGGA,demo*00", gga)
        withTimeout(1000) { while (received.size < rtcm.size) kotlinx.coroutines.delay(10) }
        assertContentEquals(rtcm.toList(), received)
        assertTrue(client.stats.value.bytesReceived >= rtcm.size.toLong())
    }

    @Test fun `sourcetable fetch parses mountpoints`() = runBlocking {
        val server = ServerSocket(0)
        val body = "STR;AAA;Id;RTCM 3.2;;2;GPS;N;ESP;40.0;-3.0;1;1;g;none;B;N;9600;\r\nENDSOURCETABLE\r\n"
        val serverJob = launch(Dispatchers.IO) {
            val s = server.accept()
            s.getInputStream().bufferedReader().let { r ->
                generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            }
            s.getOutputStream().write(
                ("SOURCETABLE 200 OK\r\nContent-Length: ${body.length}\r\n\r\n" + body).toByteArray()
            )
            s.getOutputStream().flush()
            s.close(); server.close()
        }
        val list = withTimeout(5000) {
            NtripClient.fetchSourceTable(NtripCredentials("127.0.0.1", server.localPort, "", ""), NtripVersion.V1)
        }
        serverJob.join()
        assertEquals(1, list.size)
        assertEquals("AAA", list[0].mountPoint)
    }
}
```

- [ ] **Step 2: Run, expect failure; implement `NtripClient.kt`:**

```kotlin
package com.agroguide.rtk.ntrip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

data class NtripStats(
    val connected: Boolean = false,
    val bytesReceived: Long = 0,
    val rtcmFrames: Long = 0,
    val lastDataMonotonicMs: Long = 0,
    val lastError: String? = null,
)

/**
 * One NTRIP session: connect, stream RTCM into [rtcmSink], periodically upload
 * GGA from [ggaProvider] (VRS casters need it). run() suspends until cancelled;
 * on socket failure it returns — the caller (NtripSession in :service) owns the
 * reconnect policy. Traffic stats are exposed for the UI indicator.
 */
class NtripClient(
    private val creds: NtripCredentials,
    private val mountPoint: String,
    private val version: NtripVersion,
    private val ggaIntervalMs: Long = 10_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _stats = MutableStateFlow(NtripStats())
    val stats: StateFlow<NtripStats> = _stats.asStateFlow()

    suspend fun run(rtcmSink: (ByteArray) -> Unit, ggaProvider: () -> String?) {
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket().also { it.connect(InetSocketAddress(creds.host, creds.port), 10_000) }
                val out = socket.getOutputStream()
                out.write(NtripRequest.stream(creds, mountPoint, version).toByteArray())
                out.flush()
                val input = socket.getInputStream()
                val head = readHeader(input)
                if (!head.ok) {
                    _stats.value = NtripStats(lastError = head.statusLine)
                    return@withContext
                }
                _stats.value = NtripStats(connected = true)
                val chunked = head.chunked
                val decoder = ChunkedDecoder()
                coroutineScope {
                    val uploader = launch {
                        while (isActive) {
                            delay(ggaIntervalMs)
                            val gga = ggaProvider() ?: continue
                            try { out.write((gga + "\r\n").toByteArray()); out.flush() }
                            catch (_: IOException) { return@launch }
                        }
                    }
                    val buf = ByteArray(4096)
                    try {
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            val data = if (chunked) decoder.feed(buf.copyOf(n)) else buf.copyOf(n)
                            if (data.isNotEmpty()) {
                                rtcmSink(data)
                                _stats.value = _stats.value.copy(
                                    bytesReceived = _stats.value.bytesReceived + data.size,
                                    rtcmFrames = _stats.value.rtcmFrames + data.count { it == 0xD3.toByte() },
                                    lastDataMonotonicMs = nowMs(),
                                )
                            }
                        }
                    } catch (_: IOException) { /* dropped */ }
                    uploader.cancel()
                }
            } catch (e: IOException) {
                _stats.value = _stats.value.copy(connected = false, lastError = e.message)
            } finally {
                _stats.value = _stats.value.copy(connected = false)
                try { socket?.close() } catch (_: IOException) {}
            }
        }
    }

    private class Header(val ok: Boolean, val statusLine: String, val chunked: Boolean)

    companion object {
        /** Reads the response head byte-by-byte so no stream data is swallowed. */
        private fun readHeader(input: InputStream): Header {
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                sb.append(b.toChar())
                if (sb.length == 12 && sb.startsWith("ICY 200 OK\r\n")) {
                    return Header(true, "ICY 200 OK", chunked = false)   // v1: no further headers
                }
                if (sb.length > 16_384) break
            }
            val text = sb.toString()
            val status = text.lineSequence().firstOrNull() ?: ""
            val ok = status.contains(" 200 ")
            val chunked = text.lowercase().contains("transfer-encoding: chunked")
            return Header(ok, status, chunked)
        }

        /** One-shot sourcetable fetch (empty mountpoint GET). */
        suspend fun fetchSourceTable(creds: NtripCredentials, version: NtripVersion): List<MountPoint> =
            withContext(Dispatchers.IO) {
                Socket().use { s ->
                    s.connect(InetSocketAddress(creds.host, creds.port), 10_000)
                    s.soTimeout = 10_000
                    s.getOutputStream().write(NtripRequest.sourceTable(creds, version).toByteArray())
                    s.getOutputStream().flush()
                    val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                    val bodyStart = text.indexOf("\r\n\r\n")
                    SourceTable.parse(if (bodyStart >= 0) text.substring(bodyStart + 4) else text)
                }
            }
    }
}
```

Note for the executor: the v1 fake caster keeps the socket open after sending RTCM, so `readBytes()` is only used for the sourcetable path (server closes). If `fetchSourceTable` hangs in the test, the `soTimeout` turns it into a SocketTimeoutException — treat as failure and fix the fake caster to close.

- [ ] **Step 3: Run `:core:ntrip:test` → green. Commit** `feat(ntrip): NTRIP v1/v2 client with GGA upload, chunked streams, traffic stats`

---

### Task 5: Profiles persistence (DataStore + kotlinx-serialization)

**Files:**
- Modify: `gradle/libs.versions.toml` — add:
  ```toml
  # [versions]
  serialization = "1.8.0"
  datastore = "1.1.4"
  # [libraries]
  kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
  androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
  # [plugins]
  kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
  ```
- Modify: root `build.gradle.kts` — `alias(libs.plugins.kotlin.serialization) apply false`
- Modify: `app/build.gradle.kts` — apply serialization plugin; add both libraries.
- Create: `app/src/main/java/com/agroguide/rtk/data/NtripProfiles.kt`

- [ ] **Step 1: Model + store** (no unit test — thin DataStore wiring over kotlinx-serialization; exercised manually and in later phases)

```kotlin
package com.agroguide.rtk.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NtripProfile(
    val id: String,                  // UUID string
    val name: String,
    val host: String,
    val port: Int = 2101,
    val mountPoint: String = "",
    val username: String = "",
    val password: String = "",
    val ntripV2: Boolean = false,
    val uploadGga: Boolean = true,
    val ggaIntervalS: Int = 10,
)

@Serializable
data class NtripSettings(
    val profiles: List<NtripProfile> = emptyList(),
    val activeProfileId: String? = null,
)

private val Context.dataStore by preferencesDataStore("settings")
private val KEY_NTRIP = stringPreferencesKey("ntrip_settings")
private val json = Json { ignoreUnknownKeys = true }

/** Profiles live in DataStore as one JSON blob — flat list, no queries needed. */
class NtripProfileStore(private val context: Context) {
    val settings: Flow<NtripSettings> = context.dataStore.data.map { prefs ->
        prefs[KEY_NTRIP]?.let { runCatching { json.decodeFromString<NtripSettings>(it) }.getOrNull() }
            ?: NtripSettings()
    }

    suspend fun update(transform: (NtripSettings) -> NtripSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_NTRIP]?.let {
                runCatching { json.decodeFromString<NtripSettings>(it) }.getOrNull()
            } ?: NtripSettings()
            prefs[KEY_NTRIP] = json.encodeToString(transform(current))
        }
    }
}
```

- [ ] **Step 2: Build `:app:assembleDebug` → green. Commit** `feat(app): NTRIP credential profiles persisted in DataStore`

---

### Task 6: Service wiring — NtripSession inside GuidanceService

**Files:**
- Create: `service/src/main/java/com/agroguide/rtk/service/NtripSession.kt`
- Modify: `service/src/main/java/com/agroguide/rtk/service/GuidanceService.kt`
- Modify: `service/src/main/java/com/agroguide/rtk/service/PositionRepository.kt`
- Modify: `service/build.gradle.kts` — no new deps (`:device` api-exposes `:core:ntrip`).

- [ ] **Step 1: `NtripSession.kt`**

```kotlin
package com.agroguide.rtk.service

import com.agroguide.rtk.device.SourceEvent
import com.agroguide.rtk.device.bt.BtSppPositionSource
import com.agroguide.rtk.device.bt.ReconnectPolicy
import com.agroguide.rtk.ntrip.NtripClient
import com.agroguide.rtk.ntrip.NtripCredentials
import com.agroguide.rtk.ntrip.NtripStats
import com.agroguide.rtk.ntrip.NtripVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the NTRIP loop for one BT session: reconnects with backoff, pipes RTCM
 * into the receiver socket, answers GGA upload requests with the latest GGA
 * observed on the NMEA stream. Config comes from the active NtripProfile.
 */
class NtripSession(
    private val scope: CoroutineScope,
    private val bt: BtSppPositionSource,
    sourceEvents: Flow<SourceEvent>,
    private val creds: NtripCredentials,
    private val mountPoint: String,
    private val version: NtripVersion,
    private val uploadGga: Boolean,
    ggaIntervalS: Int,
) {
    private val _stats = MutableStateFlow(NtripStats())
    val stats: StateFlow<NtripStats> = _stats.asStateFlow()

    @Volatile private var lastGga: String? = null
    private val ggaIntervalMs = ggaIntervalS * 1000L
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            // remember the latest GGA line for VRS upload
            launch {
                sourceEvents.filterIsInstance<SourceEvent.Nmea>()
                    .collect { if (it.line.regionMatches(3, "GGA", 0, 3)) lastGga = it.line }
            }
            val policy = ReconnectPolicy()
            while (isActive) {
                val client = NtripClient(creds, mountPoint, version, ggaIntervalMs)
                val statsMirror = launch { client.stats.collect { _stats.value = it } }
                client.run(
                    rtcmSink = { bt.write(it) },
                    ggaProvider = { if (uploadGga) lastGga else null },
                )
                statsMirror.cancel()
                if (!isActive) break
                delay(policy.nextDelayMs())
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
```

- [ ] **Step 2: Repository + service plumbing.**

`PositionRepository`: add next to the other flows:
```kotlin
    private val _ntrip = MutableStateFlow(com.agroguide.rtk.ntrip.NtripStats())
    val ntrip: StateFlow<com.agroguide.rtk.ntrip.NtripStats> = _ntrip.asStateFlow()
    fun publishNtrip(stats: com.agroguide.rtk.ntrip.NtripStats) { _ntrip.value = stats }
```
Also expose the shared event flow for the service: change `attach` to store it:
```kotlin
    @Volatile var sharedEvents: kotlinx.coroutines.flow.SharedFlow<SourceEvent>? = null
        private set
    // inside attach(): sharedEvents = shared
    // inside detach(): sharedEvents = null; _ntrip.value = com.agroguide.rtk.ntrip.NtripStats()
```

`GuidanceService`: new extras `EXTRA_NTRIP` (Boolean) + profile fields passed via intent (host, port, mount, user, pass, v2, uploadGga, ggaInterval — primitive extras; the Activity reads the active profile from DataStore and flattens it). In `onStartCommand`, when mode==BT and `EXTRA_NTRIP`:
```kotlin
        ntripSession?.stop()
        val src = source as? BtSppPositionSource
        val events = PositionRepository.sharedEvents
        if (src != null && events != null && intent.getBooleanExtra(EXTRA_NTRIP, false)) {
            ntripSession = NtripSession(
                lifecycleScope, src, events,
                NtripCredentials(
                    intent.getStringExtra(EXTRA_NTRIP_HOST) ?: "",
                    intent.getIntExtra(EXTRA_NTRIP_PORT, 2101),
                    intent.getStringExtra(EXTRA_NTRIP_USER) ?: "",
                    intent.getStringExtra(EXTRA_NTRIP_PASS) ?: "",
                ),
                mountPoint = intent.getStringExtra(EXTRA_NTRIP_MOUNT) ?: "",
                version = if (intent.getBooleanExtra(EXTRA_NTRIP_V2, false)) NtripVersion.V2 else NtripVersion.V1,
                uploadGga = intent.getBooleanExtra(EXTRA_NTRIP_GGA, true),
                ggaIntervalS = intent.getIntExtra(EXTRA_NTRIP_GGA_INT, 10),
            ).also { s -> s.start(); lifecycleScope.launch { s.stats.collect { PositionRepository.publishNtrip(it) } } }
        }
```
(ordering: call `PositionRepository.attach(source)` BEFORE building the session so `sharedEvents` is set). `onDestroy`: `ntripSession?.stop()`. Also add a `sendToReceiver(bytes: ByteArray)` static hook: keep a `companion object { @Volatile var activeBt: BtSppPositionSource? = null }` set in onStartCommand/onDestroy — the Receptor UI uses it to send VALSET frames.

- [ ] **Step 3: Build all + tests → green. Commit** `feat(service): NTRIP session wired to BT receiver with GGA upload and stats`

---

### Task 7: UI — tabs, NTRIP screen, receiver utilities

**Files:**
- Create: `app/src/main/java/com/agroguide/rtk/ui/NtripScreen.kt`
- Create: `app/src/main/java/com/agroguide/rtk/ui/ReceiverScreen.kt`
- Modify: `app/src/main/java/com/agroguide/rtk/ui/HomeScreen.kt` (NTRIP toggle + status row)
- Modify: `app/src/main/java/com/agroguide/rtk/MainActivity.kt` (3 tabs: Guiado | NTRIP | Receptor)
- Modify: `app/src/main/res/values/strings.xml`

Content requirements (implement with the existing Compose style, 56 dp touch targets, Spanish strings):

1. **Tabs**: `TabRow` with Guiado / NTRIP / Receptor; state hoisted in MainActivity.
2. **NtripScreen**: list of profiles (name + host:port/mount) from `NtripProfileStore.settings`; tap = set active (radio); long-press-free edit via an "Editar" `TextButton` per row; "Nuevo perfil" button opens an `AlertDialog` form (name, host, port, mountpoint, user, password [visualTransformation = PasswordVisualTransformation()], switch "NTRIP v2", switch "Enviar GGA", GGA interval seconds). "Explorar" button inside the dialog: calls `NtripClient.fetchSourceTable` (in a coroutine, `Dispatchers.IO`), shows result list in a second dialog (mountpoint, identifier, format, country, badges "GGA" if requiresGga and candado if requiresAuth); tapping fills the mountpoint field. Errors → `Text` in red inside the dialog, never a crash. Delete button ("Eliminar") with a confirm dialog.
3. **HomeScreen additions**: below the source options, when mode==BT: a `Switch` "Correcciones NTRIP" (enabled only if there is an active profile; hint text otherwise) — its state is passed to `GuidanceService.start`. Below the connection banner: NTRIP status line when active: green dot + "NTRIP: conectado · 2,4 KB/s · edad 1,2 s" (bytes/s computed in UI from consecutive stats samples; correction age comes from the fix panel data) or red "NTRIP: sin conexión".
4. **ReceiverScreen**: only functional when BT source is running (`PositionRepository.sourceName.value == "BT-SPP"`; otherwise a hint "Conecta el receptor Bluetooth primero"). Buttons ≥64 dp: "Optimizar receptor (5 Hz)" → `GuidanceService.activeBt?.write(UbxValSet.optimize())`; "Restaurar valores de fábrica" → `write(UbxValSet.restoreDefaults())`. Listen to `PositionRepository.sharedEvents` for `SourceEvent.UbxAckEvent`: show a `Snackbar` "Receptor configurado ✓" (ok) or "El receptor rechazó el comando" (nak) within 3 s, or "Sin respuesta del receptor" on timeout.
5. **New strings** (Spanish): tab_guidance="Guiado", tab_ntrip="NTRIP", tab_receiver="Receptor", ntrip_new_profile="Nuevo perfil", ntrip_edit="Editar", ntrip_delete="Eliminar", ntrip_browse="Explorar mountpoints", ntrip_active="Perfil activo", ntrip_corrections="Correcciones NTRIP", ntrip_need_profile="Crea un perfil NTRIP en la pestaña NTRIP", ntrip_connected="NTRIP: conectado", ntrip_disconnected="NTRIP: sin conexión", receiver_optimize="Optimizar receptor (5 Hz)", receiver_restore="Restaurar valores de fábrica", receiver_need_bt="Conecta el receptor Bluetooth primero", receiver_ok="Receptor configurado ✓", receiver_nak="El receptor rechazó el comando", receiver_timeout="Sin respuesta del receptor", plus field labels (ntrip_host="Servidor", ntrip_port="Puerto", ntrip_mount="Mountpoint", ntrip_user="Usuario", ntrip_pass="Contraseña", ntrip_v2="NTRIP v2", ntrip_send_gga="Enviar GGA al caster", ntrip_gga_interval="Intervalo GGA (s)", save="Guardar", cancel="Cancelar").

- [ ] **Step 1: Implement all of the above.**
- [ ] **Step 2: `.\gradlew.bat test assembleDebug` → green. Commit** `feat(app): NTRIP profiles/browser UI, receiver utilities, guidance NTRIP indicator`

---

### Task 8: Phase-2 verification & checkpoint

- [ ] **Step 1:** `.\gradlew.bat test assembleDebug --console=plain` from clean state (run `clean` separately first) → all green.
- [ ] **Step 2:** If a device is attached: install, demo-mode smoke test still works; NTRIP tab: create profile against a public caster (e.g. rtk2go.com:2101) → "Explorar" lists mountpoints.
- [ ] **Step 3:** Checkpoint report (Spanish): what was built, manual tests with/without hardware (including a real VRS flow if the user has credentials), known limitations (UBX keys fixed for UART1/UART2, no TLS casters, profiles unencrypted in DataStore, no RTCM message-type breakdown).
- [ ] **Step 4:** `git tag phase2-checkpoint`
