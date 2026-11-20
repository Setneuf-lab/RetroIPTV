// Consulta el estado de una cuenta Xtream (caducidad, conexiones, estado) para el panel.
//
// El navegador no puede preguntar directamente al proveedor (CORS, y un panel https no
// puede llamar a un servidor http), así que lo hace esta función de Netlify.
//
// Seguridad:
//  - Solo responde al administrador: el panel envía su token de Firebase y aquí se
//    comprueba pidiendo a Firestore la lista de dispositivos con ese token. Las reglas de
//    Firestore (panel/firestore.rules) solo dejan hacerlo al admin, así que el email
//    autorizado sigue estando en un único sitio.
//  - Nunca se escriben en el log usuarios, contraseñas ni direcciones.
//  - Solo se devuelven los campos necesarios.

const PROJECT_ID = process.env.FIREBASE_PROJECT_ID || "retroiptv-95a7c";
const USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20";
const PROVIDER_TIMEOUT_MS = 8000;
const ADMIN_CHECK_TIMEOUT_MS = 5000;
const ADMIN_CACHE_MS = 5 * 60 * 1000;

/** Tokens ya verificados (mientras la función siga "caliente"). */
const verified = new Map();

function reply(statusCode, body) {
  return {
    statusCode,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
    body: JSON.stringify(body),
  };
}

async function isAdmin(token) {
  const now = Date.now();
  const until = verified.get(token);
  if (until && until > now) return true;
  const url = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(PROJECT_ID)}` +
    "/databases/(default)/documents/devices?pageSize=1&mask.fieldPaths=updatedAt";
  const r = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(ADMIN_CHECK_TIMEOUT_MS),
  });
  if (!r.ok) return false;
  if (verified.size > 50) verified.clear();
  verified.set(token, now + ADMIN_CACHE_MS);
  return true;
}

/** "servidor.com:8080/" → "http://servidor.com:8080". null si no es una dirección válida. */
function normalizeServer(raw) {
  let s = String(raw || "").trim();
  if (!s || s.length > 300) return null;
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) {
    if (!/^https?:\/\//i.test(s)) return null;   // solo http y https
  } else {
    s = "http://" + s;
  }
  let u;
  try { u = new URL(s); } catch { return null; }
  if (u.protocol !== "http:" && u.protocol !== "https:") return null;
  if (u.username || u.password) return null;
  const path = u.pathname.replace(/\/[^/]*\.php$/i, "").replace(/\/+$/, "");
  return u.origin + path;
}

const toInt = v => {
  const n = parseInt(String(v ?? ""), 10);
  return Number.isFinite(n) ? n : 0;
};
const isTrue = v => v === true || v === 1 || v === "1" || String(v).toLowerCase() === "true";

exports.handler = async (event) => {
  if (event.httpMethod !== "POST") return reply(405, { error: "method" });

  // Solo desde el propio panel
  const origin = event.headers.origin || "";
  const host = event.headers.host || "";
  if (origin) {
    let originHost = "";
    try { originHost = new URL(origin).host; } catch { /* inválido */ }
    if (originHost !== host) return reply(403, { error: "origin" });
  }

  const auth = event.headers.authorization || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  let admin = false;
  if (token) {
    try { admin = await isAdmin(token); } catch { admin = false; }
  }
  if (!admin) return reply(401, { error: "unauthorized" });

  let body;
  try {
    const raw = event.isBase64Encoded ? Buffer.from(event.body || "", "base64").toString("utf8") : event.body;
    body = JSON.parse(raw || "{}");
  } catch {
    return reply(400, { error: "bad_request" });
  }
  const server = normalizeServer(body.server);
  const user = typeof body.user === "string" ? body.user.trim() : "";
  const pass = typeof body.pass === "string" ? body.pass.trim() : "";
  if (!server || !user || !pass || user.length > 200 || pass.length > 200) {
    return reply(400, { error: "bad_request" });
  }

  const url = `${server}/player_api.php?username=${encodeURIComponent(user)}&password=${encodeURIComponent(pass)}`;
  let resp;
  try {
    resp = await fetch(url, {
      headers: { "User-Agent": USER_AGENT, Accept: "application/json" },
      redirect: "follow",
      signal: AbortSignal.timeout(PROVIDER_TIMEOUT_MS),
    });
  } catch (e) {
    const timeout = e && (e.name === "TimeoutError" || e.name === "AbortError");
    return reply(200, { ok: false, error: timeout ? "timeout" : "unreachable" });
  }

  if (resp.status === 401 || resp.status === 403) return reply(200, { ok: true, auth: false });
  if (!resp.ok) return reply(200, { ok: false, error: "http_" + resp.status });

  let data;
  try {
    data = JSON.parse(await resp.text());
  } catch {
    return reply(200, { ok: false, error: "not_xtream" });
  }
  const u = data && typeof data === "object" ? data.user_info : null;
  // Con datos incorrectos muchos servidores devuelven user_info vacío o auth = 0
  if (!u || typeof u !== "object" || !isTrue(u.auth)) return reply(200, { ok: true, auth: false });

  const exp = toInt(u.exp_date);
  return reply(200, {
    ok: true,
    auth: true,
    status: String(u.status || ""),
    exp: exp > 0 ? exp : null,
    max: toInt(u.max_connections),
    active: toInt(u.active_cons),
    trial: isTrue(u.is_trial),
  });
};

// Para las pruebas
exports._normalizeServer = normalizeServer;
