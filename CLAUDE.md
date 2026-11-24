# Retro IPTV — guía para Claude

App Android / Android TV de IPTV para uso familiar. Las listas se gestionan desde un panel web
(Firebase + Netlify) y las actualizaciones se publican solas con GitHub Actions.

## Cómo trabajar con el dueño (Ale)
- Responde **en español**, corto y directo al grano. Feedback franco.
- Si pide algo concreto, hazlo aunque tengas reservas; las reservas van aparte, en una línea.
- Antes de cambios grandes o caros, explica brevemente el plan; los pequeños, hazlos sin preguntar.
- Al terminar: qué ha cambiado (en lenguaje sencillo, sin jerga) y si hay algo que probar en la tele.

## Publicar una versión (IMPORTANTE)
- Cada **push a `main`** que toque la app lanza `.github/workflows/build-apk.yml`:
  compila la APK firmada del flavor **familia** (`assembleFamiliaRelease`) y crea una Release
  `build-<run>00` con el asset `RetroIPTV.apk`. Las teles la detectan al abrir la app
  (`src/familia/.../util/Updater.kt`, repo en `gradle.properties` → `cine.updateRepo`).
- `versionCode` = `run_number` + "00"; `versionName` = `1.2.<run_number>` (lo pone el workflow).
- Los cambios en `panel/**`, `README.md`, `CLAUDE.md`, `docs/**`, `app/src/play/**` y `build-play.yml` **no** generan versión familiar.
- AAB de Play: workflow manual `build-play.yml` (Actions → "Compilar versión Google Play"), solo desde main; artefacto, no Release.
- Publicar = llega a todos los aparatos de la familia: **compila antes de hacer push a main**.
  Si trabajas en una rama `claude/...`, al acabar haz merge a `main` (o pregunta si prefiere revisar antes).
- Tras el push, comprueba que el workflow termina en verde (`gh run list` / `gh run watch`).
- Nunca instalar builds locales (versionCode 1) encima de las de GitHub.

## Secretos — nunca al repo
- La llave de firma (`app/keystore/`, alias `cineiptv`) **no se sube jamás**. Está en los Secrets
  del repo: `KEYSTORE_BASE64` y `KEYSTORE_PASSWORD`.
- No escribir contraseñas ni la llave en código, commits ni mensajes.

## Dos versiones (product flavors, dimensión "canal")
- **familia** — `com.ale.retroiptv`, la de casa: panel Firebase, Updater de GitHub, PlayLog,
  `REQUEST_INSTALL_PACKAGES`. Firma con la llave familiar. Lo exclusivo vive en `app/src/familia/`
  (Updater + receptor, manifest con el permiso y FileProvider, `res/xml/file_paths.xml`).
- **play** — `com.ale.retroiptv.play`, para Google Play (AAB). BuildConfig de Firebase/UPDATE_REPO vacíos
  (sin panel, sin informes, ID de dispositivo oculto), `Updater` vacío en `app/src/play/`, sin permiso
  de instalar. Firma con la **llave de subida** (`PLAY_KEYSTORE_FILE/PASSWORD`, `PLAY_KEY_ALIAS`); sin
  ella el AAB sale sin firmar. Nunca firmar Play con la llave familiar. `BuildConfig.IS_PLAY` distingue.
- Prueba y pago (solo play): `util/License.kt` por flavor (familia = todo abierto). 7 días con todo abierto
  desde la 1.ª instalación; después `PlayerActivity.start` muestra la compra. Producto integrado
  `version_completa` (4,99 €, Billing 8, `playImplementation`). Días que quedan en el inicio (hueco del ID)
  y "Comprar versión completa" al final de Ajustes. Reinstalar reinicia la prueba (aceptado).
- Política de privacidad: `docs/privacidad.html` (GitHub Pages, rama main /docs).
- Al tocar build.gradle/manifest: comparar `aapt2 dump badging` de familia antes/después.

## Compilar / comprobar
- `./gradlew assembleFamiliaDebug assemblePlayDebug` (JDK 17). Si el entorno no puede descargar dependencias,
  al menos revisa con cuidado y deja que el workflow compile; si falla, lee el log y corrige.
- compileSdk/targetSdk 35, minSdk 26. ABIs: armeabi-v7a y arm64-v8a.

## Arquitectura (app/src/main/java/com/ale/cineiptv)
- `RetroApp` — caché de imágenes Coil, aplica el DNS elegido.
- **data/**
  - `PlaylistRepository` — carga M3U o Xtream (live, vod por categorías, series). `movieIndex()`.
  - `XtreamApi`, `M3uParser`, `ContentClassifier`, `Models` (Channel, MovieInfo…).
  - `RemoteRepository` — Firestore: listas por dispositivo (`devices/{id}`), sube PlayLog (`devices/{id}/logs`).
  - `Network` (OkHttp común) + `SmartDns` (Ajustes → DNS: auto / operadora / Cloudflare / Google / Quad9, DoH).
  - `Favorites`, `WatchHistory`, `PlayLog`, `ParentalControl`, `Prefs`, `RadioDirectory` (radio-browser.info, España; COPE y SER primero).
- **ui/**
  - `MainActivity` — pantalla de carga, comprueba el panel (sondeo cada 20 s si no hay listas).
  - `HomeActivity` — inicio: caducidad, "Seguir viendo", ajustes (parental, AFR, agrupar calidades, motor, DNS), versión abajo a la derecha.
  - `ContentActivity` — listas, búsqueda e historial, filtro de idioma, temporadas, agrupar calidades (`ChannelGroup`), favoritos por grupo.
  - `DetailActivity` — ficha de película; aviso 4K si el aparato no decodifica 4K y "Otras versiones".
  - `PlayerActivity` — Media3 1.6.1 (ExoPlayer + OkHttp + FFmpeg) con variantes .ts/.m3u8 y UA alternativo,
    watchdog de arranque e imagen congelada, AFR, siguiente episodio, zapping por número, canal anterior.
    Cambia solo a **VLC** (`VlcEngine`, libVLC 3.6.2) si ExoPlayer falla; se recuerda por canal.
  - `SubtitleController`, `AudioController` (pistas de audio + radio con imagen y retraso ajustable), `StreamTuning`.
- **util/** — `LangFilter` (idioma: delante, detrás y entre corchetes; VOSE, DUAL/MULTI; EN=UK/US/CA, LAT=MX; lo que no indica idioma se ve siempre), `Search` (`SearchQuery`: búsqueda sin acentos, por palabras, con erratas, equivalencias
  inglés/español en `EQUIVALENTS` y orden por parecido; pruebas en `app/src/test`, `./gradlew testFamiliaDebugUnitTest`), `ChannelName` (nombre/calidad), `Seasons`, `DeviceCaps` (4K, TitleKey). `Updater` está por flavor.
- `panel/` — panel web (index.html + firestore.rules). Admin: el email del dueño en las reglas.
  Netlify conectado al repo (base `panel`, `panel/netlify.toml`): cada push a `panel/**` lo publica.
  `subs.js` + `netlify/functions/xtream-info.js`: caducidad/conexiones de cada cuenta (la función
  solo responde al admin: valida su token pidiendo `devices` a Firestore).
- `tools/gen_logo.py` — genera los logos ("RETRO IPTV", caja de palomitas).

## Decisiones ya tomadas
- AFR desactivado por defecto (congelaba imagen en algunos sticks).
- No añadir funciones para saltarse bloqueos judiciales de LaLiga (VPN integrada, etc.).
- iOS aparcado. Descargas offline: propuestas, no hechas.
- Actualización sin preguntar (`Updater`): descarga al abrir e instala solo con `HomeActivity` delante.
- Control parental también dentro del reproductor: `play()` pide el PIN si el canal está bloqueado y el
  zapping (número, ±1, anterior) nunca usa canales bloqueados. PIN: espera de 1/5/15 min tras 5 fallos.
- Auditoría de seguridad (skill security-audit) en `Retro IPTV/security-audit/run-1` (fuera del repo).
- En directo no hay reintentos sin fin: reinicio → otra calidad (`playQualities`) → aviso "servidor caído" (`ServerHealth`).
