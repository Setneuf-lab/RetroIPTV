# Retro Player en Google Play — guía paso a paso

Orden recomendado. Lo marcado con 🔑 lo haces tú en tu PC; nunca pegues contraseñas en el chat.

---

## 0. Antes de empezar (una vez)

**a) Activar la web de la política de privacidad (GitHub Pages)**
GitHub → repo `Setneuf-lab/RetroIPTV` → Settings → Pages → *Build and deployment*:
Source = *Deploy from a branch*, Branch = `main`, carpeta `/docs` → Save.
A los 2-3 minutos funcionan:
- Política: `https://setneuf-lab.github.io/RetroIPTV/privacidad.html`
- Lista de prueba (canales gratuitos y legales de Free-TV): `https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8`

**b) 🔑 Crear la llave de subida (distinta de la familiar)**
Guárdala fuera del repo y haz copia (USB / nube). Si se pierde, Google permite cambiarla, pero tarda días.

```powershell
New-Item -ItemType Directory -Force "D:\Ale\Documentos SSD1TB\App Builder\Llaves"
```
```powershell
keytool -genkeypair -v -storetype PKCS12 -keystore "D:\Ale\Documentos SSD1TB\App Builder\Llaves\retro-player-upload.jks" -alias upload -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Retro Player"
```
Te pedirá una contraseña (dos veces). Apúntala en tu gestor de contraseñas.

**c) 🔑 Guardar la llave en los Secrets de GitHub** (cada comando, por separado):
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\Ale\Documentos SSD1TB\App Builder\Llaves\retro-player-upload.jks")) | gh secret set PLAY_UPLOAD_KEYSTORE_BASE64 -R Setneuf-lab/RetroIPTV
```
```powershell
gh secret set PLAY_UPLOAD_KEYSTORE_PASSWORD -R Setneuf-lab/RetroIPTV
```
(pega la contraseña cuando la pida)
```powershell
gh secret set PLAY_UPLOAD_KEY_ALIAS -R Setneuf-lab/RetroIPTV -b upload
```

**d) Compilar el AAB firmado**
GitHub → Actions → **Compilar versión Google Play** → Run workflow (rama main).
Al acabar, abajo en *Artifacts* descargas `RetroIPTV-play-1.0.X` (dentro va el `.aab`).
Si sale el aviso "SIN FIRMAR", falta algún Secret del paso c.

---

## 1. Crear la app en Play Console

Play Console → **Crear aplicación**:
- Nombre: **Retro Player** · Idioma: Español (España) · Tipo: **Aplicación** · **Gratis**
  (gratis con compra dentro de la app; "de pago" no permite prueba).
- Aceptar las declaraciones.

## 2. Subir la primera versión a *Prueba interna*

*Probar y publicar → Pruebas → Prueba interna* → Crear versión.
- **Firma de apps de Google Play**: aceptar (Google guarda la llave final; tú solo usas la de subida).
- Sube el `.aab`. Notas de la versión: "Primera versión de prueba".
- **Testers**: crea una lista con tu cuenta (y la de quien quieras). Copia el enlace de "Unirse a la prueba"
  y ábrelo en el móvil/tele con esa cuenta para instalarla desde Play.

La prueba interna no necesita revisión: sirve para comprobar que instala y para probar la compra.

## 3. Crear el producto "versión completa"

*Monetizar → Productos → Productos integrados en la aplicación* (sale tras subir el AAB del paso 2;
si pide un perfil de pagos, créalo: datos fiscales y cuenta bancaria).
- **ID del producto: `version_completa`** (exacto, la app busca ese nombre)
- Nombre: Versión completa · Descripción: Desbloquea Retro Player para siempre. Pago único.
- Precio: **4,99 EUR** (Play calcula el resto de países) → Guardar → **Activar**.

**Probar la compra sin pagar**: *Configuración → Pruebas de licencias* → añade tu Gmail.
Con esa cuenta, la compra sale como "pedido de prueba" y no se cobra.

## 4. Ficha de Play Store (*Crecer → Presencia en Play Store → Ficha principal*)

- **Descripción breve** (80 máx.):
  `Reproductor de tus propias listas M3U y Xtream, pensado para Android TV.`
- **Descripción completa**:

  > Retro Player es un reproductor para Android TV, TV Box y móvil con el que ves **tus propias listas**
  > (M3U o Xtream Codes) en una interfaz sencilla, cómoda con el mando.
  >
  > • Directo, películas y series organizadas por grupos, con buscador que tolera acentos y erratas.
  > • "Seguir viendo", favoritos e historial.
  > • Subtítulos y pistas de audio, siguiente episodio automático y zapping por número.
  > • Dos motores de vídeo: si un canal no arranca, prueba el otro solo.
  > • Control parental con PIN.
  > • Radio por internet de emisoras españolas.
  >
  > **Importante:** Retro Player NO incluye canales, películas ni ningún contenido. Necesitas una lista
  > propia obtenida legalmente de tu proveedor. No estamos asociados con ningún proveedor de contenido.
  >
  > 7 días de prueba con todo incluido. Después, un único pago para siempre: sin suscripciones ni anuncios.

- **Icono**: `docs/play/icono-512.png` · **Gráfico destacado**: `docs/play/grafico-destacado-1024x500.png`
- **Capturas** (mínimo 2 de móvil; para TV, 1920×1080 en *Android TV*): hazlas con pantallas **sin logos de cadenas**
  (menús, ajustes, buscador, ficha vacía) o tapando los logos. **Nunca** con canales reales,
  logos de cadenas, fútbol ni carátulas de películas comerciales: es la causa nº 1 de rechazo de apps IPTV.
- Categoría: **Reproductores y editores de vídeo**. Email de contacto: cucla.info@gmail.com.
- **Android TV**: *Configuración avanzada → Factores de forma* → añadir **Android TV** (pide banner y
  capturas de TV; el banner ya va dentro de la app).

## 5. Contenido de la aplicación (*Política → Contenido de la aplicación*)

| Apartado | Respuesta |
|---|---|
| Política de privacidad | `https://setneuf-lab.github.io/RetroIPTV/privacidad.html` |
| Acceso a la app | "Algunas funciones restringidas" → instrucciones: *Abrir la app → "URL M3U" → pegar `https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8` → "Cargar lista". La prueba de 7 días da acceso completo.* |
| Anuncios | No contiene anuncios |
| Clasificación de contenido | Categoría "Utilidades / otras". Sin violencia, sexo, etc. Si pregunta si los usuarios pueden acceder a contenido sin filtrar de internet: **Sí** (reproduce listas del usuario). |
| Público objetivo | **18 años o más** (evita las normas de apps para niños) |
| Seguridad de los datos | ¿Recoge o comparte datos? **No**. (Las listas se quedan en el aparato; la compra la gestiona Google Play.) |
| App de noticias / Salud / Finanzas / Gobierno | No |
| ID de publicidad | No usa |

## 6. Prueba cerrada (obligatoria en cuentas **personales** nuevas)

Si tu cuenta es personal (creada después de nov. 2023), Google exige antes de publicar:
**al menos 12 testers** que se apunten a una **prueba cerrada** y la mantengan **14 días seguidos**.
- *Pruebas → Prueba cerrada* → crear pista → sube el mismo AAB → lista de testers (emails de Gmail:
  familia, amigos…). Cada uno abre el enlace, acepta y la instala desde Play.
- Pasados los 14 días: *Panel → Solicitar acceso a producción* (preguntas sobre la prueba).
Si es cuenta de **organización**, puedes saltarte este paso.

## 7. Producción

*Producción → Crear versión* → mismo AAB (o uno nuevo) → países (empieza por España) → Revisar → Publicar.
La revisión tarda de horas a 7 días. Si rechazan, el email dice el motivo: me lo pasas y lo corregimos.

---

### Versiones siguientes
Cada nueva versión: Actions → *Compilar versión Google Play* → descarga el AAB → súbelo en la pista que
toque. El número de versión sube solo. La versión familiar no se ve afectada.

### Requisitos técnicos (ya cumplidos)
- Android 16 / API 36 (`targetSdk` del flavor play) — obligatorio para apps nuevas desde el 31-8-2026.
- Google Play Billing 8 — obligatorio desde el 31-8-2026.
- Formato AAB, firmado con la llave de subida; sin permiso de instalar paquetes.
