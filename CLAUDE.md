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
  compila la APK firmada y crea una Release `build-<run>00` con el asset `RetroIPTV.apk`.
  Las teles la detectan al abrir la app (`util/Updater.kt`, repo en `gradle.properties` → `cine.updateRepo`).
- `versionCode` = `run_number` + "00"; `versionName` = `1.2.<run_number>` (lo pone el workflow).
- Los cambios en `panel/**`, `README.md` y `CLAUDE.md` **no** generan versión.
- Publicar = llega a todos los aparatos de la familia: **compila antes de hacer push a main**.
  Si trabajas en una rama `claude/...`, al acabar haz merge a `main` (o pregunta si prefiere revisar antes).
- Tras el push, comprueba que el workflow termina en verde (`gh run list` / `gh run watch`).
- Nunca instalar builds locales (versionCode 1) encima de las de GitHub.

## Secretos — nunca al repo
- La llave de firma (`app/keystore/`, alias `cineiptv`) **no se sube jamás**. Está en los Secrets
  del repo: `KEYSTORE_BASE64` y `KEYSTORE_PASSWORD`.
- No escribir contraseñas ni la llave en código, commits ni mensajes.

## Compilar / comprobar
- `./gradlew assembleDebug` (JDK 17). Si el entorno no puede descargar dependencias,
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
- **util/** — `ChannelName` (nombre/calidad), `LangFilter`, `Seasons`, `DeviceCaps` (4K, TitleKey), `Updater`.
- `panel/` — panel web (index.html + firestore.rules). Admin: el email del dueño en las reglas.
- `tools/gen_logo.py` — genera los logos ("RETRO IPTV", caja de palomitas).

## Decisiones ya tomadas
- AFR desactivado por defecto (congelaba imagen en algunos sticks).
- No añadir funciones para saltarse bloqueos judiciales de LaLiga (VPN integrada, etc.).
- iOS aparcado. Descargas offline: propuestas, no hechas.
- Actualización sin preguntar (`Updater`): descarga al abrir e instala solo con `HomeActivity` delante.
- En directo no hay reintentos sin fin: reinicio → otra calidad (`playQualities`) → aviso "servidor caído" (`ServerHealth`).
