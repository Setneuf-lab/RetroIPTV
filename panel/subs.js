// Estado de la suscripción del proveedor de cada lista (caducidad, conexiones, estado).
// Los datos los pide la función de Netlify netlify/functions/xtream-info.js; el último
// resultado se guarda en este navegador para verlo al instante mientras se refresca.
// En la caché no se guardan usuarios ni contraseñas: solo una huella de ellos.

const FN_URL = "/.netlify/functions/xtream-info";
const CACHE_KEY = "retroiptv.subs.v1";
const REQUEST_TIMEOUT_MS = 10_000;   // la función espera 8 s al proveedor
const DAY = 86_400_000;
const SOON_DAYS = 30;
const CACHE_MAX_AGE = 90 * DAY;

/** Huella corta (no reversible a simple vista) para identificar una cuenta. */
function fingerprint(str) {
  let h1 = 0xdeadbeef, h2 = 0x41c6ce57;
  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36);
}

/** Servidor, usuario y contraseña de una lista; null si no se puede consultar. */
export function credsOf(p) {
  if (!p) return null;
  if (p.type === "xtream") {
    if (!p.host || !p.user || !p.pass) return null;
    return { server: p.host.trim(), user: p.user.trim(), pass: p.pass.trim() };
  }
  let u;
  try { u = new URL(String(p.url || "").trim()); } catch { return null; }
  // Solo direcciones tipo .../get.php?username=U&password=P
  if (!/\.php$/i.test(u.pathname)) return null;
  const user = u.searchParams.get("username");
  const pass = u.searchParams.get("password");
  if (!user || !pass) return null;
  const base = u.pathname.replace(/\/[^/]*\.php$/i, "").replace(/\/+$/, "");
  return { server: u.origin + base, user, pass };
}

export function keyOf(p) {
  const c = credsOf(p);
  if (!c) return null;
  const server = c.server.replace(/^https?:\/\//i, "").replace(/\/+$/, "").toLowerCase();
  return "k" + fingerprint(`${server}\n${c.user}\n${c.pass}`);
}

// ---------- Caché ----------
function loadCache() {
  try { return JSON.parse(localStorage.getItem(CACHE_KEY) || "{}") || {}; } catch { return {}; }
}
let cache = loadCache();
function saveCache() {
  const now = Date.now();
  for (const [k, v] of Object.entries(cache)) {
    if (now - Math.max(v.at || 0, v.errorAt || 0) > CACHE_MAX_AGE) delete cache[k];
  }
  try { localStorage.setItem(CACHE_KEY, JSON.stringify(cache)); } catch { /* sin almacenamiento */ }
}

const checking = new Set();
const listeners = new Set();
export function onChange(fn) { listeners.add(fn); }
function emit() { listeners.forEach(fn => { try { fn(); } catch (e) { console.error(e); } }); }

/** Lo que se sabe de la cuenta de una lista (null si la lista no tiene datos de caducidad). */
export function entryOf(p) {
  const key = keyOf(p);
  return key ? { key, data: cache[key] || null, checking: checking.has(key) } : null;
}

export function isChecking() { return checking.size > 0; }

async function query(creds, token) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), REQUEST_TIMEOUT_MS);
  try {
    const r = await fetch(FN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify(creds),
      signal: ctl.signal,
    });
    if (r.status === 401 || r.status === 403) return { ok: false, error: "unauthorized" };
    if (r.status === 404) return { ok: false, error: "no_function" };
    if (!r.ok) return { ok: false, error: "fn_" + r.status };
    return await r.json();
  } catch (e) {
    return { ok: false, error: e && e.name === "AbortError" ? "timeout" : "network" };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Comprueba en paralelo las cuentas de [playlists] (cada una una sola vez).
 * [maxAgeMs]: no volver a preguntar por las comprobadas hace menos de ese tiempo.
 */
export async function checkAll(playlists, getToken, { maxAgeMs = 0 } = {}) {
  const now = Date.now();
  const pending = new Map();
  for (const p of playlists) {
    const c = credsOf(p);
    if (!c) continue;
    const k = keyOf(p);
    if (pending.has(k) || checking.has(k)) continue;
    const prev = cache[k];
    if (maxAgeMs && prev && now - Math.max(prev.at || 0, prev.errorAt || 0) < maxAgeMs) continue;
    pending.set(k, c);
  }
  if (!pending.size) return;
  let token = "";
  try { token = await getToken(); } catch { /* se responderá "unauthorized" */ }
  pending.forEach((_, k) => checking.add(k));
  emit();
  await Promise.all([...pending].map(async ([k, c]) => {
    const r = await query(c, token);
    const prev = cache[k];
    if (r && r.ok) cache[k] = { ...r, at: Date.now() };
    else cache[k] = { ...(prev && prev.ok ? prev : { ok: false }), error: (r && r.error) || "network", errorAt: Date.now() };
    checking.delete(k);
    saveCache();
    emit();
  }));
}

// ---------- Textos ----------
const fmtDay = ms => new Date(ms).toLocaleDateString("es-ES", { day: "2-digit", month: "2-digit", year: "numeric" });
function fmtWhen(ms, now = Date.now()) {
  const d = new Date(ms);
  const time = d.toLocaleTimeString("es-ES", { hour: "2-digit", minute: "2-digit" });
  return new Date(now).toDateString() === d.toDateString()
    ? time
    : d.toLocaleDateString("es-ES", { day: "2-digit", month: "2-digit" }) + " " + time;
}

const ERRORS = {
  timeout: "El servidor no responde (8 s)",
  unreachable: "No se pudo conectar con el servidor",
  not_xtream: "El servidor no da datos de la cuenta",
  unauthorized: "Sesión caducada: recarga el panel",
  no_function: "Falta la función de Netlify (ver README)",
  network: "No se pudo comprobar (¿sin Internet?)",
};
const errorText = code => ERRORS[code] || (String(code).startsWith("http_")
  ? "El servidor respondió " + String(code).slice(5)
  : "No se pudo comprobar");

const STATUS_ES = { expired: "Caducada", banned: "Bloqueada", disabled: "Desactivada" };

/**
 * Cómo mostrar una cuenta:
 *  tone: ok (verde) · warn (ámbar) · bad (rojo) · muted (gris)
 *  kind: ok · unlimited · soon · expired · problem · unknown (para el resumen y el orden)
 */
export function describe(entry, now = Date.now()) {
  if (!entry) return { tone: "muted", kind: "none", text: "Sin datos de caducidad", sub: "", order: 3e9 };
  const d = entry.data;
  if (!d) return { tone: "muted", kind: "unknown", text: entry.checking ? "Comprobando…" : "Sin comprobar", sub: "", order: 2e9 };
  if (!d.ok) {
    const sub = entry.checking ? "comprobando de nuevo…" : (d.errorAt ? "intento: " + fmtWhen(d.errorAt, now) : "");
    return { tone: "muted", kind: "unknown", text: errorText(d.error), sub, order: 2e9 };
  }

  let tone, kind, text, order;
  const status = String(d.status || "");
  const statusBad = status !== "" && status.toLowerCase() !== "active";
  const days = d.exp ? Math.ceil((d.exp * 1000 - now) / DAY) : null;
  const expired = days !== null && d.exp * 1000 <= now;

  if (d.auth === false) {
    tone = "bad"; kind = "problem"; text = "Usuario o contraseña incorrectos"; order = -2e9;
  } else if (statusBad || expired) {
    tone = "bad";
    kind = expired || status.toLowerCase() === "expired" ? "expired" : "problem";
    const label = statusBad ? `${STATUS_ES[status.toLowerCase()] || "Estado"} (${status})` : "Caducada";
    text = label + (d.exp ? (expired ? " · caducó el " : " · caduca el ") + fmtDay(d.exp * 1000) : "");
    order = -1e9 + (days || 0);
  } else if (days === null) {
    tone = "ok"; kind = "unlimited"; text = "Ilimitada"; order = 1e9;
  } else {
    tone = days > SOON_DAYS ? "ok" : "warn";
    kind = days > SOON_DAYS ? "ok" : "soon";
    text = `Caduca el ${fmtDay(d.exp * 1000)} · ${days === 1 ? "falta 1 día" : `faltan ${days} días`}`;
    order = days;
  }

  const parts = [];
  if (d.auth !== false && d.max > 0) parts.push(`${d.active || 0}/${d.max} conexiones`);
  if (d.trial) parts.push("prueba");
  if (d.at) parts.push("comprobado " + fmtWhen(d.at, now));
  if (entry.checking) parts.push("actualizando…");
  else if (d.error) parts.push("ahora: " + errorText(d.error).toLowerCase());
  return { tone, kind, text, sub: parts.join(" · "), order };
}

/** "2 caducan este mes · 1 caducada" (cada cuenta cuenta una vez aunque esté en varias TVs). */
export function summarize(playlists, now = Date.now()) {
  const seen = new Map();
  for (const p of playlists) {
    const e = entryOf(p);
    if (e && !seen.has(e.key)) seen.set(e.key, describe(e, now).kind);
  }
  if (!seen.size) return { text: "", tone: "muted" };
  const count = k => [...seen.values()].filter(v => v === k).length;
  const soon = count("soon"), expired = count("expired"), problem = count("problem"), unknown = count("unknown");
  const parts = [];
  if (soon) parts.push(soon === 1 ? "1 caduca este mes" : `${soon} caducan este mes`);
  if (expired) parts.push(expired === 1 ? "1 caducada" : `${expired} caducadas`);
  if (problem) parts.push(problem === 1 ? "1 bloqueada o con datos mal" : `${problem} bloqueadas o con datos mal`);
  if (unknown) parts.push(unknown === 1 ? "1 sin comprobar" : `${unknown} sin comprobar`);
  const total = seen.size;
  if (!parts.length) return { text: `Todas al día (${total} ${total === 1 ? "cuenta" : "cuentas"})`, tone: "ok" };
  return { text: parts.join(" · "), tone: expired || problem ? "bad" : soon ? "warn" : "muted" };
}

/** Línea con el estado de una lista: punto de color (CSS) + texto + detalle. */
export function chip(desc, prefix = "") {
  const box = document.createElement("div");
  box.className = "subs tone-" + desc.tone;
  const main = document.createElement("span");
  main.className = "main";
  main.textContent = (prefix ? prefix + ": " : "") + desc.text;
  box.append(main);
  if (desc.sub) {
    const sub = document.createElement("span");
    sub.className = "when";
    sub.textContent = desc.sub;
    box.append(sub);
  }
  return box;
}
