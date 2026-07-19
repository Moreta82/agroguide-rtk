# AgroGuide RTK — Phase 6 Implementation Plan (robustness pass + final review)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the app for production: 4 h soak proof, reconnect-storm proof, aggressive-battery-manager onboarding, mock location provider output, night theme + settings polish, and persistence of the active guidance line. Ends in the FINAL REVIEW checkpoint.

**Environment:** unchanged.

---

### Task 1: soak tests (4 h accelerated)

**Files:**
- Modify: `core/coverage/build.gradle.kts` → `tasks.withType<Test> { maxHeapSize = "1g" }`
- Test: `core/coverage/src/test/kotlin/com/agroguide/rtk/coverage/SoakTest.kt`
- Test: `device/src/test/java/com/agroguide/rtk/device/PipelineSoakTest.kt`

`SoakTest`: 4 h at 5 Hz = 72 000 segments, single 6 m section, serpentine (passes of 1 200 m at 0.6 m/segment, adjacent at exact width, 36 passes). Assert: wall time < 120 s, `Runtime` used-heap delta (after `System.gc()` hint) < 300 MB, treated ≈ 36·6·1200 m² (tolerance 50 m²), overlap < 1 % of treated. Print the actual numbers with `println` so the checkpoint can quote them.

`PipelineSoakTest`: `SyntheticNmeaGenerator` 4 h at 5 Hz (72 000 epochs = 216 000 lines) through `PositionPipeline` (as a plain flow, no delays); assert states collected == 72 000 and wall time < 60 s.

- [ ] Write both, run, adjust nothing else until green. Commit `test: 4h accelerated soak for coverage engine and NMEA pipeline`

---

### Task 2: NTRIP reconnect-storm test

**Test:** `core/ntrip/src/test/kotlin/com/agroguide/rtk/ntrip/NtripStormTest.kt` — 3 consecutive cycles: fresh `ServerSocket(0)` each; server accepts, sends `ICY 200 OK` + 64 RTCM bytes, closes. Client (`NtripClient` per cycle, mirroring `NtripSession`'s loop) `run()`s; assert every cycle returns (no hang, no throw) and the sink received the 64 bytes; total < 15 s.

- [ ] Write, run green. Commit `test: NTRIP client survives repeated caster drops`

---

### Task 3: guidance line persistence (TDD codec + wiring)

**Files:**
- Create: `data/src/main/java/com/agroguide/rtk/data/guidance/GuidanceLineCodec.kt`
- Test: `data/src/test/java/com/agroguide/rtk/data/guidance/GuidanceLineCodecTest.kt`
- Create: `app/src/main/java/com/agroguide/rtk/data/GuidanceLineStore.kt` (DataStore key `guidance_line`, JSON string)
- Modify: `MainActivity.kt` — on start restore into `GuidanceRepository` (line + matching mode); `LaunchedEffect` collecting `GuidanceRepository.line` saves on change (including null → clear).

Codec: JSON `{type: "AB"|"APLUS"|"CURVE", points: [[x,y],...], heading: Double?}`; `toJson(line: GuidanceLine?): String?`, `fromJson(text): GuidanceLine?`; tests: roundtrip all three types, null/garbage → null.

- [ ] TDD codec, wire store, build. Commit `feat: active guidance line persisted across restarts`

---

### Task 4: mock location provider

**Files:**
- Create: `device/src/main/java/com/agroguide/rtk/device/MockLocationPublisher.kt` — `start(): Boolean` (addTestProvider GPS + enable; false on SecurityException = app not selected as mock provider in developer options), `publish(state: VehicleState)` (Location with lat/lon, accuracy by quality: 0.02/0.3/1.5/4.0 m, speed, bearing, `elapsedRealtimeNanos`), `stop()`.
- Modify: `GuidanceService` — new `EXTRA_MOCK` boolean; when true and mode != android (no feedback loop), start publisher and publish every state in the collector; stop in onDestroy.
- Manifest (`device`): `<uses-permission android:name="android.permission.ACCESS_MOCK_LOCATION" tools:ignore="MockLocation,ProtectedPermissions"/>` with xmlns:tools.
- SourceDialog: switch "Salida mock (otras apps)" bound to AppSettings (Task 6), passed into `GuidanceService.start`.

- [ ] Implement + build. Commit `feat(device,service): republish RTK position as Android mock location provider`

---

### Task 5: aggressive battery managers (TDD vendor mapping + onboarding card)

**Files:**
- Create: `device/src/main/java/com/agroguide/rtk/device/BatteryVendors.kt` (pure): `enum VendorAdvice { MIUI, EMUI, ONE_UI }`, `fun adviceFor(manufacturer: String): VendorAdvice?` — xiaomi/redmi/poco→MIUI, huawei/honor→EMUI, samsung→ONE_UI, case-insensitive.
- Test: `device/src/test/java/com/agroguide/rtk/device/BatteryVendorsTest.kt`
- App: first-run dialog in MainActivity when NOT `PowerManager.isIgnoringBatteryOptimizations(packageName)` and not shown before (flag in AppSettings): Spanish text explaining foreground-service kill risk + vendor-specific paragraph (MIUI: "Ajustes → Batería → Sin restricciones"; EMUI: "Batería → Inicio de aplicaciones → Manual"; One UI: "Batería → Uso en segundo plano sin límite"; generic otherwise) + button firing `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (manifest permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in app) + "No volver a mostrar".
- Same status shown in the Ajustes tab (Task 6) with the request button.

- [ ] TDD mapping, implement dialog, build. Commit `feat: aggressive battery manager detection and whitelisting onboarding`

---

### Task 6: night theme + AppSettings + Ajustes tab

**Files:**
- Create: `app/src/main/java/com/agroguide/rtk/data/AppSettings.kt` — DataStore JSON: `{theme: "AUTO|DAY|NIGHT", keepScreenOn: Boolean = true, mockProvider: Boolean = false, batteryDialogShown: Boolean = false}` + store class.
- Modify: `ui/theme/Theme.kt` — add high-contrast `NightColors` (`darkColorScheme`: background `#0E1510`, surface `#16201A`, primary `#7CDB8A`, onSurface `#F2F5F2`); `AgroGuideTheme(night: Boolean?)` where null = follow system.
- Create: `app/src/main/java/com/agroguide/rtk/ui/SettingsScreen.kt` — sixth tab "Ajustes": theme selector (3 radio: Auto/Día/Noche), switch "Mantener pantalla encendida", switch "Salida mock", battery-optimization card (state + request button + vendor hint), app version footer.
- Modify: `MainActivity.kt` — `ScrollableTabRow` (6 tabs); apply theme from settings; toggle FLAG_KEEP_SCREEN_ON per setting; battery dialog on first run.
- FieldView night tweak: background/grid colors read from MaterialTheme? Keep canvas colors fixed (field view is high-contrast by design) — only surrounding chrome themes. Document in checkpoint.

- [ ] Implement, build + full tests. Commit `feat(app): night theme, settings tab, keep-screen-on and mock toggles`

---

### Task 7: FINAL REVIEW

- [ ] `clean` + full `test assembleDebug` green; quote soak numbers.
- [ ] Update README-style summary in `docs/` (short: what the app does, build instructions, module map) and commit.
- [ ] Final report (Spanish): everything built across 6 phases, full manual test matrix (with/without hardware), remaining known limitations, suggested future work (USB OTG, basemap, autosteer output).
- [ ] `git tag phase6-final`
