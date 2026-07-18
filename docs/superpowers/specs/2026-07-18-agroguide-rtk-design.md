# AgroGuide RTK — Especificación de diseño

Fecha: 2026-07-18 · Estado: aprobada por el usuario · Idioma del código: inglés · Idioma de UI: español (recursos preparados para i18n)

## 1. Objetivo

Aplicación Android de guiado agrícola por GNSS-RTK (guiado visual, sin autosteer físico) de calidad de producción, alcance comparable a "Agroguía". Nombre de trabajo **AgroGuide RTK**, applicationId `com.agroguide.rtk`.

## 2. Stack y versiones

- Kotlin 2.1.x, Jetpack Compose (BOM estable), AGP 8.9+, Gradle wrapper.
- compileSdk 36, targetSdk 36, minSdk 26. JDK 21 (JBR de Android Studio).
- Room + DataStore para persistencia; kotlinx-coroutines; kotlinx-serialization (GeoJSON); JTS 1.20 (`org.locationtech.jts`) para geometría.
- Sin SDK de mapas en v1: la vista de campo es un Canvas propio, offline-first. El basemap satélite queda **pospuesto tras v1** (decisión del usuario); la capa de render deja un punto de inserción limpio (capa 0 vacía bajo la cobertura).

## 3. Hardware objetivo

ArduSimple RTK Portable Bluetooth Kit (u-blox ZED-F9P):
- Bluetooth Classic SPP (no BLE), nombre `RTK_GNSS_*`, PIN 1234, 115200 bps.
- NMEA GGA/RMC/VTG/GSV/GST a 1 Hz por defecto, configurable hasta 10 Hz.
- Acepta RTCM3 por el mismo enlace serie; los casters VRS requieren subida periódica de GGA.
- Acepta UBX-CFG-VALSET (capas RAM+flash) por el mismo enlace.

## 4. Modos de posicionamiento (seleccionables en Ajustes)

1. **BT SPP directo (primario):** la app posee el socket, parsea NMEA, ejecuta su propio cliente NTRIP y escribe RTCM3 al receptor. Reconexión automática con backoff exponencial; watchdog: sin NMEA durante 5 s → reconectar + alerta en UI. Opcional: actuar como **mock location provider** del sistema.
2. **Android Location:** consume la API estándar de localización (compatible con proveedores mock externos tipo GNSS Master).
3. **USB OTG serie** (`usb-serial-for-android`): fase posterior, fuera de v1, pero `PositionSource` lo contempla.

## 5. Arquitectura modular

| Módulo | Tipo | Responsabilidad |
|---|---|---|
| `:app` | Android | UI Compose: vista de campo (Canvas), lightbar, panel de fix, ajustes, parcelas, trabajos, informes. Temas día/noche de alto contraste, touch targets ≥ 56 dp, landscape-first tablet. |
| `:core:geo` | Kotlin puro | Plano tangente ENU (origen recalculado por trabajo), conversiones, XTE, líneas AB / A+ / curvas / modo libre, snap a pasada más cercana, offsets de apero (ancho, lateral, longitudinal antena→apero). |
| `:core:nmea` | Kotlin puro | Parser NMEA (GGA/RMC/VTG/GSV/GST, checksum, frases corruptas/truncadas), codificador UBX-CFG-VALSET, framing NTRIP v1/v2, generador de replay NMEA (archivo a Hz configurable). |
| `:core:coverage` | Kotlin puro + JTS | Cobertura como polilíneas bufferizadas unidas incrementalmente; solape por intersección; contadores (ha tratadas, ha/% solape, velocidad, distancia); estado de secciones. |
| `:data` | Android | Room (trabajos, parcelas, perfiles NTRIP), DataStore (ajustes), journal de trabajo append-only replayable, import/export GeoJSON y KML, informes HTML/PDF. |
| `:device` | Android | Implementaciones de `PositionSource`: BT SPP, Android Location, Replay (demo). Cliente NTRIP. Mock location provider. Utilidades de receptor (UBX). |
| `:service` | Android | Foreground service: posee socket BT, sesión NTRIP y grabación del trabajo; sobrevive pantalla apagada; wake lock; opción keep-screen-on. |

Regla de dependencias: `:core:*` no dependen de Android ni entre sí (salvo `:core:coverage` → `:core:geo`); `:device`/`:data`/`:service` dependen de `:core:*`; `:app` de todos.

## 6. Pipeline de posición

```
PositionSource → NmeaParser → JumpFilter → CvProjector → StateFlow<VehicleState>
```

- **JumpFilter:** rechazo de outliers limitado por velocidad (salto imposible a la velocidad actual + margen → descartar fix).
- **CvProjector:** proyección a velocidad constante entre fixes para que el guiado sea suave a 1–5 Hz; tick de render 15 Hz.
- `VehicleState`: posición ENU + lat/lon, rumbo, velocidad, calidad de fix (NO FIX / 3D / DGPS / RTK Float / RTK Fixed), nº satélites, HDOP, edad de correcciones.
- Toda la matemática de guiado en ENU; **nunca** planar sobre lat/lon crudos.

## 7. Cobertura y solape (rendimiento 4 h)

- Cada avance con secciones activas genera un cuadrilátero de franja (por sección) que se une al mosaico.
- Mosaico por **teselas de ~50 m**: solo se re-unen las teselas tocadas por la franja nueva; solape = intersección incremental contra la geometría existente de la tesela, acumulado en capa separada (color distinto).
- Render: `Path` cacheado por tesela; solo se regeneran los paths de teselas modificadas. Culling por viewport.
- Contadores incrementales (no se recalcula el total en cada frame).

## 8. Guiado

- Tipos de línea: AB recta, A+ (punto + rumbo), curva (grabada), modo libre.
- XTE con lightbar en pantalla legible a 2 m; auto-snap a la pasada más cercana; ancho de apero y desfase izquierda/derecha configurables; offset longitudinal antena→apero aplicado al punto de trabajo.
- **Control de secciones:** N secciones configurables repartidas en el ancho del apero, toggles manuales por sección; la cobertura pintada refleja solo secciones activas.

## 9. NTRIP y utilidades de receptor

- Cliente NTRIP v1 y v2, navegador de source-table de mountpoints, perfiles de credenciales, toggle de subida de GGA (periódica para VRS), reconexión automática, indicador de tráfico RTCM.
- "Optimizar receptor" (un toque): 5 Hz, desactivar GSV, mantener GGA/RMC/VTG vía UBX-CFG-VALSET en capas RAM+flash; restaurar valores por defecto.
- Panel de fix en vivo: calidad con código de color, satélites, HDOP, edad de correcciones.

## 10. Trabajos, parcelas e informes

- **Parcelas:** crear conduciendo el perímetro o tocando puntos; área/perímetro; almacenar, listar, renombrar; import/export GeoJSON y KML.
- **Trabajos:** iniciar/pausar/reanudar/finalizar; persisten track completo + cobertura; reanudables tras reinicio de la app.
- **Journal crash-safe:** archivo append-only por trabajo (registros binarios: fix aceptado, cambio de secciones, pausa/reanuda, marca de origen ENU), fsync periódico; al reabrir se reproduce y se reconstruye el estado. Room guarda solo el índice/metadatos.
- **Informe por trabajo** (HTML y PDF): parcela, fecha, duración, área tratada/solape/parcela, ancho de apero, velocidad media, miniatura del track.

## 11. Robustez

- Foreground service con wake lock; reconexión BT/NTRIP con backoff exponencial y watchdog de 5 s sin NMEA.
- Detección en primer arranque de gestores de batería agresivos (MIUI/HyperOS, EMUI, One UI) con guía de whitelisting.
- Modo demo (replay NMEA) permite operar toda la app sin hardware.

## 12. Testing

- Tests unitarios JVM en `:core:*`: parser NMEA (malformadas/truncadas/checksum), conversiones ENU, XTE, área/solape, framing NTRIP, codificación UBX.
- El generador de replay se comparte entre tests y modo demo in-app.
- Soak de 4 h (Fase 6) = replay acelerado midiendo memoria/CPU/tiempos de frame.
- `gradlew test` se ejecuta en local en cada fase (Android Studio + JDK 21 ya instalados; dispositivo físico opcional para pruebas manuales).

## 13. Fases y checkpoints

1. Esqueleto del proyecto, núcleo de posicionamiento (modos 1 y 2), pipeline NMEA, modo demo replay, panel de estado de fix. **CHECKPOINT**
2. Cliente NTRIP + utilidades de receptor. **CHECKPOINT**
3. Motor de guiado (AB/A+/curvas, lightbar, XTE) sobre la vista de campo Canvas. **CHECKPOINT**
4. Cobertura, solape, control de secciones, contadores en vivo. **CHECKPOINT**
5. Parcelas, trabajos, informes, import/export. **CHECKPOINT**
6. Pase de robustez (gestores de batería, tormentas de reconexión, soak 4 h con replay), pulido de ajustes, salida mock provider. **REVISIÓN FINAL**

En cada checkpoint: qué se construyó, cómo probarlo manualmente (con y sin hardware), limitaciones conocidas. No se avanza sin confirmación del usuario.

## 14. Decisiones registradas

- applicationId: `com.agroguide.rtk`.
- Basemap satélite: pospuesto tras v1 (punto de inserción previsto).
- Informes/checkpoints al usuario en español; código y comentarios en inglés; UI en español vía resources.
- Stack nativo Kotlin/Compose (alternativas Flutter y map-SDK-first descartadas: peor soporte BT SPP/servicios y conflicto con offline-first, respectivamente).
