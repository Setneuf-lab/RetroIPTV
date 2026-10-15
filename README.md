# Retro IPTV

Reproductor IPTV personal para Android móvil, tablet, TV Box y Android TV (Android 8+).
Kotlin + XML, View Binding, OkHttp + Coroutines, Media3 (ExoPlayer) y parser M3U propio. Sin dependencias de pago.

## Funciones

- **Device ID** único por dispositivo (formato MAC `AA:BB:CC:DD:EE:FF`) + clave de 6 dígitos.
- **3 formas de cargar contenido**: URL M3U, archivo `.m3u/.m3u8` del dispositivo, o Xtream Codes (host + usuario + contraseña → genera la URL `get.php` sola).
- **Clasificación automática** en TV en Vivo, Películas y Series (las series se agrupan por título y se abren en episodios).
- **Buscador en tiempo real** por nombre de canal o grupo (busca en todos los grupos).
- **Ordenar** (botón ⇅ junto al buscador): por número de canal, alfabético A–Z o por grupos del proveedor. Se recuerda la elección; los números de canal no cambian al reordenar.
- **10 canales por pantalla** en TV gracias a filas compactas de una línea. En móvil, letra y logo más pequeños y el nombre ocupa todo el ancho (hasta 2 líneas).
- **Subtítulos** (botón junto a los controles del reproductor o tecla CC del mando): elegir la pista que ofrezca el vídeo, tamaño, tipo de letra, sombra/contorno/fondo, color y **sincronización** (±0,1 s / ±1 s, se aplica al momento). La app recuerda el estilo y el idioma elegido.
- **Buscador global (Xtream)**: en Películas y Series, al escribir se busca por título en todo el catálogo. La primera búsqueda descarga la lista de títulos (sin episodios) y la guarda; después es instantánea. Se rehace con «Actualizar lista».
- **Reproductor integrado** Media3 con HLS, MPEG-TS, MP4/MKV; pistas de audio y subtítulos.
- **Mando a distancia**: todo navegable con D-pad, foco ámbar visible. En TV en vivo: CH+/CH- o ↑/↓ (con controles ocultos) para cambiar de canal.
- **Recuerda** la última lista (se abre al instante sin descargar) y la posición de películas/episodios.
- **Favoritos**: mantén pulsado OK sobre un canal, película, serie o episodio para añadirlo/quitarlo. Tienen su propia sección en el menú.
- **Control parental con PIN**: bloquea automáticamente grupos de adultos y los grupos que elijas (mantén OK sobre un grupo). Se desbloquean con el PIN hasta volver al menú.
- **Lista de canales durante la reproducción**: OK (en directo) o ← abre un panel lateral sin parar el vídeo. En móvil, botón de lista arriba a la derecha.
- Botones **Cambiar lista**, **Actualizar lista**, **Reiniciar app** y **Control parental** en la parte inferior del menú.

## Panel web de administración (solo tú)

Desde un navegador (PC o móvil, en cualquier sitio) entras con **tu cuenta de Google** y ves **todos los dispositivos** de la familia: les añades, editas, borras o activas listas (URL M3U o Xtream), les pones nombre y eliminas los que ya no uses. Nadie más puede entrar.

Las TVs no necesitan cuenta: cada una solo puede leer su propia configuración y elegir entre las listas que tú le asignes. La app aplica los cambios al abrirse o al pulsar **Reiniciar** / **Actualizar lista**.

Funciona con **Firebase** (gratis de sobra para unos pocos dispositivos).

### 1. Crear el proyecto Firebase (≈15 min, una sola vez)
1. Entra en **console.firebase.google.com** con tu cuenta de Google → **Crear un proyecto** (p. ej. `retroiptv`; puedes desactivar Google Analytics).
2. **Compilación → Firestore Database → Crear base de datos** → ubicación **europe-southwest1 (Madrid)** → **Iniciar en modo de producción**.
3. Pestaña **Reglas** → borra lo que haya → pega `panel/firestore.rules` → **comprueba que el email de `isAdmin()` es el de tu cuenta de Google** → **Publicar**.
4. **Compilación → Authentication → Comenzar** → pestaña **Método de inicio de sesión** → **Google** → **Habilitar** → elige tu email de asistencia → **Guardar**.
5. ⚙️ **Configuración del proyecto** → **Tus apps** → icono **Web `</>`** → nombre `panel` → **Registrar app** (sin Hosting). Copia `apiKey` y `projectId`.

### 2. Poner los datos en la app y en el panel
- **App**: en `gradle.properties`
  ```
  cine.firebaseProjectId=tu-project-id
  cine.firebaseApiKey=AIza...
  ```
  y vuelve a compilar el APK.
- **Panel**: los mismos dos valores en `panel/config.js`.

> La `apiKey` de Firebase no es secreta (solo identifica el proyecto). La seguridad la ponen las reglas: solo tu email puede ver y modificar listas.

### 3. Publicar el panel (gratis)
1. Crea una cuenta en **app.netlify.com**, entra en **app.netlify.com/drop** y arrastra la carpeta `panel`. Te da una dirección tipo `https://xxxx.netlify.app`.
2. **Importante:** en Firebase → **Authentication → Configuración → Dominios autorizados → Agregar dominio** → pega `xxxx.netlify.app` (sin https://). Sin esto, el botón «Entrar con Google» da error.
3. Guarda la dirección en favoritos. Para actualizar el panel: Netlify → tu sitio → **Deploys** → arrastra de nuevo la carpeta.

### 4. Uso diario
1. Instala el APK en cada dispositivo y ábrelo **una vez con Internet**: aparece solo en tu panel con su modelo y su ID.
2. En el panel → **Entrar con Google** → pulsa el dispositivo → **Añadir lista** (la primera queda **Activa**) → ponle nombre («TV de mamá»).
3. En el dispositivo: **Reiniciar** (o abrir la app) → carga la lista activa.

Si hay dos TVs del mismo modelo, distínguelas por el ID que muestra cada app en su pantalla de inicio.

Reglas de prioridad en la app:
- Si cambias algo en el panel, el panel manda en el siguiente arranque.
- En **Cambiar lista** la TV ve las listas que le asignaste; elegir otra allí también la marca como activa en el panel.
- Si alguien carga una lista a mano en la app, se respeta hasta que vuelvas a cambiar algo en el panel.
- Sin Internet o sin panel configurado, la app usa su última lista guardada.

## Obtener el APK

### Opción A — Sin instalar nada (GitHub Actions)
1. Crea un repositorio **privado** en github.com y sube el contenido de esta carpeta.
2. Pestaña **Actions** → workflow **Compilar APK** → se ejecuta solo (o pulsa *Run workflow*).
3. Cuando termine (≈5 min), abre la ejecución y descarga el artefacto **RetroIPTV-apk** (ZIP con el `.apk`).

### Opción B — Android Studio
1. Abre la carpeta `RetroIPTV` con Android Studio (Ladybug o posterior) y espera a que sincronice Gradle.
2. Menú **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. El APK queda en `app/build/outputs/apk/release/` (o `debug/`).

Ambas opciones firman con la misma clave incluida (`app/keystore`), así que puedes **actualizar sin desinstalar**.

## Instalar (sideload)

- **Móvil / tablet**: copia el APK, ábrelo y permite "Instalar apps de origen desconocido".
- **Android TV / TV Box**: instala la app **Downloader** (AFTVnews) desde la tienda, activa *Orígenes desconocidos* para ella y descarga el APK (Retro IPTV) desde un enlace (Drive, Dropbox…). Alternativa: pendrive + explorador de archivos, o `adb install CineIPTV.apk`.

## Mando a distancia

| Dónde | Botón | Acción |
|---|---|---|
| Listas | OK | Abrir / reproducir |
| Listas | Mantener OK | Añadir o quitar de favoritos |
| Grupos | Mantener OK | Bloquear / desbloquear grupo (pide PIN) |
| Reproduciendo (directo) | OK o ← | Lista de canales superpuesta |
| Reproduciendo (directo) | ↑ / ↓ · CH+ / CH- | Canal anterior / siguiente |
| Reproduciendo | MENÚ / INFO | Controles (audio, subtítulos) |
| Lista superpuesta | Atrás | Cerrarla |

## Uso

1. Al abrir verás tu **ID de dispositivo**.
2. Elige **URL M3U**, **Archivo** o **Xtream**, rellena los datos y pulsa **Cargar lista**.
3. En el menú principal elige TV en Vivo, Películas o Series.
4. Grupos a la izquierda (en TV) o arriba (en móvil vertical); escribe en el buscador para filtrar.
5. **Atrás** en una serie vuelve a la lista de series; en la búsqueda la borra.

## Problemas habituales

| Síntoma | Causa probable / solución |
|---|---|
| "Acceso denegado (401/403)" | Usuario/contraseña mal, cuenta caducada o el servidor bloquea el User-Agent. Prueba otro en el campo avanzado (p. ej. `IPTVSmartersPlayer`). |
| "Página web en lugar de lista" | URL incorrecta o el proveedor exige otro formato. |
| Canal no carga / "rechazó la conexión" | Canal caído o has superado las conexiones simultáneas de tu cuenta (típico con 3-4 personas a la vez). |
| "Formato no compatible" | El dispositivo no decodifica ese códec (p. ej. HEVC en TV Box antiguos). |
| Archivo: "no tiene selector" | Algunos TV Box no traen selector de archivos: usa URL o Xtream. |

## Estructura

```
app/src/main/java/com/ale/cineiptv/
├── data/
│   ├── M3uParser.kt          Parser M3U en streaming (atributos, EXTGRP, EXTVLCOPT, cabeceras |)
│   ├── ContentClassifier.kt  Vivo / Película / Serie
│   ├── PlaylistRepository.kt Descarga, validación, caché y clasificación
│   ├── RemoteRepository.kt   Panel web: lectura/registro en Firestore (REST)
│   ├── Network.kt            OkHttp + generador de URL Xtream
│   ├── Prefs.kt              Última fuente, Device ID, posiciones
│   └── Models.kt
├── ui/
│   ├── MainActivity.kt       Configuración / carga de lista
│   ├── HomeActivity.kt       Menú principal
│   ├── ContentActivity.kt    Grupos + buscador + lista/cuadrícula
│   ├── PlayerActivity.kt     Reproductor Media3
│   └── adapters/Adapters.kt
└── util/Ui.kt                Zoom de foco y reinicio de la app

panel/
├── index.html                Panel de administración (login con Google)
├── config.js                 projectId + apiKey de Firebase
└── firestore.rules           Reglas de seguridad para pegar en Firebase
```

## Notas

- **Audio**: se incluye el decodificador FFmpeg de Jellyfin (`org.jellyfin.media3:media3-ffmpeg-decoder`) para reproducir audio AC3/E-AC3 (Dolby), MP2 o DTS que muchos móviles no soportan. Su licencia es GPL: perfecto para uso personal; revísala si algún día distribuyes la app.
- **Modo Xtream (API)**: con la opción Xtream —o con una URL M3U del tipo `…/get.php?username=…&password=…`, que se detecta sola— la app no descarga la lista entera. Carga los canales en vivo y las categorías en segundos, y las películas/series de cada categoría solo al abrirla (las últimas 8 abiertas quedan en memoria). Funciona igual con 300.000 que con varios millones de títulos. En el menú se muestra la caducidad y las conexiones de la cuenta. En este modo, el buscador de Películas/Series busca dentro de la categoría abierta (o entre las categorías).
- **Listas enormes en M3U normal**: la app guarda una copia ya procesada de la lista para abrirse en segundos. Si una lista no cabe en la memoria del dispositivo, carga automáticamente solo la TV en vivo y lo avisa.

- La contraseña Xtream se guarda en el almacenamiento privado de la app (sin cifrar). Suficiente para uso familiar.
- Xtream se carga en formato `output=ts`. Si tu proveedor funciona mejor con HLS, cambia `output=ts` por `output=m3u8` en `data/Network.kt`.
- Las claves de firma están en `app/build.gradle.kts`; cámbialas si algún día distribuyes la app.
- Usa la app solo con listas y servicios a los que tengas derecho legal de acceso.

## Novedades 1.2

- **Caducidad de la suscripción** en el menú (también con listas M3U de servidores Xtream). Ámbar si quedan ≤15 días, rojo si caduca.
- **Seguir viendo**: fila en el menú con películas y episodios a medias (mantener OK = quitar). Al abrir algo empezado pregunta "Continuar desde…".
- **Avance/retroceso progresivo** en películas: ← → (o ⏪ ⏩) saltan 10 s; pulsando seguido 30 s, 1 min, 3 min, 5 min. El salto se aplica al soltar.
- **Controles del reproductor a pantalla completa** (sin márgenes).
- **Calidad del canal como etiqueta** (HD, FHD, 4K…) siempre visible; letra de las listas más pequeña.
- **Error de vídeo (p. ej. 4K)**: botón "Abrir con VLC" (si está instalado) que continúa en el mismo punto.

- **Series por temporadas**: al abrir una serie, la columna izquierda muestra sus temporadas (empieza en la del último episodio visto).
- **Filtro de idioma** (botón 🌐 junto a Ordenar): muestra solo los grupos/canales de un idioma o país (ES, LAT, UK…), detectado por el prefijo del proveedor.
- **Pantalla de carga** a pantalla completa con progreso y botón Cancelar.
- **Menú principal compacto en TV**: todo cabe sin desplazarse, "Seguir viendo" en tarjetas horizontales.

## Novedades 1.3

- **Siguiente episodio automático** (cuenta atrás de 10 s, "Ver ahora" / "Cancelar").
- **Zapping por número** (teclas 0-9 del mando) y **canal anterior** (→ con los controles ocultos, o la tecla "último canal").
- **Ajuste automático de fotogramas (AFR)**: la tele cambia a 50/24/60 Hz según el vídeo. Se activa/desactiva en Ajustes. En Fire TV activa además "Ajustar a la frecuencia original" en sus ajustes de pantalla.
- **Actualización diaria en segundo plano** de las cuentas Xtream.
- **Caché de imágenes** optimizada para poca memoria (200 MB en disco, RGB565).
- **Media3 1.6.1** (+ decodificador FFmpeg 1.6.1+2).
- El botón "Control parental" pasa a ser **Ajustes** (control parental + AFR).

## Novedades 1.4

- **Ficha de película** (cuentas Xtream): carátula, año, duración, nota, género, sinopsis, dirección y reparto; botones Ver/Continuar, Desde el principio y Favorito.
- **Canales repetidos agrupados**: "La 1 SD / HD / FHD" en una sola fila con las calidades; al pulsar se elige la calidad y se recuerda. Se puede desactivar en Ajustes.
- **Búsquedas recientes** y el cursor vuelve al canal visto al salir del reproductor.

## Motor VLC integrado (1.5)

- Segundo motor de reproducción **libVLC 3.6.2** dentro de la app (como Smarters/IBO).
- **Automático** (por defecto): si un canal no arranca con ninguna variante, da error de decodificación o se congela, la app pasa sola a VLC y lo recuerda para ese canal.
- En Ajustes → **Motor de reproducción**: Automático / Siempre VLC / Solo el principal.
- En modo VLC: OK pausa/continúa y muestra el tiempo; ← → avanzan/retroceden como siempre. Sin selector de subtítulos ni pistas en VLC.
- La APK incluye solo ARM (armeabi-v7a, arm64-v8a): unos +25 MB.

## Informe de reproducción (panel)

- La app apunta arranques (y su tiempo), cortes, errores, "no arranca", imagen congelada y cambios de formato, y los sube al salir del reproductor a `devices/{id}/logs` (sin usuarios ni contraseñas).
- En el panel, dentro de cada dispositivo: **Ver informe**, **Descargar (.json)** y **Borrar informe**.
- Requiere publicar las nuevas `firestore.rules` y volver a subir la carpeta `panel` a Netlify.

## Conexión más robusta

- Conexiones de vídeo con **OkHttp** (reutiliza conexiones, sigue redirecciones de balanceadores, reconecta).
- Si un canal no arranca, se prueban solas otras formas de pedirlo: **.ts ↔ .m3u8** y otro **User-Agent**; se recuerda la que funcionó.
- Cuentas de 1 conexión: el "demasiadas conexiones" (403/429/458/509) al cambiar de canal se reintenta durante ~9 s.
- Vigilante de **imagen congelada** (reconecta y, si persiste, ofrece VLC).
- El mensaje de error muestra un **detalle técnico** (HTTP, formato) para diagnosticar.

## Actualizaciones automáticas (GitHub)

1. El proyecto está en GitHub (público) **sin** la carpeta `app/keystore/`.
2. En GitHub → Settings → Secrets and variables → Actions hay dos secrets:
   - `KEYSTORE_BASE64`: la llave de firma en texto (base64)
   - `KEYSTORE_PASSWORD`: su contraseña
3. Cada cambio subido a la rama `main` compila la APK y la publica en **Releases**
   (etiqueta `build-<versionCode>`).
4. La app, al abrir el menú, consulta la última versión de `cine.updateRepo`
   (en `gradle.properties`) y, si es más nueva, ofrece **Actualizar**.

⚠️ Guarda siempre `app/keystore/cineiptv.jks`: sin ella no se pueden publicar actualizaciones.
