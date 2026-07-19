# AgroGuide RTK

Aplicación Android de guiado agrícola por GNSS-RTK (guiado visual tipo lightbar, sin autosteer físico), pensada para el kit ArduSimple RTK Portable Bluetooth (u-blox ZED-F9P). UI en español, offline-first (sin mapas), landscape para tablet.

## Qué hace

- **Posicionamiento**: Bluetooth SPP directo al receptor (parser NMEA propio con watchdog de 5 s y reconexión con backoff), API de ubicación de Android, o modo demo simulado sin hardware.
- **Correcciones RTK**: cliente NTRIP v1/v2 integrado (perfiles, navegador de mountpoints, subida de GGA para VRS, indicador de tráfico RTCM) escribiendo RTCM3 al receptor por el mismo socket.
- **Receptor**: "Optimizar" a 5 Hz + GSV off vía UBX-CFG-VALSET (RAM+flash) con confirmación por ACK; restaurar valores de fábrica.
- **Guiado**: líneas AB, A+, curvas grabadas y modo libre; XTE con auto-snap a la pasada más cercana; offsets de apero (ancho, lateral, longitudinal); lightbar drive-to legible a 2 m; vista de campo Canvas con cámara heading-up, zoom y modo north-up.
- **Cobertura**: franjas pintadas en tiempo real (JTS, mosaico incremental de teselas de 50 m), solape en color distinto, control manual de hasta 16 secciones, contadores en vivo (ha, solape, distancia). Soak verificado: 4 h a 5 Hz = 72.000 segmentos en ~4 s de CPU y ~1 MB de heap.
- **Parcelas**: creación conduciendo el perímetro o marcando puntos; área/perímetro; import/export GeoJSON y KML.
- **Trabajos**: iniciar/pausar/reanudar/finalizar con journal binario append-only crash-safe; reanudación tras reinicio con la cobertura reconstruida; informes HTML y PDF con miniatura del track.
- **Robustez**: foreground service con wake lock, onboarding para gestores de batería agresivos (MIUI/EMUI/One UI), salida como mock location provider, tema día/noche, línea de guiado persistente.

## Compilar

Requisitos: Android Studio (JDK 21 incluido) y SDK 36.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat test assembleDebug
# APK: app\build\outputs\apk\debug\app-debug.apk
```

## Módulos

| Módulo | Tipo | Contenido |
|---|---|---|
| `:core:geo` | JVM | Plano tangente ENU WGS84, filtro de saltos, proyector CV, motor de guiado (AB/A+/curvas, XTE, snap) |
| `:core:nmea` | JVM | Parser NMEA 0183, agregador de fixes, ensamblador de líneas, UBX (VALSET, ACK), replay/generador sintético |
| `:core:ntrip` | JVM | Cliente NTRIP v1/v2, sourcetable, decodificador chunked |
| `:core:coverage` | JVM | Cobertura incremental por teselas con JTS, solape, contadores |
| `:data` | Android | Room (parcelas, trabajos), journal AGJ1, codecs GeoJSON/KML, informes HTML/PDF, codec de línea |
| `:device` | Android | Fuentes de posición (BT SPP, Android, demo), pipeline, mock provider, detección de fabricantes |
| `:service` | Android | Foreground service, repositorios (posición, guiado, cobertura), NTRIP session, JobManager |
| `:app` | Android | UI Compose: campo+lightbar, parcelas, trabajos, NTRIP, receptor, ajustes |

Tests: `.\gradlew.bat test` (~110 tests JVM, sin dispositivo). El hardware se sustituye con el modo demo (generador NMEA sintético).

Specs y planes de implementación en `docs/superpowers/`.
