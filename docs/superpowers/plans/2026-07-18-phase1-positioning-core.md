# AgroGuide RTK — Phase 1 Implementation Plan (skeleton + positioning core)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Buildable Android app with the positioning pipeline (BT SPP + Android Location + demo replay), NMEA parsing, and a live fix-status panel.

**Architecture:** Multi-module Gradle project. Pure-JVM modules `:core:geo` (ENU math, jump filter, CV projector) and `:core:nmea` (parser, fix aggregation, replay/synthetic generators) hold all testable logic. `:device` implements `PositionSource` (BT SPP with watchdog+backoff, LocationManager, replay). `:service` runs a foreground service feeding a process-wide `PositionRepository`. `:app` is Compose UI (Spanish strings) showing the fix panel and source selection.

**Tech Stack:** Kotlin 2.1.20, AGP 8.10.1, Gradle 8.11.1, JDK 21 (Android Studio JBR at `C:\Program Files\Android\Android Studio\jbr`), compileSdk/targetSdk 36, minSdk 26, Compose BOM 2025.05.00, kotlinx-coroutines, JUnit4 + kotlin-test. No DI framework (manual ServiceLocator). No Hilt, no map SDK.

**Conventions:** Code and comments in English. UI strings in Spanish under `res/values/strings.xml` (default locale = Spanish). Package root `com.agroguide.rtk`; core modules use `com.agroguide.rtk.geo` / `com.agroguide.rtk.nmea`.

**Environment notes for the executor:**
- No system Gradle/Java on PATH. Use `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` in every shell that runs Gradle.
- Bootstrap wrapper (Task 1) by downloading the Gradle 8.11.1 distribution once to the scratchpad, then always use `.\gradlew`.
- Android SDK at `$env:LOCALAPPDATA\Android\Sdk` → write `local.properties` with `sdk.dir`.
- All Gradle commands run from repo root `C:\Users\AitorADC\Desktop\IA\CLAUDE\GUIADO-TRACTOR`.

---

### Task 1: Gradle skeleton (5 modules, buildable, empty)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `local.properties`, `.gitignore`
- Create: `core/geo/build.gradle.kts`, `core/nmea/build.gradle.kts`, `device/build.gradle.kts`, `service/build.gradle.kts`, `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/agroguide/rtk/MainActivity.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`
- Create: gradle wrapper (`gradlew.bat`, `gradle/wrapper/*`)

- [ ] **Step 1: Download Gradle and generate the wrapper**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$scratch = "C:\Users\AitorADC\AppData\Local\Temp\claude\C--Users-AitorADC-Desktop-IA-CLAUDE-GUIADO-TRACTOR\ddb08bbd-af4f-46fd-bd52-00f4e7ca8cf0\scratchpad"
Invoke-WebRequest "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip" -OutFile "$scratch\gradle.zip"
Expand-Archive "$scratch\gradle.zip" -DestinationPath "$scratch\gradle"
& "$scratch\gradle\gradle-8.11.1\bin\gradle.bat" wrapper --gradle-version 8.11.1 --distribution-type bin
```
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar|properties` created in repo root.

- [ ] **Step 2: Write root build files**

`.gitignore`:
```
.gradle/
build/
local.properties
.idea/
*.iml
.kotlin/
```

`local.properties` (NOT committed):
```
sdk.dir=C\:\\Users\\AitorADC\\AppData\\Local\\Android\\Sdk
```

`gradle.properties`:
```
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
org.gradle.caching=true
org.gradle.configuration-cache=true
# compileSdk 36 pairs with AGP 8.10+; if AGP ever warns about untested compileSdk:
android.suppressUnsupportedCompileSdk=36
```

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AgroGuideRTK"
include(":app", ":core:geo", ":core:nmea", ":device", ":service")
```

`gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.10.1"
kotlin = "2.1.20"
coroutines = "1.10.1"
composeBom = "2025.05.00"
activityCompose = "1.10.1"
lifecycle = "2.8.7"
coreKtx = "1.16.0"
junit = "4.13.2"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-service = { module = "androidx.lifecycle:lifecycle-service", version.ref = "lifecycle" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
junit = { module = "junit:junit", version.ref = "junit" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

Root `build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

- [ ] **Step 3: Write module build files**

`core/geo/build.gradle.kts` (identical pattern for `core/nmea/build.gradle.kts`):
```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

`device/build.gradle.kts` (same for `service/build.gradle.kts`, changing namespace suffix to `.service` and adding `implementation(libs.androidx.lifecycle.service)`):
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.agroguide.rtk.device"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core:nmea"))
    api(project(":core:geo"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
```
(`service/build.gradle.kts` additionally: `api(project(":device"))`.)

`app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.agroguide.rtk"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.agroguide.rtk"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":service"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 4: Minimal app shell**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="@string/app_name"
        android:theme="@style/Theme.AgroGuide"
        android:supportsRtl="false">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="userLandscape"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/values/strings.xml` (Spanish default):
```xml
<resources>
    <string name="app_name">AgroGuide RTK</string>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.AgroGuide" parent="android:Theme.Material.NoActionBar.Fullscreen" />
</resources>
```

`app/src/main/java/com/agroguide/rtk/MainActivity.kt`:
```kotlin
package com.agroguide.rtk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Text("AgroGuide RTK") } }
    }
}
```

Also create placeholder source dirs so pure modules compile: `core/geo/src/main/kotlin/`, `core/nmea/src/main/kotlin/` (empty is fine — Gradle tolerates no sources).

- [ ] **Step 5: Build and verify**

Run: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 6: Commit**

```powershell
git add -A; git commit -m "chore: multi-module Gradle skeleton (app, core:geo, core:nmea, device, service)"
```

---

### Task 2: `:core:nmea` — sentence types + checksum + parser (TDD)

**Files:**
- Create: `core/nmea/src/main/kotlin/com/agroguide/rtk/nmea/NmeaSentence.kt`
- Create: `core/nmea/src/main/kotlin/com/agroguide/rtk/nmea/NmeaParser.kt`
- Test: `core/nmea/src/test/kotlin/com/agroguide/rtk/nmea/NmeaParserTest.kt`

- [ ] **Step 1: Define the model** (`NmeaSentence.kt`)

```kotlin
package com.agroguide.rtk.nmea

/** GGA fix quality per NMEA 0183. */
enum class FixQuality(val code: Int) {
    NO_FIX(0), GPS(1), DGPS(2), PPS(3), RTK_FIXED(4), RTK_FLOAT(5), DEAD_RECKONING(6);
    companion object {
        fun fromCode(code: Int): FixQuality = entries.firstOrNull { it.code == code } ?: NO_FIX
    }
}

sealed interface NmeaSentence {
    /** $--GGA: position, fix quality, sats, HDOP, altitude, correction age. */
    data class Gga(
        val timeUtc: String?,          // "hhmmss.ss" as sent, null if empty
        val latDeg: Double?,           // signed decimal degrees, null if no fix
        val lonDeg: Double?,
        val quality: FixQuality,
        val satellites: Int?,
        val hdop: Double?,
        val altitudeM: Double?,
        val correctionAgeS: Double?,   // field 13, empty when no DGPS/RTK
    ) : NmeaSentence

    /** $--RMC: position, speed over ground, course over ground, date. */
    data class Rmc(
        val timeUtc: String?,
        val valid: Boolean,            // A=valid, V=void
        val latDeg: Double?,
        val lonDeg: Double?,
        val speedMps: Double?,         // converted from knots
        val courseDeg: Double?,        // true course, null when not moving
        val dateDdMmYy: String?,
    ) : NmeaSentence

    /** $--VTG: course and speed over ground. */
    data class Vtg(val courseDeg: Double?, val speedMps: Double?) : NmeaSentence

    /** $--GSV: satellites in view (only total count parsed; per-sat data ignored in v1). */
    data class Gsv(val totalInView: Int?) : NmeaSentence

    /** $--GST: position error statistics (1-sigma, meters). */
    data class Gst(val latErrM: Double?, val lonErrM: Double?, val altErrM: Double?) : NmeaSentence

    data class Unknown(val type: String) : NmeaSentence
}
```

- [ ] **Step 2: Write failing tests** (`NmeaParserTest.kt`) — the full set below; run after writing.

```kotlin
package com.agroguide.rtk.nmea

import kotlin.test.*

class NmeaParserTest {
    // Real ZED-F9P style sentences (checksums valid).
    private val gga = "\$GNGGA,123519.00,4807.03800,N,01131.00000,E,4,12,0.58,545.4,M,46.9,M,1.2,0000*57"
    private val rmc = "\$GNRMC,123519.00,A,4807.03800,N,01131.00000,E,3.5,84.4,230326,,,R,V*33"

    @Test fun `parses GGA with RTK fixed quality`() {
        val s = NmeaParser.parse(gga)
        assertIs<NmeaSentence.Gga>(s)
        assertEquals(FixQuality.RTK_FIXED, s.quality)
        assertEquals(48.117300, s.latDeg!!, 1e-6)
        assertEquals(11.516667, s.lonDeg!!, 1e-6)
        assertEquals(12, s.satellites)
        assertEquals(0.58, s.hdop!!, 1e-9)
        assertEquals(545.4, s.altitudeM!!, 1e-9)
        assertEquals(1.2, s.correctionAgeS!!, 1e-9)
    }

    @Test fun `parses RMC speed knots to mps and course`() {
        val s = NmeaParser.parse(rmc)
        assertIs<NmeaSentence.Rmc>(s)
        assertTrue(s.valid)
        assertEquals(3.5 * 0.514444, s.speedMps!!, 1e-6)
        assertEquals(84.4, s.courseDeg!!, 1e-9)
    }

    @Test fun `southern and western hemispheres are negative`() {
        val line = withChecksum("\$GNGGA,000000.00,3345.00000,S,07030.00000,W,1,08,1.0,10.0,M,0.0,M,,")
        val s = NmeaParser.parse(line) as NmeaSentence.Gga
        assertEquals(-33.75, s.latDeg!!, 1e-9)
        assertEquals(-70.5, s.lonDeg!!, 1e-9)
    }

    @Test fun `rejects wrong checksum`() {
        assertNull(NmeaParser.parse(gga.dropLast(2) + "00"))
    }

    @Test fun `rejects truncated sentence`() {
        assertNull(NmeaParser.parse("\$GNGGA,1235"))          // no checksum
        assertNull(NmeaParser.parse(gga.substring(0, 20)))
    }

    @Test fun `rejects garbage and empty`() {
        assertNull(NmeaParser.parse(""))
        assertNull(NmeaParser.parse("not nmea at all"))
        assertNull(NmeaParser.parse("\$*00"))
    }

    @Test fun `GGA with empty fields yields nulls and NO_FIX`() {
        val line = withChecksum("\$GNGGA,,,,,,0,,,,M,,M,,")
        val s = NmeaParser.parse(line) as NmeaSentence.Gga
        assertEquals(FixQuality.NO_FIX, s.quality)
        assertNull(s.latDeg); assertNull(s.satellites); assertNull(s.correctionAgeS)
    }

    @Test fun `RMC void has valid=false`() {
        val line = withChecksum("\$GNRMC,,V,,,,,,,,,,N,V")
        val s = NmeaParser.parse(line) as NmeaSentence.Rmc
        assertFalse(s.valid)
    }

    @Test fun `parses VTG GSV GST`() {
        val vtg = withChecksum("\$GNVTG,84.4,T,,M,3.5,N,6.5,K,D")
        assertEquals(84.4, (NmeaParser.parse(vtg) as NmeaSentence.Vtg).courseDeg!!, 1e-9)
        val gsv = withChecksum("\$GPGSV,3,1,11,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45")
        assertEquals(11, (NmeaParser.parse(gsv) as NmeaSentence.Gsv).totalInView)
        val gst = withChecksum("\$GNGST,123519.00,1.2,0.03,0.02,12.3,0.01,0.02,0.04")
        val g = NmeaParser.parse(gst) as NmeaSentence.Gst
        assertEquals(0.01, g.latErrM!!, 1e-9); assertEquals(0.02, g.lonErrM!!, 1e-9); assertEquals(0.04, g.altErrM!!, 1e-9)
    }

    @Test fun `unknown talker sentence returns Unknown`() {
        val line = withChecksum("\$GNZDA,123519.00,23,03,2026,00,00")
        assertEquals("ZDA", (NmeaParser.parse(line) as NmeaSentence.Unknown).type)
    }

    /** Helper: append correct *hh checksum to a sentence body starting with '$'. */
    private fun withChecksum(body: String): String {
        var x = 0; for (c in body.drop(1)) x = x xor c.code
        return body + "*" + x.toString(16).uppercase().padStart(2, '0')
    }
}
```

- [ ] **Step 3: Run tests, verify they fail**

Run: `.\gradlew.bat :core:nmea:test --console=plain`
Expected: compilation failure (NmeaParser missing).

- [ ] **Step 4: Implement `NmeaParser.kt`**

```kotlin
package com.agroguide.rtk.nmea

/**
 * Parser for the NMEA 0183 sentences emitted by u-blox ZED-F9P.
 * Returns null for anything that fails checksum or basic framing —
 * callers treat null as line noise, never as an error.
 */
object NmeaParser {

    fun parse(line: String): NmeaSentence? {
        val trimmed = line.trim()
        if (trimmed.length < 9 || !trimmed.startsWith("$")) return null
        val star = trimmed.lastIndexOf('*')
        if (star < 0 || star + 3 > trimmed.length) return null
        val body = trimmed.substring(1, star)
        val expected = trimmed.substring(star + 1, star + 3).toIntOrNull(16) ?: return null
        var x = 0; for (c in body) x = x xor c.code
        if (x != expected) return null

        val f = body.split(',')
        val type = f[0].takeIf { it.length >= 5 }?.substring(2) ?: return null
        return try {
            when (type) {
                "GGA" -> parseGga(f)
                "RMC" -> parseRmc(f)
                "VTG" -> parseVtg(f)
                "GSV" -> NmeaSentence.Gsv(f.getOrNull(3)?.toIntOrNull())
                "GST" -> NmeaSentence.Gst(
                    latErrM = f.getOrNull(6)?.toDoubleOrNull(),
                    lonErrM = f.getOrNull(7)?.toDoubleOrNull(),
                    altErrM = f.getOrNull(8)?.toDoubleOrNull(),
                )
                else -> NmeaSentence.Unknown(type)
            }
        } catch (_: IndexOutOfBoundsException) {
            null // truncated field list
        }
    }

    private fun parseGga(f: List<String>): NmeaSentence.Gga? {
        if (f.size < 14) return null
        return NmeaSentence.Gga(
            timeUtc = f[1].ifEmpty { null },
            latDeg = parseLat(f[2], f[3]),
            lonDeg = parseLon(f[4], f[5]),
            quality = FixQuality.fromCode(f[6].toIntOrNull() ?: 0),
            satellites = f[7].toIntOrNull(),
            hdop = f[8].toDoubleOrNull(),
            altitudeM = f[9].toDoubleOrNull(),
            correctionAgeS = f[13].toDoubleOrNull(),
        )
    }

    private fun parseRmc(f: List<String>): NmeaSentence.Rmc? {
        if (f.size < 10) return null
        return NmeaSentence.Rmc(
            timeUtc = f[1].ifEmpty { null },
            valid = f[2] == "A",
            latDeg = parseLat(f[3], f[4]),
            lonDeg = parseLon(f[5], f[6]),
            speedMps = f[7].toDoubleOrNull()?.times(KNOT_MPS),
            courseDeg = f[8].toDoubleOrNull(),
            dateDdMmYy = f[9].ifEmpty { null },
        )
    }

    private fun parseVtg(f: List<String>): NmeaSentence.Vtg {
        return NmeaSentence.Vtg(
            courseDeg = f.getOrNull(1)?.toDoubleOrNull(),
            speedMps = f.getOrNull(5)?.toDoubleOrNull()?.times(KNOT_MPS),
        )
    }

    /** "4807.03800","N" → 48.1173; ddmm.mmmm format. */
    private fun parseLat(v: String, hemi: String): Double? {
        if (v.length < 4) return null
        val deg = v.substring(0, 2).toDoubleOrNull() ?: return null
        val min = v.substring(2).toDoubleOrNull() ?: return null
        val d = deg + min / 60.0
        return if (hemi == "S") -d else d
    }

    /** "01131.00000","E" → 11.516667; dddmm.mmmm format. */
    private fun parseLon(v: String, hemi: String): Double? {
        if (v.length < 5) return null
        val deg = v.substring(0, 3).toDoubleOrNull() ?: return null
        val min = v.substring(3).toDoubleOrNull() ?: return null
        val d = deg + min / 60.0
        return if (hemi == "W") -d else d
    }

    private const val KNOT_MPS = 0.514444
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `.\gradlew.bat :core:nmea:test --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests green. If a checksum literal in a fixture is wrong, recompute it (XOR of chars between `$` and `*`) and fix the fixture, not the parser.

- [ ] **Step 6: Commit**

```powershell
git add core/nmea; git commit -m "feat(nmea): NMEA 0183 parser for GGA/RMC/VTG/GSV/GST with checksum validation"
```

---

### Task 3: `:core:nmea` — GnssFix aggregation + line assembler

**Files:**
- Create: `core/nmea/src/main/kotlin/com/agroguide/rtk/nmea/GnssFix.kt` (contains `FixAggregator`)
- Create: `core/nmea/src/main/kotlin/com/agroguide/rtk/nmea/NmeaLineAssembler.kt`
- Test: `core/nmea/src/test/kotlin/com/agroguide/rtk/nmea/FixAggregatorTest.kt`
- Test: `core/nmea/src/test/kotlin/com/agroguide/rtk/nmea/NmeaLineAssemblerTest.kt`

- [ ] **Step 1: Write failing tests**

`FixAggregatorTest.kt`:
```kotlin
package com.agroguide.rtk.nmea

import kotlin.test.*

class FixAggregatorTest {
    private fun cs(body: String): String {
        var x = 0; for (c in body.drop(1)) x = x xor c.code
        return body + "*" + x.toString(16).uppercase().padStart(2, '0')
    }

    @Test fun `GGA alone emits fix without speed`() {
        val agg = FixAggregator()
        val fix = agg.onSentence(
            NmeaParser.parse(cs("\$GNGGA,120000.00,4807.03800,N,01131.00000,E,1,08,1.1,500.0,M,46.9,M,,"))!!,
            monotonicMs = 1000L,
        )
        assertNotNull(fix)
        assertEquals(48.1173, fix.latDeg, 1e-6)
        assertEquals(FixQuality.GPS, fix.quality)
        assertNull(fix.speedMps)
        assertEquals(1000L, fix.monotonicMs)
    }

    @Test fun `RMC then GGA merges speed and course into the GGA fix`() {
        val agg = FixAggregator()
        assertNull(agg.onSentence(NmeaParser.parse(cs("\$GNRMC,120000.00,A,4807.03800,N,01131.00000,E,2.0,90.0,230326,,,A,V"))!!, 990L))
        val fix = agg.onSentence(NmeaParser.parse(cs("\$GNGGA,120000.00,4807.03800,N,01131.00000,E,4,12,0.6,500.0,M,46.9,M,1.0,0000"))!!, 1000L)!!
        assertEquals(2.0 * 0.514444, fix.speedMps!!, 1e-6)
        assertEquals(90.0, fix.courseDeg!!, 1e-9)
        assertEquals(FixQuality.RTK_FIXED, fix.quality)
    }

    @Test fun `GSV updates satellites in view carried on next fix`() {
        val agg = FixAggregator()
        agg.onSentence(NmeaParser.parse(cs("\$GPGSV,3,1,11,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45"))!!, 900L)
        val fix = agg.onSentence(NmeaParser.parse(cs("\$GNGGA,120000.00,4807.03800,N,01131.00000,E,1,08,1.1,500.0,M,46.9,M,,"))!!, 1000L)!!
        assertEquals(11, fix.satsInView)
    }

    @Test fun `GGA without position emits null`() {
        val agg = FixAggregator()
        assertNull(agg.onSentence(NmeaParser.parse(cs("\$GNGGA,,,,,,0,,,,M,,M,,"))!!, 1000L))
    }
}
```

`NmeaLineAssemblerTest.kt`:
```kotlin
package com.agroguide.rtk.nmea

import kotlin.test.*

class NmeaLineAssemblerTest {
    @Test fun `reassembles lines across chunk boundaries`() {
        val asm = NmeaLineAssembler()
        val out = mutableListOf<String>()
        out += asm.feed("\$GNGGA,1".toByteArray())
        out += asm.feed("23519.00,A*7F\r\n\$GNRMC".toByteArray())
        assertEquals(listOf("\$GNGGA,123519.00,A*7F"), out)
        out.clear()
        out += asm.feed(",x*11\r\n".toByteArray())
        assertEquals(listOf("\$GNRMC,x*11"), out)
    }

    @Test fun `drops oversized garbage without newline`() {
        val asm = NmeaLineAssembler()
        assertTrue(asm.feed(ByteArray(2000) { 'A'.code.toByte() }).isEmpty())
        // buffer must have been reset: a normal line still comes through
        assertEquals(listOf("\$X*00"), asm.feed("\$X*00\r\n".toByteArray()))
    }

    @Test fun `binary RTCM noise between sentences is ignored`() {
        val asm = NmeaLineAssembler()
        val lines = asm.feed(byteArrayOf(0xD3.toByte(), 0x00, 0x13) + "\$X*00\r\n".toByteArray())
        assertEquals(listOf("\$X*00"), lines)
    }
}
```

- [ ] **Step 2: Run tests, verify fail** — `.\gradlew.bat :core:nmea:test` → compile error.

- [ ] **Step 3: Implement**

`GnssFix.kt`:
```kotlin
package com.agroguide.rtk.nmea

/**
 * One accepted position fix, aggregated from GGA (authoritative for position/quality)
 * plus the most recent RMC/VTG (speed, course), GSV (sats in view) and GST (sigma).
 * [monotonicMs] is the receive timestamp on the local monotonic clock.
 */
data class GnssFix(
    val latDeg: Double,
    val lonDeg: Double,
    val altitudeM: Double?,
    val quality: FixQuality,
    val satellites: Int?,      // used in solution (GGA field 7)
    val satsInView: Int?,      // from GSV
    val hdop: Double?,
    val correctionAgeS: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
    val sigmaLatM: Double?,
    val sigmaLonM: Double?,
    val timeUtc: String?,
    val monotonicMs: Long,
)

/**
 * Stateful merger: feed every parsed sentence; a non-null return means
 * "new fix ready" (emitted once per GGA, which paces the stream).
 */
class FixAggregator {
    private var lastRmc: NmeaSentence.Rmc? = null
    private var lastVtg: NmeaSentence.Vtg? = null
    private var lastGsv: NmeaSentence.Gsv? = null
    private var lastGst: NmeaSentence.Gst? = null

    fun onSentence(s: NmeaSentence, monotonicMs: Long): GnssFix? {
        when (s) {
            is NmeaSentence.Rmc -> lastRmc = s
            is NmeaSentence.Vtg -> lastVtg = s
            is NmeaSentence.Gsv -> lastGsv = s
            is NmeaSentence.Gst -> lastGst = s
            is NmeaSentence.Gga -> {
                val lat = s.latDeg ?: return null
                val lon = s.lonDeg ?: return null
                return GnssFix(
                    latDeg = lat, lonDeg = lon, altitudeM = s.altitudeM,
                    quality = s.quality, satellites = s.satellites,
                    satsInView = lastGsv?.totalInView, hdop = s.hdop,
                    correctionAgeS = s.correctionAgeS,
                    speedMps = lastRmc?.speedMps ?: lastVtg?.speedMps,
                    courseDeg = lastRmc?.courseDeg ?: lastVtg?.courseDeg,
                    sigmaLatM = lastGst?.latErrM, sigmaLonM = lastGst?.lonErrM,
                    timeUtc = s.timeUtc, monotonicMs = monotonicMs,
                )
            }
            is NmeaSentence.Unknown -> {}
        }
        return null
    }
}
```

`NmeaLineAssembler.kt`:
```kotlin
package com.agroguide.rtk.nmea

/**
 * Turns an arbitrary byte-chunk stream (BT SPP reads) into complete NMEA lines.
 * Bytes before a '$' are discarded (RTCM echoes, UBX ACKs, noise).
 * Lines longer than [maxLine] without a terminator reset the buffer.
 */
class NmeaLineAssembler(private val maxLine: Int = 256) {
    private val buf = StringBuilder()

    fun feed(chunk: ByteArray): List<String> {
        val out = mutableListOf<String>()
        for (b in chunk) {
            val c = b.toInt().toChar()
            when {
                c == '$' -> { buf.setLength(0); buf.append(c) }
                c == '\n' || c == '\r' -> {
                    if (buf.isNotEmpty()) { out += buf.toString(); buf.setLength(0) }
                }
                buf.isNotEmpty() -> {
                    buf.append(c)
                    if (buf.length > maxLine) buf.setLength(0)
                }
                // else: byte outside any sentence — drop
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run tests, verify pass** — `.\gradlew.bat :core:nmea:test` → green.

- [ ] **Step 5: Commit** — `git add core/nmea; git commit -m "feat(nmea): fix aggregation (GGA-paced) and byte-stream line assembler"`

---

### Task 4: `:core:nmea` — replay + synthetic NMEA generators (demo mode backbone)

**Files:**
- Create: `core/nmea/src/main/kotlin/com/agroguide/rtk/nmea/replay/NmeaReplayer.kt`
- Create: `core/nmea/src/main/kotlin/com/agroguide/rtk/nmea/replay/SyntheticNmeaGenerator.kt`
- Test: `core/nmea/src/test/kotlin/com/agroguide/rtk/nmea/replay/NmeaReplayerTest.kt`
- Test: `core/nmea/src/test/kotlin/com/agroguide/rtk/nmea/replay/SyntheticNmeaGeneratorTest.kt`

- [ ] **Step 1: Write failing tests**

`SyntheticNmeaGeneratorTest.kt`:
```kotlin
package com.agroguide.rtk.nmea.replay

import com.agroguide.rtk.nmea.*
import kotlin.test.*

class SyntheticNmeaGeneratorTest {
    @Test fun `generates parseable GGA and RMC pairs along a track`() {
        val gen = SyntheticNmeaGenerator(
            startLatDeg = 42.0, startLonDeg = -5.0,
            speedMps = 3.0, quality = FixQuality.RTK_FIXED,
        )
        val sentences = gen.next()  // one epoch = [GGA, RMC, VTG]
        assertEquals(3, sentences.size)
        val gga = NmeaParser.parse(sentences[0])
        assertIs<NmeaSentence.Gga>(gga)
        assertEquals(FixQuality.RTK_FIXED, gga.quality)
        assertEquals(42.0, gga.latDeg!!, 1e-4)
        val rmc = NmeaParser.parse(sentences[1])
        assertIs<NmeaSentence.Rmc>(rmc)
        assertEquals(3.0, rmc.speedMps!!, 0.01)
    }

    @Test fun `position advances between epochs at commanded speed`() {
        val gen = SyntheticNmeaGenerator(42.0, -5.0, speedMps = 5.0, headingDeg = 0.0, epochIntervalS = 1.0)
        val g1 = NmeaParser.parse(gen.next()[0]) as NmeaSentence.Gga
        val g2 = NmeaParser.parse(gen.next()[0]) as NmeaSentence.Gga
        // 5 m north ≈ 5 / 111320 degrees latitude
        assertEquals(5.0 / 111_320.0, g2.latDeg!! - g1.latDeg!!, 1e-7)
    }
}
```

`NmeaReplayerTest.kt` (virtual-time with coroutines-test):
```kotlin
package com.agroguide.rtk.nmea.replay

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import kotlin.test.*

class NmeaReplayerTest {
    @Test fun `emits lines paced at requested rate`() = runTest {
        val lines = listOf("\$A*00", "\$B*00", "\$C*00", "\$D*00")
        val replayer = NmeaReplayer(lines, rateHz = 2.0)   // 500 ms per line
        val stamped = mutableListOf<Pair<Long, String>>()
        val t0 = currentTime
        replayer.stream().collect { stamped += (currentTime - t0) to it }
        assertEquals(4, stamped.size)
        assertEquals(listOf(0L, 500L, 1000L, 1500L), stamped.map { it.first })
    }

    @Test fun `loops forever when loop=true`() = runTest {
        val replayer = NmeaReplayer(listOf("\$A*00"), rateHz = 10.0, loop = true)
        val collected = mutableListOf<String>()
        val job = launch { replayer.stream().collect { collected += it } }
        advanceTimeBy(1000); job.cancel()
        assertTrue(collected.size >= 9)
    }
}
```

- [ ] **Step 2: Run, verify fail** — compile error.

- [ ] **Step 3: Implement**

`NmeaReplayer.kt`:
```kotlin
package com.agroguide.rtk.nmea.replay

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replays a list of NMEA lines at [rateHz] lines/second.
 * Groups are NOT reordered: file replay assumes the file is already
 * in receiver emission order. Uses coroutine delay → test-friendly virtual time.
 */
class NmeaReplayer(
    private val lines: List<String>,
    private val rateHz: Double = 1.0,
    private val loop: Boolean = false,
) {
    // First line emits immediately (t=0); every later line is delayed one
    // period, including across loop passes — matches the pacing test.
    fun stream(): Flow<String> = flow {
        val periodMs = (1000.0 / rateHz).toLong()
        var first = true
        do {
            for (line in lines) {
                if (!first) delay(periodMs)
                first = false
                emit(line)
            }
        } while (loop)
    }
}
```

`SyntheticNmeaGenerator.kt`:
```kotlin
package com.agroguide.rtk.nmea.replay

import com.agroguide.rtk.nmea.FixQuality
import kotlin.math.cos
import kotlin.math.PI

/**
 * Deterministic NMEA source for demo mode and tests: drives a point at
 * [speedMps] on [headingDeg] from the start position, emitting GGA+RMC+VTG
 * per epoch. Heading can be changed live (demo UI turns the "tractor").
 */
class SyntheticNmeaGenerator(
    startLatDeg: Double,
    startLonDeg: Double,
    var speedMps: Double = 3.0,
    var headingDeg: Double = 0.0,
    var quality: FixQuality = FixQuality.RTK_FIXED,
    private val epochIntervalS: Double = 1.0,
) {
    private var lat = startLatDeg
    private var lon = startLonDeg
    private var utcS = 12 * 3600.0
    private var emittedFirst = false

    fun next(): List<String> {
        if (emittedFirst) {
            val dNorth = speedMps * epochIntervalS * cos(headingDeg * PI / 180)
            val dEast = speedMps * epochIntervalS * kotlin.math.sin(headingDeg * PI / 180)
            lat += dNorth / 111_320.0
            lon += dEast / (111_320.0 * cos(lat * PI / 180))
            utcS += epochIntervalS
        }
        emittedFirst = true
        val time = utcTime()
        val latN = nmeaLat(lat); val lonN = nmeaLon(lon)
        val speedKn = speedMps / 0.514444
        val gga = cs("\$GNGGA,$time,${latN.first},${latN.second},${lonN.first},${lonN.second}," +
            "${quality.code},12,0.60,500.0,M,46.9,M,${if (quality.code >= 2) "1.0" else ""},0000")
        val rmc = cs("\$GNRMC,$time,A,${latN.first},${latN.second},${lonN.first},${lonN.second}," +
            "${"%.1f".format(speedKn)},${"%.1f".format(headingDeg)},010126,,,R,V")
        val vtg = cs("\$GNVTG,${"%.1f".format(headingDeg)},T,,M,${"%.1f".format(speedKn)},N," +
            "${"%.1f".format(speedKn * 1.852)},K,D")
        return listOf(gga, rmc, vtg)
    }

    private fun utcTime(): String {
        val h = (utcS / 3600).toInt() % 24; val m = ((utcS % 3600) / 60).toInt()
        val s = utcS % 60
        return "%02d%02d%05.2f".format(h, m, s)
    }
    private fun nmeaLat(d: Double): Pair<String, String> {
        val a = kotlin.math.abs(d); val deg = a.toInt(); val min = (a - deg) * 60
        return "%02d%08.5f".format(deg, min) to if (d < 0) "S" else "N"
    }
    private fun nmeaLon(d: Double): Pair<String, String> {
        val a = kotlin.math.abs(d); val deg = a.toInt(); val min = (a - deg) * 60
        return "%03d%08.5f".format(deg, min) to if (d < 0) "W" else "E"
    }
    private fun cs(body: String): String {
        var x = 0; for (c in body.drop(1)) x = x xor c.code
        return body + "*" + x.toString(16).uppercase().padStart(2, '0')
    }
}
```
IMPORTANT: `"%.2f"`-style formatting must be locale-independent — use `String.format(java.util.Locale.US, ...)`. Replace every `"...".format(...)` above with `String.format(java.util.Locale.US, "...", ...)` (a Spanish system locale would emit `0,60` and corrupt sentences). Add a test:
```kotlin
    @Test fun `sentences use dot decimal separator regardless of default locale`() {
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("es", "ES"))
            val gen = SyntheticNmeaGenerator(42.0, -5.0)
            assertFalse(gen.next()[0].contains(','.toString() + "60")) // no "0,60"
            assertNotNull(com.agroguide.rtk.nmea.NmeaParser.parse(gen.next()[0]))
        } finally { java.util.Locale.setDefault(prev) }
    }
```

- [ ] **Step 4: Run tests, verify pass** — `.\gradlew.bat :core:nmea:test` → green.

- [ ] **Step 5: Commit** — `git add core/nmea; git commit -m "feat(nmea): paced replayer and synthetic NMEA generator for demo mode"`

---

### Task 5: `:core:geo` — ENU tangent plane (TDD)

**Files:**
- Create: `core/geo/src/main/kotlin/com/agroguide/rtk/geo/Enu.kt`
- Test: `core/geo/src/test/kotlin/com/agroguide/rtk/geo/EnuTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.agroguide.rtk.geo

import kotlin.math.hypot
import kotlin.test.*

class EnuTest {
    @Test fun `origin maps to 0,0`() {
        val p = TangentPlane(42.0, -5.0)
        val e = p.toEnu(42.0, -5.0)
        assertEquals(0.0, e.x, 1e-9); assertEquals(0.0, e.y, 1e-9)
    }

    @Test fun `one degree latitude north is about 111 km`() {
        val p = TangentPlane(42.0, -5.0)
        val e = p.toEnu(43.0, -5.0)
        assertEquals(0.0, e.x, 1.0)                 // no east component
        assertEquals(111_000.0, e.y, 1_000.0)       // ~110.9 km at 42N
    }

    @Test fun `east displacement shrinks with cos(lat)`() {
        val p = TangentPlane(60.0, 0.0)
        val e = p.toEnu(60.0, 0.001)
        // 0.001 deg lon at 60N ≈ 111320*cos(60)*0.001 ≈ 55.7 m
        assertEquals(55.7, e.x, 0.5)
        assertEquals(0.0, e.y, 0.1)
    }

    @Test fun `roundtrip toGeo inverts toEnu within 1 mm over 2 km`() {
        val p = TangentPlane(42.123456, -5.654321)
        val e = p.toEnu(42.13, -5.63)
        val (lat, lon) = p.toGeo(e.x, e.y)
        val back = p.toEnu(lat, lon)
        assertTrue(hypot(back.x - e.x, back.y - e.y) < 0.001)
    }

    @Test fun `small offsets are metrically accurate against Vincenty-style truth`() {
        // 100 m north of 42N should be 100/110852.4 deg ≈ 0.00090211 deg (WGS84 meridian radius at 42deg)
        val p = TangentPlane(42.0, -5.0)
        val e = p.toEnu(42.0 + 100.0 / 110_852.4, -5.0)
        assertEquals(100.0, e.y, 0.01)
    }
}
```

- [ ] **Step 2: Run, verify fail.** `.\gradlew.bat :core:geo:test`

- [ ] **Step 3: Implement `Enu.kt`**

```kotlin
package com.agroguide.rtk.geo

import kotlin.math.*

data class EnuPoint(val x: Double, val y: Double)  // east, north (meters)

/**
 * Local East-North tangent plane on the WGS84 ellipsoid, anchored at an origin
 * that is recomputed per job. Uses per-origin meridian (M) and prime-vertical (N)
 * radii of curvature, which is millimeter-accurate over the few-km extent of a field.
 * All guidance math operates on these coordinates — never on raw lat/lon.
 */
class TangentPlane(val originLatDeg: Double, val originLonDeg: Double) {
    private val a = 6_378_137.0
    private val e2 = 6.69437999014e-3
    private val lat0 = originLatDeg * PI / 180
    private val sinLat0 = sin(lat0)
    private val w = sqrt(1 - e2 * sinLat0 * sinLat0)
    /** Meridian radius of curvature at origin (north scale). */
    private val mRad = a * (1 - e2) / (w * w * w)
    /** Prime vertical radius (east scale, includes cos(lat)). */
    private val nRad = a / w * cos(lat0)

    fun toEnu(latDeg: Double, lonDeg: Double): EnuPoint = EnuPoint(
        x = (lonDeg - originLonDeg) * PI / 180 * nRad,
        y = (latDeg - originLatDeg) * PI / 180 * mRad,
    )

    fun toGeo(x: Double, y: Double): Pair<Double, Double> = Pair(
        originLatDeg + y / mRad * 180 / PI,
        originLonDeg + x / nRad * 180 / PI,
    )
}
```

- [ ] **Step 4: Run tests, verify pass.**

- [ ] **Step 5: Commit** — `git add core/geo; git commit -m "feat(geo): WGS84 local ENU tangent plane"`

---

### Task 6: `:core:geo` — VehicleState, jump filter, constant-velocity projector (TDD)

**Files:**
- Create: `core/geo/src/main/kotlin/com/agroguide/rtk/geo/VehicleState.kt`
- Create: `core/geo/src/main/kotlin/com/agroguide/rtk/geo/JumpFilter.kt`
- Create: `core/geo/src/main/kotlin/com/agroguide/rtk/geo/CvProjector.kt`
- Test: `core/geo/src/test/kotlin/com/agroguide/rtk/geo/JumpFilterTest.kt`
- Test: `core/geo/src/test/kotlin/com/agroguide/rtk/geo/CvProjectorTest.kt`

- [ ] **Step 1: Define `VehicleState.kt`** (no test — pure data)

```kotlin
package com.agroguide.rtk.geo

/** Fix quality mirrored here so :core:geo stays independent of :core:nmea. */
enum class GnssQuality { NO_FIX, GPS, DGPS, RTK_FLOAT, RTK_FIXED, DEAD_RECKONING }

/**
 * The single source of truth consumed by guidance, coverage and UI.
 * Position is ENU meters on the active job's tangent plane; lat/lon kept
 * for display and export only.
 */
data class VehicleState(
    val x: Double, val y: Double,            // ENU antenna position
    val latDeg: Double, val lonDeg: Double,
    val headingDeg: Double,                  // course over ground, true
    val speedMps: Double,
    val quality: GnssQuality,
    val satellites: Int?,
    val hdop: Double?,
    val correctionAgeS: Double?,
    val fixMonotonicMs: Long,                // when the underlying fix arrived
    val projected: Boolean,                  // true if CV-extrapolated, not a raw fix
)
```

- [ ] **Step 2: Write failing tests**

`JumpFilterTest.kt`:
```kotlin
package com.agroguide.rtk.geo

import kotlin.test.*

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
        assertFalse(f.accept(EnuPoint(0.0, 500.0), 2.0, 1000))   // 500 m jump: reject
        assertFalse(f.accept(EnuPoint(0.0, 501.0), 2.0, 2000))   // still far: reject (1)
        assertFalse(f.accept(EnuPoint(0.0, 502.0), 2.0, 3000))   // reject (2)
        assertTrue(f.accept(EnuPoint(0.0, 503.0), 2.0, 4000))    // 3rd consistent → accept new region
    }

    @Test fun `stationary jitter within margin passes`() {
        f.accept(EnuPoint(0.0, 0.0), 0.0, 0)
        assertTrue(f.accept(EnuPoint(0.3, -0.2), 0.0, 1000))
    }

    @Test fun `long gap widens the gate`() {
        f.accept(EnuPoint(0.0, 0.0), 5.0, 0)
        // 30 s without fixes at 5 m/s → 150 m is plausible
        assertTrue(f.accept(EnuPoint(0.0, 150.0), 5.0, 30_000))
    }
}
```

`CvProjectorTest.kt`:
```kotlin
package com.agroguide.rtk.geo

import kotlin.test.*

class CvProjectorTest {
    private fun state(x: Double, y: Double, headingDeg: Double, speed: Double, tMs: Long) = VehicleState(
        x = x, y = y, latDeg = 0.0, lonDeg = 0.0, headingDeg = headingDeg, speedMps = speed,
        quality = GnssQuality.RTK_FIXED, satellites = 12, hdop = 0.6, correctionAgeS = 1.0,
        fixMonotonicMs = tMs, projected = false,
    )

    @Test fun `projects along heading at constant speed`() {
        val p = CvProjector()
        p.onFix(state(0.0, 0.0, headingDeg = 90.0, speed = 2.0, tMs = 0))
        val s = p.projectAt(500)!!   // 0.5 s later, heading east
        assertEquals(1.0, s.x, 1e-9)
        assertEquals(0.0, s.y, 1e-9)
        assertTrue(s.projected)
    }

    @Test fun `does not extrapolate beyond cap`() {
        val p = CvProjector(maxExtrapolationMs = 2000)
        p.onFix(state(0.0, 0.0, 0.0, 5.0, 0))
        val s = p.projectAt(10_000)!!
        assertEquals(10.0, s.y, 1e-9)  // frozen at cap: 5 m/s * 2 s
    }

    @Test fun `fresh fix resets projection base`() {
        val p = CvProjector()
        p.onFix(state(0.0, 0.0, 0.0, 5.0, 0))
        p.onFix(state(0.0, 4.9, 0.0, 5.0, 1000))
        val s = p.projectAt(1200)!!
        assertEquals(5.9, s.y, 1e-9)
    }

    @Test fun `null before any fix`() {
        assertNull(CvProjector().projectAt(100))
    }
}
```

- [ ] **Step 3: Run, verify fail.**

- [ ] **Step 4: Implement**

`JumpFilter.kt`:
```kotlin
package com.agroguide.rtk.geo

import kotlin.math.hypot
import kotlin.math.max

/**
 * Speed-gated outlier rejection. A fix is accepted when its displacement from
 * the last accepted position is explainable by (reported speed + margin) over
 * the elapsed time. After [persistToRelocate] consecutive rejections that are
 * mutually consistent, the filter relocates (real long jump, e.g. receiver
 * cold start or transport in a vehicle).
 */
class JumpFilter(
    private val marginMps: Double = 3.0,
    private val gateFactor: Double = 2.0,
    private val persistToRelocate: Int = 4,
) {
    private var last: EnuPoint? = null
    private var lastMs: Long = 0
    private var rejectAnchor: EnuPoint? = null
    private var rejectCount = 0

    fun accept(p: EnuPoint, speedMps: Double, monotonicMs: Long): Boolean {
        val prev = last ?: return acceptInternal(p, monotonicMs)
        val dtS = max((monotonicMs - lastMs) / 1000.0, 0.05)
        val allowed = (speedMps + marginMps) * dtS * gateFactor + 1.0  // +1 m jitter floor
        val d = hypot(p.x - prev.x, p.y - prev.y)
        if (d <= allowed) return acceptInternal(p, monotonicMs)

        // Rejected: track whether the "new place" is self-consistent.
        val anchor = rejectAnchor
        if (anchor != null && hypot(p.x - anchor.x, p.y - anchor.y) < 25.0) {
            rejectCount++
            if (rejectCount >= persistToRelocate) return acceptInternal(p, monotonicMs)
        } else {
            rejectAnchor = p; rejectCount = 1
        }
        return false
    }

    private fun acceptInternal(p: EnuPoint, ms: Long): Boolean {
        last = p; lastMs = ms; rejectAnchor = null; rejectCount = 0
        return true
    }
}
```
Persistence walk-through against the test: sample at y=500 sets the anchor (count=1, rejected); 501→count=2 (rejected); 502→count=3 (rejected); 503→count=4 ≥ persistToRelocate(4) → accepted and the filter relocates. Note the anchor-branch must count the anchor sample itself but NOT accept on it — only the `rejectCount++` branch can trigger relocation.

`CvProjector.kt`:
```kotlin
package com.agroguide.rtk.geo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Constant-velocity dead reckoning between GNSS fixes so guidance and render
 * stay smooth at 1-5 Hz input. Extrapolation is capped: beyond the cap the
 * position freezes (stale data must be visible, not invented).
 */
class CvProjector(private val maxExtrapolationMs: Long = 2000) {
    private var base: VehicleState? = null

    fun onFix(s: VehicleState) { base = s.copy(projected = false) }

    fun projectAt(nowMonotonicMs: Long): VehicleState? {
        val b = base ?: return null
        val dtMs = min(nowMonotonicMs - b.fixMonotonicMs, maxExtrapolationMs).coerceAtLeast(0)
        if (dtMs == 0L) return b
        val dt = dtMs / 1000.0
        val rad = b.headingDeg * PI / 180
        return b.copy(
            x = b.x + b.speedMps * dt * sin(rad),
            y = b.y + b.speedMps * dt * cos(rad),
            projected = true,
        )
    }
}
```

- [ ] **Step 5: Run tests, verify pass.** `.\gradlew.bat :core:geo:test` → green.

- [ ] **Step 6: Commit** — `git add core/geo; git commit -m "feat(geo): vehicle state, speed-gated jump filter, constant-velocity projector"`

---

### Task 7: `:device` — PositionSource contract + pipeline + replay/demo source

**Files:**
- Create: `device/src/main/java/com/agroguide/rtk/device/PositionSource.kt`
- Create: `device/src/main/java/com/agroguide/rtk/device/PositionPipeline.kt`
- Create: `device/src/main/java/com/agroguide/rtk/device/DemoPositionSource.kt`
- Test: `device/src/test/java/com/agroguide/rtk/device/PositionPipelineTest.kt`

- [ ] **Step 1: Contract** (`PositionSource.kt`)

```kotlin
package com.agroguide.rtk.device

import kotlinx.coroutines.flow.Flow

sealed interface SourceEvent {
    /** A raw NMEA line (BT/replay sources) with local monotonic receive time. */
    data class Nmea(val line: String, val monotonicMs: Long) : SourceEvent
    /** A ready-made fix (Android Location source bypasses NMEA). */
    data class DirectFix(val fix: com.agroguide.rtk.nmea.GnssFix) : SourceEvent
    data class ConnectionState(val state: State, val detail: String? = null) : SourceEvent {
        enum class State { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }
}

/** One position provider (BT SPP, Android location, replay, later USB). */
interface PositionSource {
    val events: Flow<SourceEvent>
    val name: String
}
```

- [ ] **Step 2: Failing pipeline test** (`PositionPipelineTest.kt`)

```kotlin
package com.agroguide.rtk.device

import com.agroguide.rtk.geo.GnssQuality
import com.agroguide.rtk.nmea.replay.SyntheticNmeaGenerator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PositionPipelineTest {
    @Test fun `NMEA lines become VehicleStates on a tangent plane`() = runTest {
        val gen = SyntheticNmeaGenerator(42.0, -5.0, speedMps = 4.0, headingDeg = 90.0)
        val lines = (0 until 5).flatMap { gen.next() }
        val source = object : PositionSource {
            override val name = "test"
            override val events = lines.mapIndexed { i, l ->
                SourceEvent.Nmea(l, monotonicMs = (i / 3) * 1000L)
            }.asFlow()
        }
        val pipeline = PositionPipeline(source)
        val states = pipeline.states.toList()
        assertEquals(5, states.size)
        assertEquals(GnssQuality.RTK_FIXED, states.first().quality)
        // heading east at 4 m/s → x advances ~4 m per epoch, y stays ~0
        assertEquals(4.0, states[1].x - states[0].x, 0.1)
        assertEquals(0.0, states[1].y - states[0].y, 0.1)
        assertEquals(4.0, states[1].speedMps, 0.1)
    }

    @Test fun `outlier fix is dropped`() = runTest {
        val gen = SyntheticNmeaGenerator(42.0, -5.0, speedMps = 2.0, headingDeg = 0.0)
        val good = (0 until 3).flatMap { gen.next() }
        val jumper = SyntheticNmeaGenerator(42.1, -5.0, speedMps = 2.0)  // 11 km away
        val bad = jumper.next()
        val lines = good.take(6) + bad + good.drop(6)
        val source = object : PositionSource {
            override val name = "test"
            override val events = lines.mapIndexed { i, l -> SourceEvent.Nmea(l, i * 300L) }.asFlow()
        }
        val states = PositionPipeline(source).states.toList()
        assertEquals(3, states.size)  // 3 good epochs; outlier epoch dropped
        assertTrue(states.all { kotlin.math.abs(it.latDeg - 42.0) < 0.01 })
    }
}
```

- [ ] **Step 3: Run, verify fail.** `.\gradlew.bat :device:test`

- [ ] **Step 4: Implement `PositionPipeline.kt`**

```kotlin
package com.agroguide.rtk.device

import com.agroguide.rtk.geo.*
import com.agroguide.rtk.nmea.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * source events → parse → aggregate → tangent plane (auto-anchored on first
 * fix) → jump filter → VehicleState. One instance per source session; the
 * tangent-plane origin is replaced when a job supplies its own.
 */
class PositionPipeline(
    private val source: PositionSource,
    @Volatile var plane: TangentPlane? = null,
) {
    private val aggregator = FixAggregator()
    private val jumpFilter = JumpFilter()

    val states: Flow<VehicleState> = source.events.transform { ev ->
        val fix: GnssFix = when (ev) {
            is SourceEvent.Nmea -> {
                val sentence = NmeaParser.parse(ev.line) ?: return@transform
                aggregator.onSentence(sentence, ev.monotonicMs) ?: return@transform
            }
            is SourceEvent.DirectFix -> ev.fix
            is SourceEvent.ConnectionState -> return@transform
        }
        if (fix.quality == FixQuality.NO_FIX) return@transform
        val p = plane ?: TangentPlane(fix.latDeg, fix.lonDeg).also { plane = it }
        val enu = p.toEnu(fix.latDeg, fix.lonDeg)
        val speed = fix.speedMps ?: 0.0
        if (!jumpFilter.accept(enu, speed, fix.monotonicMs)) return@transform
        emit(
            VehicleState(
                x = enu.x, y = enu.y, latDeg = fix.latDeg, lonDeg = fix.lonDeg,
                headingDeg = fix.courseDeg ?: 0.0, speedMps = speed,
                quality = fix.quality.toGnssQuality(), satellites = fix.satellites,
                hdop = fix.hdop, correctionAgeS = fix.correctionAgeS,
                fixMonotonicMs = fix.monotonicMs, projected = false,
            )
        )
    }
}

fun FixQuality.toGnssQuality(): GnssQuality = when (this) {
    FixQuality.NO_FIX -> GnssQuality.NO_FIX
    FixQuality.GPS, FixQuality.PPS -> GnssQuality.GPS
    FixQuality.DGPS -> GnssQuality.DGPS
    FixQuality.RTK_FIXED -> GnssQuality.RTK_FIXED
    FixQuality.RTK_FLOAT -> GnssQuality.RTK_FLOAT
    FixQuality.DEAD_RECKONING -> GnssQuality.DEAD_RECKONING
}
```

- [ ] **Step 5: Implement `DemoPositionSource.kt`** (no unit test — thin wiring over tested parts; exercised by the in-app demo)

```kotlin
package com.agroguide.rtk.device

import android.os.SystemClock
import com.agroguide.rtk.nmea.FixQuality
import com.agroguide.rtk.nmea.replay.SyntheticNmeaGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Demo mode: synthetic tractor near Leon, Spain, driving at [speedMps].
 * The UI can mutate [generator] fields (heading/speed/quality) live.
 */
class DemoPositionSource(
    rateHz: Double = 5.0,
    val generator: SyntheticNmeaGenerator = SyntheticNmeaGenerator(
        startLatDeg = 42.598, startLonDeg = -5.567,
        speedMps = 3.0, headingDeg = 0.0, quality = FixQuality.RTK_FIXED,
        epochIntervalS = 1.0 / rateHz,
    ),
) : PositionSource {
    override val name = "Demo"
    private val periodMs = (1000.0 / rateHz).toLong()

    override val events: Flow<SourceEvent> = flow {
        emit(SourceEvent.ConnectionState(SourceEvent.ConnectionState.State.CONNECTED, "demo"))
        while (true) {
            val now = SystemClock.elapsedRealtime()
            for (line in generator.next()) emit(SourceEvent.Nmea(line, now))
            delay(periodMs)
        }
    }
}
```

- [ ] **Step 6: Run device tests** — `.\gradlew.bat :device:test` → green (pipeline tests; DemoPositionSource compiles).

- [ ] **Step 7: Commit** — `git add device; git commit -m "feat(device): PositionSource contract, parse/filter pipeline, demo source"`

---

### Task 8: `:device` — Bluetooth SPP source (watchdog + backoff) and Android Location source

**Files:**
- Create: `device/src/main/java/com/agroguide/rtk/device/bt/BtSppPositionSource.kt`
- Create: `device/src/main/java/com/agroguide/rtk/device/bt/ReconnectPolicy.kt`
- Create: `device/src/main/java/com/agroguide/rtk/device/AndroidLocationSource.kt`
- Create: `device/src/main/AndroidManifest.xml` (BT permissions)
- Test: `device/src/test/java/com/agroguide/rtk/device/bt/ReconnectPolicyTest.kt`

- [ ] **Step 1: Failing test for the reconnect/backoff policy** (the only part unit-testable without a device)

```kotlin
package com.agroguide.rtk.device.bt

import kotlin.test.*

class ReconnectPolicyTest {
    @Test fun `backoff doubles from 1s and caps at 60s`() {
        val p = ReconnectPolicy()
        assertEquals(1000L, p.nextDelayMs())
        assertEquals(2000L, p.nextDelayMs())
        assertEquals(4000L, p.nextDelayMs())
        repeat(10) { p.nextDelayMs() }
        assertEquals(60_000L, p.nextDelayMs())
    }

    @Test fun `success resets backoff`() {
        val p = ReconnectPolicy()
        p.nextDelayMs(); p.nextDelayMs()
        p.reset()
        assertEquals(1000L, p.nextDelayMs())
    }
}
```

- [ ] **Step 2: Run, verify fail; implement `ReconnectPolicy.kt`**

```kotlin
package com.agroguide.rtk.device.bt

/** Exponential backoff: 1s, 2s, 4s ... capped at 60s. reset() after a healthy connection. */
class ReconnectPolicy(private val baseMs: Long = 1000, private val capMs: Long = 60_000) {
    private var attempt = 0
    fun nextDelayMs(): Long {
        val d = (baseMs shl attempt.coerceAtMost(6)).coerceAtMost(capMs)
        attempt++
        return d
    }
    fun reset() { attempt = 0 }
}
```
Run: `.\gradlew.bat :device:test` → green.

- [ ] **Step 3: Implement `BtSppPositionSource.kt`** (integration code; manual-tested with hardware, structure reviewed here)

```kotlin
package com.agroguide.rtk.device.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import com.agroguide.rtk.device.PositionSource
import com.agroguide.rtk.device.SourceEvent
import com.agroguide.rtk.device.SourceEvent.ConnectionState.State
import com.agroguide.rtk.nmea.NmeaLineAssembler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Owns the RFCOMM socket to the RTK receiver. Emits NMEA lines; exposes
 * [write] so the NTRIP client (phase 2) can push RTCM3 down the same link.
 * Watchdog: if no NMEA for [watchdogMs], the socket is torn down and the
 * reconnect loop takes over with exponential backoff.
 */
@SuppressLint("MissingPermission")  // caller must hold BLUETOOTH_CONNECT
class BtSppPositionSource(
    private val deviceAddress: String,
    private val watchdogMs: Long = 5000,
) : PositionSource {
    override val name = "BT-SPP"
    @Volatile private var output: OutputStream? = null
    private val policy = ReconnectPolicy()

    /** Write RTCM/UBX bytes to the receiver; no-op if disconnected. */
    fun write(bytes: ByteArray) {
        try { output?.write(bytes) } catch (_: IOException) { /* reconnect loop handles it */ }
    }

    override val events: Flow<SourceEvent> = channelFlow {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: run { send(SourceEvent.ConnectionState(State.ERROR, "no_bluetooth")); return@channelFlow }
        while (isActive) {
            send(SourceEvent.ConnectionState(State.CONNECTING))
            var socket: BluetoothSocket? = null
            try {
                val device = adapter.getRemoteDevice(deviceAddress)
                adapter.cancelDiscovery()
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                output = socket.outputStream
                send(SourceEvent.ConnectionState(State.CONNECTED, deviceAddress))
                policy.reset()
                readUntilStale(socket)
            } catch (e: IOException) {
                send(SourceEvent.ConnectionState(State.ERROR, e.message))
            } finally {
                output = null
                try { socket?.close() } catch (_: IOException) {}
            }
            if (!isActive) break
            send(SourceEvent.ConnectionState(State.DISCONNECTED))
            delay(policy.nextDelayMs())
        }
    }

    /** Blocking read loop on IO dispatcher; returns when watchdog fires or stream ends. */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<SourceEvent>.readUntilStale(
        socket: BluetoothSocket,
    ) = withContext(Dispatchers.IO) {
        val assembler = NmeaLineAssembler()
        val buf = ByteArray(1024)
        var lastNmeaMs = SystemClock.elapsedRealtime()
        val input = socket.inputStream
        val watchdog = launch {
            while (isActive) {
                delay(1000)
                if (SystemClock.elapsedRealtime() - lastNmeaMs > watchdogMs) {
                    try { socket.close() } catch (_: IOException) {}  // unblocks read()
                    break
                }
            }
        }
        try {
            while (isActive) {
                val n = input.read(buf)
                if (n < 0) break
                val now = SystemClock.elapsedRealtime()
                for (line in assembler.feed(buf.copyOf(n))) {
                    lastNmeaMs = now
                    send(SourceEvent.Nmea(line, now))
                }
            }
        } catch (_: IOException) { /* socket closed by watchdog or peer */ }
        finally { watchdog.cancel() }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
```

- [ ] **Step 4: Implement `AndroidLocationSource.kt`**

```kotlin
package com.agroguide.rtk.device

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.agroguide.rtk.nmea.FixQuality
import com.agroguide.rtk.nmea.GnssFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Mode 2: standard Android location (GPS provider), which is what external
 * mock providers (GNSS Master etc.) feed. Emits DirectFix — no NMEA parsing.
 * Quality is approximated from accuracy since the framework hides RTK state:
 * <0.1 m → RTK_FIXED, <0.5 m → RTK_FLOAT, <2 m → DGPS, else GPS.
 */
@SuppressLint("MissingPermission")  // caller must hold ACCESS_FINE_LOCATION
class AndroidLocationSource(context: Context, private val intervalMs: Long = 200) : PositionSource {
    override val name = "Android"
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override val events: Flow<SourceEvent> = callbackFlow {
        val listener = LocationListener { loc: Location ->
            trySend(SourceEvent.DirectFix(loc.toGnssFix()))
        }
        trySend(SourceEvent.ConnectionState(SourceEvent.ConnectionState.State.CONNECTED, "gps"))
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(listener) }
    }

    private fun Location.toGnssFix(): GnssFix {
        val acc = if (hasAccuracy()) accuracy.toDouble() else Double.MAX_VALUE
        val q = when {
            acc < 0.1 -> FixQuality.RTK_FIXED
            acc < 0.5 -> FixQuality.RTK_FLOAT
            acc < 2.0 -> FixQuality.DGPS
            else -> FixQuality.GPS
        }
        return GnssFix(
            latDeg = latitude, lonDeg = longitude, altitudeM = if (hasAltitude()) altitude else null,
            quality = q, satellites = extras?.getInt("satellites"), satsInView = null,
            hdop = null, correctionAgeS = null,
            speedMps = if (hasSpeed()) speed.toDouble() else null,
            courseDeg = if (hasBearing()) bearing.toDouble() else null,
            sigmaLatM = if (hasAccuracy()) acc else null, sigmaLonM = if (hasAccuracy()) acc else null,
            timeUtc = null, monotonicMs = SystemClock.elapsedRealtime(),
        )
    }
}
```

- [ ] **Step 5: `device/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
</manifest>
```

- [ ] **Step 6: Build + tests** — `.\gradlew.bat :device:test :device:assembleDebug` → green.

- [ ] **Step 7: Commit** — `git add device; git commit -m "feat(device): BT SPP source with watchdog/backoff, Android location source"`

---

### Task 9: `:service` — foreground service + PositionRepository

**Files:**
- Create: `service/src/main/java/com/agroguide/rtk/service/PositionRepository.kt`
- Create: `service/src/main/java/com/agroguide/rtk/service/GuidanceService.kt`
- Create: `service/src/main/AndroidManifest.xml`

- [ ] **Step 1: `PositionRepository.kt`** (process-wide singleton the UI observes; service writes)

```kotlin
package com.agroguide.rtk.service

import com.agroguide.rtk.device.*
import com.agroguide.rtk.geo.CvProjector
import com.agroguide.rtk.geo.VehicleState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Single source of truth for the app: latest VehicleState (render-paced via
 * CvProjector at [renderHz]) and connection state. The foreground service
 * drives it; Compose collects it.
 */
object PositionRepository {
    private val _vehicle = MutableStateFlow<VehicleState?>(null)
    val vehicle: StateFlow<VehicleState?> = _vehicle.asStateFlow()

    private val _connection = MutableStateFlow(
        SourceEvent.ConnectionState(SourceEvent.ConnectionState.State.DISCONNECTED)
    )
    val connection: StateFlow<SourceEvent.ConnectionState> = _connection.asStateFlow()

    private val _sourceName = MutableStateFlow<String?>(null)
    val sourceName: StateFlow<String?> = _sourceName.asStateFlow()

    private var scope: CoroutineScope? = null
    private const val RENDER_HZ = 15

    /** Called by GuidanceService when a source is (re)selected. */
    fun attach(source: PositionSource, nowMs: () -> Long) {
        detach()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        _sourceName.value = source.name
        val pipeline = PositionPipeline(source)
        val projector = CvProjector()
        s.launch {
            source.events.filterIsInstance<SourceEvent.ConnectionState>()
                .collect { _connection.value = it }
        }
        s.launch { pipeline.states.collect { projector.onFix(it); _vehicle.value = it } }
        s.launch {
            while (isActive) {
                delay(1000L / RENDER_HZ)
                projector.projectAt(nowMs())?.let { if (it.projected) _vehicle.value = it }
            }
        }
    }

    fun detach() {
        scope?.cancel(); scope = null
        _sourceName.value = null
        _connection.value = SourceEvent.ConnectionState(SourceEvent.ConnectionState.State.DISCONNECTED)
    }
}
```
(Note: `attach` launches `source.events` collection twice — once inside PositionPipeline and once for connection state. `PositionSource.events` implementations MUST therefore be cold flows safe to collect twice, OR share. **Fix in implementation: share the source once:** `val shared = source.events.shareIn(s, SharingStarted.Eagerly, replay = 0)` and build the pipeline on a `PositionSource` wrapper exposing `shared`. Implement it that way — a BT socket must not be opened twice.)

```kotlin
// Inside attach(), replacing the two launches above:
val shared = source.events.shareIn(s, SharingStarted.Eagerly)
val sharedSource = object : PositionSource {
    override val name = source.name
    override val events = shared
}
val pipeline = PositionPipeline(sharedSource)
s.launch { shared.filterIsInstance<SourceEvent.ConnectionState>().collect { _connection.value = it } }
s.launch { pipeline.states.collect { projector.onFix(it); _vehicle.value = it } }
```

- [ ] **Step 2: `GuidanceService.kt`**

```kotlin
package com.agroguide.rtk.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.agroguide.rtk.device.AndroidLocationSource
import com.agroguide.rtk.device.DemoPositionSource
import com.agroguide.rtk.device.PositionSource
import com.agroguide.rtk.device.bt.BtSppPositionSource

/**
 * Foreground service that owns the position source (and, in later phases,
 * the NTRIP session and job recorder). Started with an EXTRA_MODE of
 * demo|bt|android; bt also needs EXTRA_BT_ADDRESS.
 */
class GuidanceService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground()
        val source: PositionSource = when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_BT -> BtSppPositionSource(intent.getStringExtra(EXTRA_BT_ADDRESS) ?: return START_NOT_STICKY)
            MODE_ANDROID -> AndroidLocationSource(this)
            else -> DemoPositionSource()
        }
        PositionRepository.attach(source) { SystemClock.elapsedRealtime() }
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        PositionRepository.detach()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Guiado", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AgroGuide RTK")
            .setContentText("Guiado activo")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agroguide:guidance")
        }
        wakeLock?.let { if (!it.isHeld) it.acquire(12 * 60 * 60 * 1000L) }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_BT_ADDRESS = "bt_address"
        const val MODE_DEMO = "demo"; const val MODE_BT = "bt"; const val MODE_ANDROID = "android"
        private const val CHANNEL_ID = "guidance"; private const val NOTIF_ID = 1

        fun start(context: Context, mode: String, btAddress: String? = null) {
            val i = Intent(context, GuidanceService::class.java)
                .putExtra(EXTRA_MODE, mode).putExtra(EXTRA_BT_ADDRESS, btAddress)
            context.startForegroundService(i)
        }
        fun stop(context: Context) = context.stopService(Intent(context, GuidanceService::class.java))
    }
}
```

- [ ] **Step 3: `service/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application>
        <service
            android:name="com.agroguide.rtk.service.GuidanceService"
            android:foregroundServiceType="location|connectedDevice"
            android:exported="false" />
    </application>
</manifest>
```
Also add to `service/build.gradle.kts` dependencies: `implementation("androidx.core:core-ktx:1.16.0")` via catalog (`libs.androidx.core.ktx`) and `implementation(libs.androidx.lifecycle.service)`.

- [ ] **Step 4: Build** — `.\gradlew.bat :service:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** — `git add service; git commit -m "feat(service): foreground guidance service with wake lock and position repository"`

---

### Task 10: `:app` — fix-status panel UI + source selection + permissions

**Files:**
- Create: `app/src/main/java/com/agroguide/rtk/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/agroguide/rtk/ui/FixPanel.kt`
- Create: `app/src/main/java/com/agroguide/rtk/ui/HomeScreen.kt`
- Modify: `app/src/main/java/com/agroguide/rtk/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml` (POST_NOTIFICATIONS request happens at runtime; BT device listing needs BLUETOOTH_CONNECT)

- [ ] **Step 1: Theme** (`Theme.kt`) — high-contrast day scheme now; night in phase 6.

```kotlin
package com.agroguide.rtk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DayColors = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color.White,
    surface = Color(0xFFF5F5F0),
    onSurface = Color(0xFF101010),
    background = Color(0xFFECECE4),
    onBackground = Color(0xFF101010),
)

@Composable
fun AgroGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DayColors, content = content)
}
```

- [ ] **Step 2: Strings** (`strings.xml`, Spanish default)

```xml
<resources>
    <string name="app_name">AgroGuide RTK</string>
    <string name="fix_no_fix">SIN FIX</string>
    <string name="fix_gps">GPS</string>
    <string name="fix_dgps">DGPS</string>
    <string name="fix_rtk_float">RTK FLOAT</string>
    <string name="fix_rtk_fixed">RTK FIJO</string>
    <string name="fix_dead_reckoning">ESTIMA</string>
    <string name="label_satellites">Satélites</string>
    <string name="label_hdop">HDOP</string>
    <string name="label_corr_age">Edad corr.</string>
    <string name="label_speed">Velocidad</string>
    <string name="unit_kmh">km/h</string>
    <string name="source_demo">Demo (simulado)</string>
    <string name="source_bt">Receptor Bluetooth RTK</string>
    <string name="source_android">Ubicación de Android</string>
    <string name="btn_start">Iniciar</string>
    <string name="btn_stop">Detener</string>
    <string name="state_connecting">Conectando…</string>
    <string name="state_connected">Conectado</string>
    <string name="state_disconnected">Desconectado</string>
    <string name="state_error">Error de conexión</string>
    <string name="perm_rationale">Se necesitan permisos de ubicación y Bluetooth para el guiado.</string>
    <string name="no_paired_rtk">No hay receptores RTK_GNSS emparejados. Empareja el receptor (PIN 1234) en Ajustes de Bluetooth.</string>
</resources>
```

- [ ] **Step 3: `FixPanel.kt`** — color-coded quality chip + stats, ≥56dp targets

```kotlin
package com.agroguide.rtk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agroguide.rtk.R
import com.agroguide.rtk.geo.GnssQuality
import com.agroguide.rtk.geo.VehicleState

@Composable
fun qualityColor(q: GnssQuality): Color = when (q) {
    GnssQuality.RTK_FIXED -> Color(0xFF2E7D32)
    GnssQuality.RTK_FLOAT -> Color(0xFFF9A825)
    GnssQuality.DGPS -> Color(0xFFEF6C00)
    GnssQuality.GPS -> Color(0xFFC62828)
    GnssQuality.DEAD_RECKONING -> Color(0xFF6A1B9A)
    GnssQuality.NO_FIX -> Color(0xFF424242)
}

@Composable
fun qualityLabel(q: GnssQuality): String = stringResource(
    when (q) {
        GnssQuality.RTK_FIXED -> R.string.fix_rtk_fixed
        GnssQuality.RTK_FLOAT -> R.string.fix_rtk_float
        GnssQuality.DGPS -> R.string.fix_dgps
        GnssQuality.GPS -> R.string.fix_gps
        GnssQuality.DEAD_RECKONING -> R.string.fix_dead_reckoning
        GnssQuality.NO_FIX -> R.string.fix_no_fix
    }
)

/** Live fix-quality panel: big colored quality chip + sats/HDOP/corr-age/speed. */
@Composable
fun FixPanel(state: VehicleState?, modifier: Modifier = Modifier) {
    val quality = state?.quality ?: GnssQuality.NO_FIX
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(qualityColor(quality), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(qualityLabel(quality), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        StatRow(stringResource(R.string.label_satellites), state?.satellites?.toString() ?: "—")
        StatRow(stringResource(R.string.label_hdop), state?.hdop?.let { "%.2f".format(it) } ?: "—")
        StatRow(
            stringResource(R.string.label_corr_age),
            state?.correctionAgeS?.let { "%.1f s".format(it) } ?: "—",
        )
        StatRow(
            stringResource(R.string.label_speed),
            state?.let { "%.1f ${stringResource(R.string.unit_kmh)}".format(it.speedMps * 3.6) } ?: "—",
        )
        Text(
            state?.let { "%.7f, %.7f".format(it.latDeg, it.lonDeg) } ?: "—",
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 18.sp)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}
```
(Same locale warning as Task 4: for on-screen text `%.2f` with device locale is fine — Spanish users expect comma decimals in UI. Keep `.format` here.)

- [ ] **Step 4: `HomeScreen.kt`** — source chooser + start/stop + connection banner

```kotlin
package com.agroguide.rtk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agroguide.rtk.R
import com.agroguide.rtk.service.GuidanceService
import com.agroguide.rtk.service.PositionRepository
import com.agroguide.rtk.device.SourceEvent

/**
 * Phase-1 home: pick source (demo / BT / Android location), start/stop the
 * foreground service, watch the fix panel. Field canvas arrives in phase 3.
 */
@Composable
fun HomeScreen(pairedRtkDevices: List<Pair<String, String>>) {  // name to MAC
    val context = LocalContext.current
    val vehicle by PositionRepository.vehicle.collectAsStateWithLifecycle()
    val connection by PositionRepository.connection.collectAsStateWithLifecycle()
    val active by PositionRepository.sourceName.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(GuidanceService.MODE_DEMO) }
    var btAddress by remember { mutableStateOf(pairedRtkDevices.firstOrNull()?.second) }

    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceOption(stringResource(R.string.source_demo), mode == GuidanceService.MODE_DEMO) {
                mode = GuidanceService.MODE_DEMO
            }
            SourceOption(stringResource(R.string.source_bt), mode == GuidanceService.MODE_BT) {
                mode = GuidanceService.MODE_BT
            }
            if (mode == GuidanceService.MODE_BT) {
                if (pairedRtkDevices.isEmpty()) {
                    Text(stringResource(R.string.no_paired_rtk))
                } else {
                    pairedRtkDevices.forEach { (name, mac) ->
                        SourceOption(name, btAddress == mac) { btAddress = mac }
                    }
                }
            }
            SourceOption(stringResource(R.string.source_android), mode == GuidanceService.MODE_ANDROID) {
                mode = GuidanceService.MODE_ANDROID
            }
            Spacer(Modifier.weight(1f))
            ConnectionBanner(connection)
            Button(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                onClick = {
                    if (active == null) GuidanceService.start(context, mode, btAddress)
                    else GuidanceService.stop(context)
                },
                enabled = mode != GuidanceService.MODE_BT || btAddress != null,
            ) {
                Text(
                    stringResource(if (active == null) R.string.btn_start else R.string.btn_stop),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        FixPanel(vehicle, Modifier.weight(1f))
    }
}

@Composable
private fun SourceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ConnectionBanner(c: SourceEvent.ConnectionState) {
    val text = when (c.state) {
        SourceEvent.ConnectionState.State.CONNECTING -> stringResource(R.string.state_connecting)
        SourceEvent.ConnectionState.State.CONNECTED -> stringResource(R.string.state_connected)
        SourceEvent.ConnectionState.State.DISCONNECTED -> stringResource(R.string.state_disconnected)
        SourceEvent.ConnectionState.State.ERROR -> stringResource(R.string.state_error) +
            (c.detail?.let { ": $it" } ?: "")
    }
    Text(text, style = MaterialTheme.typography.bodyLarge)
}
```

- [ ] **Step 5: `MainActivity.kt`** — permissions + paired-device listing + content

```kotlin
package com.agroguide.rtk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import com.agroguide.rtk.ui.HomeScreen
import com.agroguide.rtk.ui.theme.AgroGuideTheme

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsGranted = it.values.all { granted -> granted }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNeededPermissions()
        setContent {
            AgroGuideTheme {
                HomeScreen(pairedRtkDevices = if (permissionsGranted) pairedRtkDevices() else emptyList())
            }
        }
    }

    private fun requestNeededPermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) permissionsGranted = true
        else permissionLauncher.launch(missing.toTypedArray())
    }

    /** Paired devices whose name starts with RTK_GNSS (ArduSimple kit naming). */
    @SuppressLint("MissingPermission")
    private fun pairedRtkDevices(): List<Pair<String, String>> {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return emptyList()
        return try {
            adapter.bondedDevices
                .filter { (it.name ?: "").startsWith("RTK_GNSS", ignoreCase = true) }
                .map { (it.name ?: it.address) to it.address }
        } catch (_: SecurityException) { emptyList() }
    }
}
```

- [ ] **Step 6: Full build + all tests**

Run: `.\gradlew.bat test assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL; unit tests of `:core:geo`, `:core:nmea`, `:device` green; `app-debug.apk` produced.

- [ ] **Step 7: Commit** — `git add -A; git commit -m "feat(app): fix-status panel, source selection, permission flow"`

---

### Task 11: Phase-1 verification & checkpoint report

- [ ] **Step 1: Run the full suite once more from clean** — `.\gradlew.bat clean test assembleDebug --console=plain` → all green.

- [ ] **Step 2: If a device is attached (optional):** `adb install -r app\build\outputs\apk\debug\app-debug.apk`, launch, select "Demo (simulado)", press Iniciar → quality chip shows **RTK FIJO** green, speed ~10.8 km/h, coordinates advancing; screen stays on; notification "Guiado activo" visible; press Detener → panel freezes, state Desconectado.

- [ ] **Step 3: Write checkpoint report to the user (Spanish)** covering: built items, manual test script (demo mode without hardware; BT mode with the ArduSimple kit: pair PIN 1234 → select receiver → Iniciar; watchdog test: power off receiver → alert within ~5 s and reconnection attempts), known limitations (no NTRIP yet → BT shows GPS/DGPS quality only unless caster configured in receiver; Android-mode quality inferred from accuracy; no night theme yet; no USB OTG).

- [ ] **Step 4: Tag** — `git tag phase1-checkpoint`
