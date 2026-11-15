// Media Browser web mode — the phone page. Vanilla JS, no build step.
// Contract: docs/web-mode.md. The phone link carries the passkey as
// "#k=<passkey>"; it is traded for an HttpOnly cookie at once and the fragment
// dropped. Folders are addressed by the desktop's locator text ("p").
"use strict";

const $ = (id) => document.getElementById(id);
const enc = encodeURIComponent;

/** Column counts the grid snaps between (pinch, ctrl+wheel, +/-). */
const COLUMNS = [1, 2, 3, 4, 5, 7, 9, 12, 16];
/** Tile edges the server renders; requests snap up to these (server mirrors this list). */
const TILE_SIZES = [96, 128, 192, 256, 384, 512, 768];
const MAX_FULL_EDGE = 2560;
const MAX_TILE_FETCHES = 6;

const state = {
  listing: null,      // the current /api/list answer
  items: [],          // listing.media
  colIndex: defaultColumnIndex(),
  tiles: [],          // tile elements, by item index
  tileUrls: new Map(),// item path -> {url, v, bucket, corrupt}; kept across folders (LRU)
  generation: 0,      // bumps on every navigation
};

// ------------------------------------------------------------------ errors

class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

// ---------------------------------------------------------------- notices
// A message pops up as a dimmed-background card whose close button carries a
// countdown ring; it closes itself when the ring runs out (or on tap / tap
// outside). Every message is also kept in the inbox behind the bell, unread
// (counted on the bell) until the inbox is opened, which greys them out.

const NOTICE_MS = { info: 5000, error: 10000 };
const MAX_LOG = 100;
let noticeTimer = 0;

function loadLog() {
  try { return JSON.parse(localStorage.getItem("mb.log") || "[]"); } catch (e) { return []; }
}
function saveLog(log) {
  try { localStorage.setItem("mb.log", JSON.stringify(log.slice(-MAX_LOG))); } catch (e) { /* private mode */ }
}
const messageLog = loadLog();

/**
 * Shows and logs a message. {@code action} ({label, run}) adds a button beside
 * Dismiss, e.g. Undo after a move; {@code ms} overrides the countdown.
 */
function banner(text, info, action, ms) {
  const kind = info ? "info" : "error";
  messageLog.push({ t: Date.now(), text, kind, read: false });
  saveLog(messageLog);
  updateBell();
  showNotice(text, kind, action, ms);
}

function showNotice(text, kind, action, duration) {
  const sheet = $("notice");
  $("notice-text").textContent = text;
  sheet.classList.toggle("error", kind === "error");
  const act = $("notice-action");
  act.hidden = !action;
  act.disabled = false;
  act.textContent = action ? action.label : "";
  act.onclick = action ? () => { act.disabled = true; closeNotice(); action.run(); } : null;
  sheet.hidden = false;
  const ring = $("notice-ring");
  const ms = duration || NOTICE_MS[kind];
  ring.style.animation = "none";
  void ring.getBoundingClientRect();           // restart the countdown
  ring.style.animation = "notice-countdown " + ms + "ms linear forwards";
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(closeNotice, ms);
}
function closeNotice() {
  clearTimeout(noticeTimer);
  $("notice").hidden = true;
  // stays unread until the inbox is opened
}
$("notice-close").addEventListener("click", closeNotice);
$("notice").addEventListener("click", (e) => { if (e.target === $("notice")) closeNotice(); });

function updateBell() {
  const unread = messageLog.filter((m) => !m.read).length;
  const count = $("bellcount");
  count.hidden = unread === 0;
  count.textContent = unread > 99 ? "99+" : String(unread);
  $("bell").classList.toggle("quiet", unread === 0);
}

function openInbox() {
  const list = $("inbox-list");
  list.textContent = "";
  for (const m of [...messageLog].reverse()) {
    const li = document.createElement("li");
    li.className = "msg-row" + (m.read ? " read" : "");
    const what = document.createElement("div");
    what.className = "what";
    const n = document.createElement("div");
    n.className = "msg-text";
    n.textContent = m.text;
    const sub = document.createElement("div");
    sub.className = "sub" + (m.kind === "error" ? " bad" : "");
    sub.textContent = new Date(m.t).toLocaleString(undefined,
      { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
    what.append(n, sub);
    li.appendChild(what);
    list.appendChild(li);
  }
  $("inbox-empty").hidden = messageLog.length > 0;
  $("inbox").hidden = false;
  for (const m of messageLog) m.read = true;
  saveLog(messageLog);
  updateBell();
}
$("bell").addEventListener("click", openInbox);
$("inbox-close").addEventListener("click", () => { $("inbox").hidden = true; });
$("inbox").addEventListener("click", (e) => { if (e.target === $("inbox")) $("inbox").hidden = true; });
$("inbox-clear").addEventListener("click", () => {
  messageLog.length = 0;
  saveLog(messageLog);
  updateBell();
  openInbox();
});
updateBell();

/** Reads an error body (JSON {ok:false,code,error}) into an ApiError. */
async function errorOf(res) {
  let body = null;
  try { body = await res.json(); } catch (e) { /* not JSON */ }
  return new ApiError(res.status, (body && body.code) || "http",
    (body && body.error) || ("HTTP " + res.status + " " + res.statusText));
}

/** JSON API call; a lost session shows the passkey form and rejects. */
async function api(path, options) {
  const res = await fetch(path, Object.assign({ credentials: "same-origin", cache: "no-store" }, options));
  if (!res.ok) {
    const err = await errorOf(res);
    if (err.code === "auth") showLogin(err.message);
    throw err;
  }
  return res.json();
}

function form(data) {
  return {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: Object.entries(data).map(([k, v]) => enc(k) + "=" + enc(v)).join("&"),
  };
}

// ------------------------------------------------------------------ login

function showLogin(message) {
  closeViewer(false);
  $("browse").hidden = true;
  $("login").hidden = false;
  $("login-msg").textContent = message && message.indexOf("passkey") < 0 ? message : "";
  setTimeout(() => $("login-key").focus(), 50);
}

async function login(passkey) {
  const res = await fetch("/api/login", form({ passkey }));
  if (!res.ok) throw await errorOf(res);
}

$("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const key = $("login-key").value;
  $("login-go").disabled = true;
  try {
    await login(key);
    $("login-key").value = "";
    $("login").hidden = true;
    start();
  } catch (err) {
    $("login-msg").textContent = err.message;
  } finally {
    $("login-go").disabled = false;
  }
});

// ------------------------------------------------------------------ routing

function routePath() {
  const m = location.hash.match(/^#p=(.*)$/);
  if (!m) return "";
  try { return decodeURIComponent(m[1]); } catch (e) { return ""; }
}

async function navigate(path, push) {
  const generation = ++state.generation;
  let listing;
  try {
    listing = await api("/api/list" + (path ? "?p=" + enc(path) : ""));
  } catch (err) {
    if (err.code !== "auth") banner(err.message);
    return;
  }
  if (generation !== state.generation) return;
  const hash = "#p=" + enc(listing.path);
  if (state.listing) scrollByPath.set(state.listing.path, window.scrollY);
  if (push && location.hash !== hash) history.pushState({ p: listing.path }, "", hash);
  else history.replaceState({ p: listing.path }, "", hash);
  render(listing);
  // back/forward returns to where you were in that folder; a new folder starts at the top
  window.scrollTo(0, push ? 0 : (scrollByPath.get(listing.path) || 0));
}
const scrollByPath = new Map();
if ("scrollRestoration" in history) history.scrollRestoration = "manual";

window.addEventListener("popstate", (e) => {
  if (viewer.open && !(e.state && e.state.viewer)) {
    closeViewer(false);
    return;
  }
  if (!viewer.open && e.state && e.state.viewer) {
    // forward into a viewer entry: just stay on the grid
    history.back();
    return;
  }
  const p = routePath();
  if (!state.listing || p !== state.listing.path) navigate(p, false);
});

// ------------------------------------------------------------------ browse

function render(listing) {
  tileQueue.clear();
  observer.disconnect();
  folderObserver.disconnect();

  state.listing = listing;
  state.items = listing.media;
  $("browse").hidden = false;
  $("title").textContent = listing.name;
  document.title = listing.name + " — Media Browser";
  const parts = [];
  parts.push(listing.media.length + (listing.media.length === 1 ? " item" : " items"));
  if (listing.folders.length) parts.push(listing.folders.length + (listing.folders.length === 1 ? " folder" : " folders"));
  if (listing.readOnly) parts.push("read-only archive");
  $("subtitle").textContent = parts.join(" · ");

  const crumbs = listing.crumbs;
  const up = $("up");
  up.hidden = crumbs.length < 2;
  up.onclick = () => navigate(crumbs[crumbs.length - 2].p, true);

  const nav = $("crumbs");
  nav.textContent = "";
  crumbs.forEach((c, i) => {
    if (i > 0) {
      const sep = document.createElement("span");
      sep.className = "sep";
      sep.textContent = "›";
      nav.appendChild(sep);
    }
    if (i === crumbs.length - 1) {
      const here = document.createElement("span");
      here.className = "here";
      here.textContent = c.n;
      nav.appendChild(here);
    } else {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = c.n;
      b.addEventListener("click", () => navigate(c.p, true));
      nav.appendChild(b);
    }
  });
  nav.hidden = crumbs.length < 2;
  nav.scrollLeft = nav.scrollWidth;

  const grid = $("grid");
  grid.textContent = "";
  // Folders and archives are square tiles in the same flush grid as the
  // media, ahead of it: one surface, no rounded list wedged between bars.
  for (const f of listing.folders) {
    const t = document.createElement("div");
    t.className = "tile folder" + (f.t === "archive" ? " archive" : "");
    t.setAttribute("role", "listitem");
    t.dataset.f = f.p;
    t.title = f.n;
    t.innerHTML = f.t === "archive" ? ARCHIVE_GLYPH : FOLDER_GLYPH;
    const name = document.createElement("span");
    name.className = "fname";
    name.textContent = f.n;
    t.appendChild(name);
    grid.appendChild(t);
    if (f.t !== "archive") {
      const known = collages.get(f.p);
      if (known) paintCollage(t, known);
      else folderObserver.observe(t);
    }
  }
  state.tiles = listing.media.map((item, i) => {
    const t = document.createElement("div");
    t.className = "tile";
    t.setAttribute("role", "listitem");
    t.dataset.i = String(i);
    t.title = item.n;
    if (item.k === "video" || item.k === "audio") {
      const badge = document.createElement("span");
      badge.className = "badge";
      badge.textContent = item.k === "video" ? "▶" : "♫";
      t.appendChild(badge);
    }
    if (item.c) markCorrupt(t, item.c);
    paintCachedTile(t, item);
    grid.appendChild(t);
    observer.observe(t);
    return t;
  });
  $("empty").hidden = listing.media.length > 0 || listing.folders.length > 0;
  $("move").hidden = !!listing.readOnly;
  applyColumns();
  updateBag();
}

// Tiles already loaded in this visit are kept (blob URLs, LRU) so going back
// to a folder paints them at once, with no black flash or fade.
const MAX_TILE_URLS = 1500;
function cachedTile(item) {
  const c = state.tileUrls.get(item.p);
  if (!c || c.v !== item.v) return null;
  state.tileUrls.delete(item.p);       // refresh its LRU position
  state.tileUrls.set(item.p, c);
  return c;
}
function rememberTile(p, entry) {
  const old = state.tileUrls.get(p);
  state.tileUrls.delete(p);
  state.tileUrls.set(p, entry);
  if (old && old.url !== entry.url) setTimeout(() => URL.revokeObjectURL(old.url), 1000);
  while (state.tileUrls.size > MAX_TILE_URLS) {
    const [k, v] = state.tileUrls.entries().next().value;
    state.tileUrls.delete(k);
    setTimeout(() => URL.revokeObjectURL(v.url), 1000);
  }
}
function paintCachedTile(t, item) {
  const c = cachedTile(item);
  if (!c) return;
  const img = document.createElement("img");
  img.alt = "";
  img.className = "in instant";
  img.src = c.url;
  t.prepend(img);
  t.dataset.v = item.v;
  t.dataset.bucket = String(c.bucket);
  if (c.corrupt) markCorrupt(t, c.corrupt);
}

function markCorrupt(tile, message) {
  if (tile.querySelector(".corrupt")) return;
  const dot = document.createElement("span");
  dot.className = "corrupt";
  dot.textContent = "!";
  tile.appendChild(dot);
  tile.title = tile.title.split("\n")[0] + "\nCorrupt: " + message;
}

const FOLDER_GLYPH = '<svg class="glyph" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6.5A1.5 1.5 0 0 1 4.5 5h4.6l2 2.2h8.4A1.5 1.5 0 0 1 21 8.7v9.8a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5z" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/></svg>';
const ARCHIVE_GLYPH = '<svg class="glyph" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16v12.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19.5zM3 4h18v3H3zM10 11h4" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" stroke-linecap="round"/></svg>';

$("grid").addEventListener("click", (e) => {
  const t = e.target.closest(".tile");
  if (!t) return;
  if (sel.on) { toggleTile(t); return; }
  if (t.dataset.f) navigate(t.dataset.f, true);
  else openViewer(Number(t.dataset.i));
});

// ------------------------------------------------------------------ bare mode
// One switch hides every control everywhere (bars, titles, counts, viewer
// buttons); remembered per browser. Back: tap a photo in the viewer, or a
// two-finger tap on the grid ("h" on a keyboard).

function setBare(on) {
  if (on && sel.on) setSelecting(false);
  document.body.classList.toggle("bare", on);
  try { localStorage.setItem("mb.bare", on ? "1" : "0"); } catch (e) { /* private mode */ }
}
function toggleBare() {
  const on = !document.body.classList.contains("bare");
  setBare(on);
  if (on) {
    let hinted = false;
    try { hinted = localStorage.getItem("mb.bareHint") === "1"; localStorage.setItem("mb.bareHint", "1"); } catch (e) { /* private mode */ }
    // With a bag, say so every time: its badge (and Move here) hide too.
    if (!hinted || bag.length) {
      banner("Controls hidden" + (bag.length ? ", the bag (" + bag.length + ") too" : "")
        + ". Two-finger tap the grid, or tap a photo, to bring them back.", true);
    }
  }
}
try { if (localStorage.getItem("mb.bare") === "1") document.body.classList.add("bare"); } catch (e) { /* private mode */ }
$("hide").addEventListener("click", toggleBare);
// 1:1 puts things back: the grid's default tile size, the viewer's fitted photo.
$("reset").addEventListener("click", () => {
  zoomGrid(naturalColumnIndex() - state.colIndex, window.innerWidth / 2, window.innerHeight / 2);
});
$("vreset").addEventListener("click", () => resetZoom(true));
$("vhide").addEventListener("click", toggleBare);

// ------------------------------------------------------------------ tiles

function defaultColumnIndex() {
  let saved = null;
  try { saved = localStorage.getItem("mb.cols"); } catch (e) { /* private mode */ }
  const i = COLUMNS.indexOf(Number(saved));
  return i >= 0 ? i : naturalColumnIndex();
}
/** The default tile size: about 120 px tiles, at least 3 columns. */
function naturalColumnIndex() {
  const want = Math.max(window.innerWidth, 320) / 120;
  let best = 0;
  COLUMNS.forEach((c, k) => { if (Math.abs(c - want) < Math.abs(COLUMNS[best] - want)) best = k; });
  return Math.max(best, COLUMNS.indexOf(3));
}
function columns() { return COLUMNS[state.colIndex]; }

function tileBucket() {
  // At most 2x: a 3x phone gets a slightly soft grid, and the thumbnail
  // cache it shares with the desktop holds 2.25x fewer pixels per tile.
  const px = (window.innerWidth / columns()) * Math.min(2, window.devicePixelRatio || 1);
  for (const s of TILE_SIZES) if (px <= s) return s;
  return TILE_SIZES[TILE_SIZES.length - 1];
}
function applyColumns() {
  document.documentElement.style.setProperty("--cols", String(columns()));
  document.documentElement.style.setProperty("--gap", columns() >= 9 ? "1px" : "2px");
  // tiles now needing a sharper rendition reload as they come into view
  const bucket = tileBucket();
  for (const t of state.tiles) {
    if (Number(t.dataset.bucket || 0) < bucket) {
      observer.unobserve(t);
      observer.observe(t);
    }
  }
}

/** Changes the column count by {@code steps} (positive = more, smaller tiles), keeping the tile under (x, y) in place. */
function zoomGrid(steps, x, y) {
  const next = Math.max(0, Math.min(COLUMNS.length - 1, state.colIndex + steps));
  if (next === state.colIndex) return;
  const anchor = tileAt(x, y);
  const before = anchor ? anchor.getBoundingClientRect().top : 0;
  state.colIndex = next;
  try { localStorage.setItem("mb.cols", String(columns())); } catch (e) { /* private mode */ }
  applyColumns();
  if (anchor) window.scrollBy(0, anchor.getBoundingClientRect().top - before);
}

function tileAt(x, y) {
  const el = document.elementFromPoint(x, y);
  const t = el && el.closest ? el.closest(".tile") : null;
  if (t) return t;
  // between tiles or off the grid: the first tile on screen
  return state.tiles.find((tile) => tile.getBoundingClientRect().bottom > 60) || null;
}

const tileQueue = new Set();
let tileFetches = 0;

const observer = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (e.isIntersecting) tileQueue.add(e.target);
    else tileQueue.delete(e.target);
  }
  pumpTiles();
}, { rootMargin: "600px 0px" });

// Folder tiles: a 2x2 collage of the folder's first pictures, chosen like
// the desktop mosaic's folder preview; fetched once each folder nears view.
const COLLAGE = 2;
const folderObserver = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    folderObserver.unobserve(e.target);
    loadCollage(e.target);
  }
}, { rootMargin: "600px 0px" });

const collages = new Map();          // folder path -> preview JSON (this visit)
const loadedSrc = new Set();          // collage image URLs already decoded once
const collageSrcs = new Map();        // folder path -> the tile URLs its collage used

async function loadCollage(t) {
  const known = collages.get(t.dataset.f);
  if (known) { paintCollage(t, known); return; }
  const generation = state.generation;
  let data;
  try {
    const res = await fetch("/api/folder?p=" + enc(t.dataset.f) + "&n=" + COLLAGE,
      { credentials: "same-origin" });
    if (!res.ok || generation !== state.generation) return;   // the glyph stays
    data = await res.json();
  } catch (e) {
    return;
  }
  collages.set(t.dataset.f, data);
  if (generation !== state.generation || !t.isConnected) return;
  paintCollage(t, data);
}

function paintCollage(t, data) {
  if (!data.previews.length || t.querySelector(".collage")) return;
  const cell = Math.max(96, tileBucket() / COLLAGE);
  const size = TILE_SIZES.find((s) => s >= cell) || TILE_SIZES[TILE_SIZES.length - 1];
  const box = document.createElement("div");
  box.className = "collage n" + COLLAGE;
  const srcs = data.previews.map((p) => "/api/tile?p=" + enc(p.p) + "&s=" + size + "&v=" + p.v);
  collageSrcs.set(t.dataset.f, srcs);
  for (const src of srcs) {
    const img = document.createElement("img");
    img.alt = "";
    img.decoding = "async";
    if (loadedSrc.has(src)) img.className = "in instant";
    else img.onload = () => { img.classList.add("in"); loadedSrc.add(src); };
    img.src = src;
    box.appendChild(img);
  }
  t.insertBefore(box, t.firstChild);
  t.classList.add("has-collage");
}

function pumpTiles() {
  while (tileFetches < MAX_TILE_FETCHES && tileQueue.size) {
    const t = tileQueue.values().next().value;
    tileQueue.delete(t);
    loadTile(t);
  }
}

async function loadTile(t) {
  const item = state.items[Number(t.dataset.i)];
  if (!item || !t.isConnected) return;
  const bucket = tileBucket();
  if (t.dataset.v === item.v && Number(t.dataset.bucket || 0) >= bucket) return;
  if (t.dataset.v === item.v && t.dataset.failed) return;
  const generation = state.generation;
  tileFetches++;
  try {
    const res = await fetch("/api/tile?p=" + enc(item.p) + "&s=" + bucket + "&v=" + item.v,
      { credentials: "same-origin" });
    if (generation !== state.generation) return;
    if (!res.ok) {
      const err = await errorOf(res);
      if (err.code === "auth") { showLogin(err.message); return; }
      tileFailed(t, item, err);
      return;
    }
    const corrupt = res.headers.get("X-Media-Corrupt");
    const blob = await res.blob();
    if (generation !== state.generation) return;
    const url = URL.createObjectURL(blob);
    rememberTile(item.p, { url, v: item.v, bucket, corrupt });
    let img = t.querySelector("img");
    if (!img) {
      img = document.createElement("img");
      img.alt = "";
      img.decoding = "async";
      img.addEventListener("load", () => img.classList.add("in"));
      t.prepend(img);
    }
    img.src = url;
    t.dataset.v = item.v;
    t.dataset.bucket = String(bucket);
    delete t.dataset.failed;
    const st = t.querySelector(".state");
    if (st) st.remove();
    t.classList.remove("failed", "corrupt-fail");
    if (corrupt) markCorrupt(t, corrupt);
  } catch (err) {
    if (generation === state.generation) tileFailed(t, item, new ApiError(0, "network", "Network error: " + err.message));
  } finally {
    tileFetches--;
    pumpTiles();
  }
}

function tileFailed(t, item, err) {
  t.dataset.v = item.v;
  t.dataset.failed = "1";
  let st = t.querySelector(".state");
  if (!st) {
    st = document.createElement("div");
    st.className = "state";
    t.appendChild(st);
  }
  st.textContent = "";
  const glyph = document.createElement("b");
  const label = document.createElement("span");
  if (err.code === "no-visual") {
    glyph.textContent = item.k === "audio" ? "♫" : item.k === "video" ? "▶" : "□";
    label.textContent = item.n;
  } else if (err.code === "corrupt") {
    t.classList.add("corrupt-fail");
    glyph.textContent = "!";
    label.textContent = "Corrupt";
  } else {
    t.classList.add("failed");
    glyph.textContent = "×";
    label.textContent = "Failed";
  }
  st.append(glyph, label);
  t.title = item.n + "\n" + err.message;
}

// pinch on the grid: snaps the column count when the fingers lift
let pinch = null;
const grid = $("grid");
// Listened on the whole browse screen, not just the grid: a short folder
// leaves empty space below its last row that must take the gestures too.
const browse = $("browse");
browse.addEventListener("touchstart", (e) => {
  if (e.touches.length !== 2 || viewer.open) return;
  const [a, b] = e.touches;
  const rect = grid.getBoundingClientRect();
  const cx = (a.clientX + b.clientX) / 2, cy = (a.clientY + b.clientY) / 2;
  pinch = { d0: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), cx, cy, scale: 1, t0: Date.now() };
  grid.style.transformOrigin = (cx - rect.left) + "px " + (cy - rect.top) + "px";
  e.preventDefault();
}, { passive: false });
browse.addEventListener("touchmove", (e) => {
  if (!pinch || e.touches.length !== 2) return;
  const [a, b] = e.touches;
  pinch.scale = Math.max(0.3, Math.min(3, Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY) / pinch.d0));
  grid.style.transform = "scale(" + pinch.scale + ")";
  e.preventDefault();
}, { passive: false });
function endPinch() {
  if (!pinch) return;
  const p = pinch;
  pinch = null;
  grid.style.transform = "";
  // a quick two-finger tap (no spread) shows/hides every control
  if (Date.now() - p.t0 < 350 && Math.abs(p.scale - 1) < 0.08) {
    toggleBare();
    return;
  }
  // fingers apart (scale > 1) = bigger tiles = fewer columns; at most two
  // levels per gesture, like Photos
  const raw = -Math.round(Math.log(p.scale) / Math.log(1.6));
  const steps = Math.max(-2, Math.min(2, raw));
  if (steps) zoomGrid(steps, p.cx, p.cy);
}
browse.addEventListener("touchend", (e) => { if (e.touches.length < 2) endPinch(); });
browse.addEventListener("touchcancel", endPinch);
// Safari's own page zoom gesture
document.addEventListener("gesturestart", (e) => e.preventDefault());

// ctrl+wheel (and trackpad pinch, which browsers report as ctrl+wheel)
let wheelSum = 0;
window.addEventListener("wheel", (e) => {
  if (!e.ctrlKey) return;
  e.preventDefault();
  if (viewer.open) {
    viewerWheelZoom(e);
    return;
  }
  wheelSum += e.deltaY;
  if (Math.abs(wheelSum) >= 60) {
    zoomGrid(wheelSum > 0 ? 1 : -1, e.clientX, e.clientY);
    wheelSum = 0;
  }
}, { passive: false });

window.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && closeTopSheet()) { e.preventDefault(); return; }
  if (sheetOpen()) return;
  if (e.key === "Escape" && sel.on) { setSelecting(false); e.preventDefault(); return; }
  if (viewer.open) {
    if (e.key === "Escape") closeViewer(true);
    else if (e.key === "ArrowLeft") go(-1);
    else if (e.key === "ArrowRight") go(1);
    else if (e.key === "h") toggleBare();
    else return;
    e.preventDefault();
    return;
  }
  if (e.target && (e.target.tagName === "INPUT")) return;
  if (e.key === "h") { toggleBare(); return; }
  if (e.key === "+" || e.key === "=") zoomGrid(-1, window.innerWidth / 2, window.innerHeight / 2);
  else if (e.key === "-") zoomGrid(1, window.innerWidth / 2, window.innerHeight / 2);
  else if (e.key === "Backspace" && state.listing && state.listing.crumbs.length > 1) {
    navigate(state.listing.crumbs[state.listing.crumbs.length - 2].p, true);
  }
});

window.addEventListener("resize", () => applyColumns());

// ------------------------------------------------------------------ viewer

const viewer = {
  open: false,
  index: 0,
  slides: [],          // [prev, current, next] slide elements
  scale: 1, tx: 0, ty: 0,
  fullUrls: new Map(), // "path|v" -> blob URL (a few, most recent last)
};
const MAX_FULL_URLS = 8;

function fullEdge() {
  const px = Math.max(window.innerWidth, window.innerHeight) * (window.devicePixelRatio || 1);
  return Math.min(MAX_FULL_EDGE, Math.ceil(px));
}

function buildSlides() {
  const track = $("track");
  track.textContent = "";
  viewer.slides = [0, 1, 2].map(() => {
    const s = document.createElement("div");
    s.className = "slide";
    const img = document.createElement("img");
    img.alt = "";
    s.appendChild(img);
    track.appendChild(s);
    return s;
  });
  placeSlides();
}

function placeSlides() {
  viewer.slides.forEach((s, k) => { s.style.left = ((k - 1) * 100) + "%"; });
}

function openViewer(index) {
  if (!state.items.length) return;
  if (!viewer.slides.length) buildSlides();
  viewer.open = true;
  $("viewer").hidden = false;
  $("viewer").classList.remove("bare");
  document.body.style.overflow = "hidden";
  history.pushState({ p: state.listing.path, viewer: true }, "", location.hash);
  showAt(index);
}

/** Closes the viewer; {@code viaHistory} pops the history entry opening it pushed. */
function closeViewer(viaHistory) {
  if (!viewer.open) return;
  if (viaHistory && history.state && history.state.viewer) {
    history.back();          // popstate closes it
    return;
  }
  viewer.open = false;
  $("viewer").hidden = true;
  $("viewer").style.background = "";
  document.body.style.overflow = "";
  const t = state.tiles[viewer.index];
  if (t) {
    const r = t.getBoundingClientRect();
    if (r.top < 60 || r.bottom > window.innerHeight) t.scrollIntoView({ block: "center" });
  }
}

function showAt(index) {
  viewer.index = Math.max(0, Math.min(state.items.length - 1, index));
  resetZoom(false);
  fillSlide(viewer.slides[1], viewer.index);
  fillSlide(viewer.slides[0], viewer.index - 1);
  fillSlide(viewer.slides[2], viewer.index + 1);
  updateChrome();
}

function updateChrome() {
  const item = state.items[viewer.index];
  $("vname").textContent = item ? item.n : "";
  $("vcount").textContent = (viewer.index + 1) + " of " + state.items.length;
  const ro = state.listing && state.listing.readOnly;
  for (const id of ["rotl", "rotr"]) {
    $(id).disabled = !!ro;
    $(id).title = ro ? "Read-only: inside an archive" : "";
  }
  $("vnote").textContent = ro ? "Read-only archive" : item && item.k === "video" ? "Video · poster only" : "";
  $("vprev").disabled = viewer.index <= 0;
  $("vnext").disabled = viewer.index >= state.items.length - 1;
}

/** Shows item {@code i} on slide {@code s}: its tile at once, then the full view. */
function fillSlide(s, i) {
  const item = state.items[i];
  const img = s.querySelector("img");
  s.querySelectorAll(".play, .smsg, .spin").forEach((n) => n.remove());
  img.style.transform = "";
  s.dataset.i = String(i);
  if (!item) {
    img.removeAttribute("src");
    delete s.dataset.key;
    return;
  }
  const key = item.p + "|" + item.v;
  if (s.dataset.key === key && img.getAttribute("src")) {
    if (item.k === "video") addPlay(s);
    return;
  }
  s.dataset.key = key;
  const full = viewer.fullUrls.get(key);
  const cached = cachedTile(item);
  const tile = cached && cached.url;
  if (full) img.src = full;
  else if (tile) img.src = tile;
  else img.removeAttribute("src");
  if (item.k === "video") addPlay(s);
  if (!full) loadFull(s, item, key);
}

function addPlay(s) {
  const play = document.createElement("div");
  play.className = "play";
  play.textContent = "▶";
  s.appendChild(play);
}

function slideMessage(s, text, cls) {
  let m = s.querySelector(".smsg");
  if (!m) {
    m = document.createElement("div");
    m.className = "smsg";
    s.appendChild(m);
  }
  m.className = "smsg " + (cls || "");
  m.textContent = text;
}

async function loadFull(s, item, key) {
  const spin = document.createElement("div");
  spin.className = "spin";
  s.appendChild(spin);
  try {
    const res = await fetch("/api/full?p=" + enc(item.p) + "&e=" + fullEdge() + "&v=" + item.v,
      { credentials: "same-origin" });
    if (!res.ok) {
      const err = await errorOf(res);
      if (err.code === "auth") { showLogin(err.message); return; }
      if (s.dataset.key === key) slideMessage(s, err.message, err.code === "corrupt" ? "warn" : "bad");
      return;
    }
    const corrupt = res.headers.get("X-Media-Corrupt");
    const url = URL.createObjectURL(await res.blob());
    viewer.fullUrls.set(key, url);
    while (viewer.fullUrls.size > MAX_FULL_URLS) {
      const [oldKey, oldUrl] = viewer.fullUrls.entries().next().value;
      viewer.fullUrls.delete(oldKey);
      if (!viewer.slides.some((x) => x.dataset.key === oldKey)) URL.revokeObjectURL(oldUrl);
    }
    if (s.dataset.key === key) {
      s.querySelector("img").src = url;
      if (corrupt) slideMessage(s, "Corrupt: " + corrupt, "warn");
    }
  } catch (err) {
    if (s.dataset.key === key) slideMessage(s, "Network error: " + err.message, "bad");
  } finally {
    spin.remove();
  }
}

/** Steps the viewer by {@code dir}, animating the track. */
function go(dir) {
  const next = viewer.index + dir;
  if (next < 0 || next >= state.items.length) {
    snapTrack(0);
    return;
  }
  const track = $("track");
  track.style.transition = "transform 0.22s ease-out";
  track.style.transform = "translateX(" + (-dir * 100) + "%)";
  setTimeout(() => {
    track.style.transition = "";
    track.style.transform = "";
    if (dir > 0) viewer.slides.push(viewer.slides.shift());
    else viewer.slides.unshift(viewer.slides.pop());
    placeSlides();
    viewer.index = next;
    resetZoom(false);
    fillSlide(viewer.slides[dir > 0 ? 2 : 0], next + dir);
    fillSlide(viewer.slides[1], next);
    updateChrome();
  }, 230);
}

function snapTrack(dx) {
  const track = $("track");
  track.style.transition = dx === 0 ? "transform 0.2s ease-out" : "";
  track.style.transform = dx === 0 ? "" : "translateX(" + dx + "px)";
  if (dx === 0) setTimeout(() => { track.style.transition = ""; }, 210);
}

// zoom of the current image
function currentImg() { return viewer.slides[1].querySelector("img"); }

function displayedSize() {
  const img = currentImg();
  const vw = window.innerWidth, vh = window.innerHeight;
  const nw = img.naturalWidth || vw, nh = img.naturalHeight || vh;
  const k = Math.min(vw / nw, vh / nh);
  return { w: nw * k, h: nh * k, vw, vh };
}

function clampPan() {
  const d = displayedSize();
  const mx = Math.max(0, (d.w * viewer.scale - d.vw) / 2);
  const my = Math.max(0, (d.h * viewer.scale - d.vh) / 2);
  viewer.tx = Math.max(-mx, Math.min(mx, viewer.tx));
  viewer.ty = Math.max(-my, Math.min(my, viewer.ty));
}

function applyZoom(animate) {
  const img = currentImg();
  img.style.transition = animate ? "transform 0.2s ease-out" : "";
  img.style.transform = viewer.scale === 1 && !viewer.tx && !viewer.ty ? ""
    : "translate(" + viewer.tx + "px," + viewer.ty + "px) scale(" + viewer.scale + ")";
}

function resetZoom(animate) {
  viewer.scale = 1;
  viewer.tx = 0;
  viewer.ty = 0;
  if (viewer.slides.length) applyZoom(animate);
}

/** Zooms to {@code scale} keeping the screen point (fx, fy) still. */
function zoomTo(scale, fx, fy, animate) {
  const cx = fx - window.innerWidth / 2, cy = fy - window.innerHeight / 2;
  const s = Math.max(1, Math.min(8, scale));
  const k = s / viewer.scale;
  viewer.tx = cx - (cx - viewer.tx) * k;
  viewer.ty = cy - (cy - viewer.ty) * k;
  viewer.scale = s;
  if (s === 1) { viewer.tx = 0; viewer.ty = 0; }
  clampPan();
  applyZoom(animate);
}

function viewerWheelZoom(e) {
  zoomTo(viewer.scale * Math.exp(-e.deltaY / 200), e.clientX, e.clientY, false);
}

// touch gestures on the stage
const stage = $("stage");
let g = null;           // the gesture in progress
let lastTap = 0;

stage.addEventListener("touchstart", (e) => {
  if (!viewer.open) return;
  e.preventDefault();
  const ts = e.touches;
  if (ts.length === 2) {
    const [a, b] = ts;
    const mid = { x: (a.clientX + b.clientX) / 2 - window.innerWidth / 2,
                  y: (a.clientY + b.clientY) / 2 - window.innerHeight / 2 };
    snapTrack(0);
    g = { mode: "pinch", d0: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), s0: viewer.scale,
          cx: (mid.x - viewer.tx) / viewer.scale, cy: (mid.y - viewer.ty) / viewer.scale };
  } else if (ts.length === 1 && (!g || g.mode !== "pinch")) {
    const t = ts[0];
    g = { mode: viewer.scale > 1 ? "pan" : "undecided", x0: t.clientX, y0: t.clientY,
          tx0: viewer.tx, ty0: viewer.ty, t0: Date.now(), moved: false, dx: 0, dy: 0 };
  }
}, { passive: false });

stage.addEventListener("touchmove", (e) => {
  if (!g) return;
  e.preventDefault();
  const ts = e.touches;
  if (g.mode === "pinch" && ts.length === 2) {
    const [a, b] = ts;
    const s = Math.max(0.6, Math.min(8, g.s0 * Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY) / g.d0));
    const mx = (a.clientX + b.clientX) / 2 - window.innerWidth / 2;
    const my = (a.clientY + b.clientY) / 2 - window.innerHeight / 2;
    viewer.scale = s;
    viewer.tx = mx - g.cx * s;
    viewer.ty = my - g.cy * s;
    applyZoom(false);
    return;
  }
  if (ts.length !== 1 || g.mode === "pinch") return;
  const t = ts[0];
  g.dx = t.clientX - g.x0;
  g.dy = t.clientY - g.y0;
  if (Math.abs(g.dx) > 8 || Math.abs(g.dy) > 8) g.moved = true;
  if (g.mode === "undecided" && g.moved) {
    g.mode = Math.abs(g.dx) > Math.abs(g.dy) ? "swipe" : g.dy > 0 ? "dismiss" : "none";
  }
  if (g.mode === "pan") {
    viewer.tx = g.tx0 + g.dx;
    viewer.ty = g.ty0 + g.dy;
    clampPan();
    applyZoom(false);
  } else if (g.mode === "swipe") {
    snapTrack(g.dx);
  } else if (g.mode === "dismiss") {
    const dy = Math.max(0, g.dy);
    viewer.slides[1].style.transform = "translateY(" + dy + "px) scale(" + Math.max(0.7, 1 - dy / 1200) + ")";
    $("viewer").style.background = "rgba(0,0,0," + Math.max(0.2, 1 - dy / 400) + ")";
    $("viewer").classList.add("bare");
  }
}, { passive: false });

function endGesture(e) {
  if (!g) return;
  const ts = e.touches;
  if (g.mode === "pinch") {
    if (ts.length > 0) return;              // wait for the last finger
    if (viewer.scale < 1.05) resetZoom(true);
    else { clampPan(); applyZoom(true); }
    g = null;
    return;
  }
  const done = g;
  g = null;
  const fast = Date.now() - done.t0 < 300;
  if (done.mode === "swipe") {
    const threshold = window.innerWidth * 0.18;
    if (done.dx < -threshold || (fast && done.dx < -40)) go(1);
    else if (done.dx > threshold || (fast && done.dx > 40)) go(-1);
    else snapTrack(0);
  } else if (done.mode === "dismiss") {
    const s = viewer.slides[1];
    if (done.dy > 110 || (fast && done.dy > 50)) {
      s.style.transform = "";
      closeViewer(true);
    } else {
      s.style.transition = "transform 0.2s ease-out";
      s.style.transform = "";
      setTimeout(() => { s.style.transition = ""; }, 210);
      $("viewer").style.background = "";
      $("viewer").classList.remove("bare");
    }
  } else if (!done.moved) {
    const now = Date.now();
    if (now - lastTap < 300) {
      lastTap = 0;
      if (viewer.scale > 1) resetZoom(true);
      else zoomTo(2.5, done.x0, done.y0, true);
    } else {
      lastTap = now;
      setTimeout(() => {
        if (lastTap === now) toggleBare();
      }, 300);
    }
  }
}
stage.addEventListener("touchend", endGesture);
stage.addEventListener("touchcancel", endGesture);

// mouse: drag to pan a zoomed image, double-click to zoom
let drag = null;
stage.addEventListener("mousedown", (e) => {
  if (viewer.scale > 1) drag = { x: e.clientX, y: e.clientY, tx: viewer.tx, ty: viewer.ty };
});
window.addEventListener("mousemove", (e) => {
  if (!drag) return;
  viewer.tx = drag.tx + e.clientX - drag.x;
  viewer.ty = drag.ty + e.clientY - drag.y;
  clampPan();
  applyZoom(false);
});
window.addEventListener("mouseup", () => { drag = null; });
stage.addEventListener("dblclick", (e) => {
  if (viewer.scale > 1) resetZoom(true);
  else zoomTo(2.5, e.clientX, e.clientY, true);
});

$("vclose").addEventListener("click", () => closeViewer(true));
$("vprev").addEventListener("click", () => go(-1));
$("vnext").addEventListener("click", () => go(1));
$("rotl").addEventListener("click", () => rotate("ccw"));
$("rotr").addEventListener("click", () => rotate("cw"));

async function rotate(turn) {
  const i = viewer.index;
  const item = state.items[i];
  if (!item) return;
  $("rotl").disabled = $("rotr").disabled = true;
  try {
    const res = await api("/api/rotate", form({ p: item.p, turn }));
    item.v = res.v;
    if (res.notice) banner(res.notice, true);
    const tile = state.tiles[i];
    if (tile) loadTile(tile);
    if (viewer.open && viewer.index === i) {
      // keep showing the old pixels until the new full view lands
      resetZoom(false);
      fillSlide(viewer.slides[1], i);
    }
  } catch (err) {
    if (err.code !== "auth") banner(err.message);
  } finally {
    updateChrome();
  }
}

// ------------------------------------------------------------------ move
// "Move" switches on select mode: tapping a picture or folder (never an
// archive, nothing inside one) toggles it; "Add to bag" keeps the choice on
// this phone (localStorage, as locators), across folders and reloads. In a
// folder that can take the bag, "Move here" asks, then POSTs /api/move and
// shows each item's result; moved items leave the bag, failed ones stay. For
// a few seconds a toast offers Undo (/api/undo).

const BAG_KEY = "mb.bag";
const UNDO_MS = 8000;
const sel = { on: false, picked: new Map() };   // locator -> bag entry
let bag = loadBag();

function loadBag() {
  try {
    const list = JSON.parse(localStorage.getItem(BAG_KEY) || "[]");
    return Array.isArray(list) ? list.filter((e) => e && typeof e.p === "string" && e.p) : [];
  } catch (e) {
    return [];
  }
}
function saveBag() {
  try { localStorage.setItem(BAG_KEY, JSON.stringify(bag)); } catch (e) { /* private mode: this visit only */ }
  updateBag();
}
function inBag(p) { return bag.some((e) => e.p === p); }

/** Whether {@code path} is {@code folder} or inside it (locators use the desktop's separator). */
function within(path, folder) {
  return path === folder || path.startsWith(folder + "/") || path.startsWith(folder + "\\");
}

/** The current folder can take the bag: a plain folder, not a bagged folder or inside one. */
function validTarget() {
  const l = state.listing;
  return !!l && !l.readOnly && bag.length > 0
    && !bag.some((e) => e.k === "dir" && within(l.path, e.p));
}

/** A tile's bag entry, or null when it cannot be moved (archives, read-only listings). */
function entryOf(t) {
  const l = state.listing;
  if (!l || l.readOnly) return null;
  if (t.dataset.f) {
    const f = l.folders.find((x) => x.p === t.dataset.f);
    return f && f.t !== "archive" ? { p: f.p, n: f.n, k: "dir" } : null;
  }
  const item = state.items[Number(t.dataset.i)];
  return item ? { p: item.p, n: item.n, k: item.k, v: item.v } : null;
}
function tilePath(t) {
  if (t.dataset.f) return t.dataset.f;
  const item = state.items[Number(t.dataset.i)];
  return item ? item.p : null;
}

function markTiles() {
  for (const t of $("grid").children) {
    const p = tilePath(t);
    t.classList.toggle("inbag", !!p && inBag(p));
    t.classList.toggle("sel", sel.on && !!p && sel.picked.has(p));
    t.classList.toggle("nosel", sel.on && !entryOf(t));
  }
}

function setSelecting(on) {
  sel.on = on;
  if (!on) sel.picked.clear();
  document.body.classList.toggle("selecting", on);
  $("bar").hidden = on;
  $("selbar").hidden = !on;
  updateSelBar();
  markTiles();
}
function updateSelBar() {
  const n = sel.picked.size;
  $("selcount").textContent = n ? n + " selected" : "Select items";
  $("seladd").disabled = n === 0;
}
function toggleTile(t) {
  const e = entryOf(t);
  if (!e) return;
  if (sel.picked.has(e.p)) sel.picked.delete(e.p);
  else sel.picked.set(e.p, e);
  t.classList.toggle("sel", sel.picked.has(e.p));
  updateSelBar();
}

$("move").addEventListener("click", () => setSelecting(true));
$("selcancel").addEventListener("click", () => setSelecting(false));
$("seladd").addEventListener("click", () => {
  let added = 0;
  for (const e of sel.picked.values()) {
    if (!inBag(e.p)) { bag.push(e); added++; }
  }
  setSelecting(false);
  saveBag();
  banner(added === 1 ? "1 item added to the bag." : added + " items added to the bag.", true);
});

function updateBag() {
  const n = bag.length;
  $("bagdock").hidden = n === 0;
  document.body.classList.toggle("hasbag", n > 0);
  $("bagcount").textContent = String(n);
  $("movehere").hidden = !validTarget();
  markTiles();
  if (!$("bagsheet").hidden) renderBagSheet();
}

/** The name of the folder a locator is in, for display. */
function parentName(p) {
  const cut = Math.max(p.lastIndexOf("/", p.length - 2), p.lastIndexOf("\\", p.length - 2));
  const parent = cut > 0 ? p.substring(0, cut) : p;
  const c2 = Math.max(parent.lastIndexOf("/"), parent.lastIndexOf("\\"));
  return c2 >= 0 ? parent.substring(c2 + 1) || parent : parent;
}

function thumbFor(e) {
  const box = document.createElement("div");
  box.className = "thumb";
  if (e.k === "dir") {
    box.innerHTML = FOLDER_GLYPH;
    miniCollage(box, e.p);
  } else if (e.v) {
    const img = document.createElement("img");
    img.alt = "";
    img.src = "/api/tile?p=" + enc(e.p) + "&s=96&v=" + enc(e.v);
    img.onerror = () => { img.remove(); box.textContent = e.k === "video" ? "▶" : "□"; };
    box.appendChild(img);
  }
  return box;
}

/**
 * A bagged folder's thumbnail: the same collage its folder tile showed (the
 * very same image URLs, so they come from the browser's cache), or fetched
 * when the tile was never on screen in this visit; the glyph stays otherwise.
 */
async function miniCollage(box, path) {
  let srcs = collageSrcs.get(path);
  if (!srcs) {
    let data = collages.get(path);
    if (!data) {
      try {
        const res = await fetch("/api/folder?p=" + enc(path) + "&n=" + COLLAGE, { credentials: "same-origin" });
        if (!res.ok) return;
        data = await res.json();
        collages.set(path, data);
      } catch (err) {
        return;
      }
    }
    srcs = data.previews.map((p) => "/api/tile?p=" + enc(p.p) + "&s=96&v=" + p.v);
  }
  if (!srcs.length) return;
  const grid = document.createElement("div");
  grid.className = "minicollage";
  for (const src of srcs) {
    const img = document.createElement("img");
    img.alt = "";
    img.src = src;
    grid.appendChild(img);
  }
  box.textContent = "";
  box.appendChild(grid);
}

function renderBagSheet() {
  const list = $("baglist");
  list.textContent = "";
  $("bagtitle").textContent = bag.length === 1 ? "Bag · 1 item" : "Bag · " + bag.length + " items";
  for (const e of bag) {
    const li = document.createElement("li");
    const what = document.createElement("div");
    what.className = "what";
    const n = document.createElement("div");
    n.className = "n";
    n.textContent = e.n + (e.k === "dir" ? " (folder)" : "");
    const sub = document.createElement("div");
    sub.className = "sub";
    sub.textContent = "in " + parentName(e.p);
    what.append(n, sub);
    const rm = document.createElement("button");
    rm.type = "button";
    rm.className = "icon";
    rm.textContent = "×";
    rm.setAttribute("aria-label", "Remove " + e.n + " from the bag");
    rm.addEventListener("click", () => {
      bag = bag.filter((x) => x.p !== e.p);
      if (!bag.length) $("bagsheet").hidden = true;
      saveBag();
    });
    li.append(thumbFor(e), what, rm);
    list.appendChild(li);
  }
  $("bagempty").disabled = bag.length === 0;
}

$("bagbtn").addEventListener("click", () => {
  renderBagSheet();
  $("bagsheet").hidden = false;
});
$("bagclose").addEventListener("click", () => { $("bagsheet").hidden = true; });
$("bagempty").addEventListener("click", async () => {
  const n = bag.length;
  if (!n || !await ask("Empty the bag (" + n + (n === 1 ? " item" : " items") + ")? Nothing is moved.")) return;
  bag = [];
  $("bagsheet").hidden = true;
  saveBag();
});
$("resclose").addEventListener("click", () => { $("result").hidden = true; });
for (const id of ["bagsheet", "result"]) {
  $(id).addEventListener("click", (e) => { if (e.target === $(id)) $(id).hidden = true; });
}

/** A Yes/No question; resolves true on Yes. */
let askDone = null;
function ask(text) {
  if (askDone) askDone(false);
  $("asktext").textContent = text;
  $("ask").hidden = false;
  return new Promise((resolve) => {
    askDone = (answer) => {
      askDone = null;
      $("ask").hidden = true;
      resolve(answer);
    };
  });
}
$("askyes").addEventListener("click", () => askDone && askDone(true));
$("askno").addEventListener("click", () => askDone && askDone(false));

function sheetOpen() {
  return !$("ask").hidden || !$("bagsheet").hidden || !$("result").hidden;
}
/** Esc: closes the topmost sheet; false when none is open. */
function closeTopSheet() {
  if (!$("ask").hidden) { if (askDone) askDone(false); return true; }
  if (!$("result").hidden) { $("result").hidden = true; return true; }
  if (!$("bagsheet").hidden) { $("bagsheet").hidden = true; return true; }
  return false;
}

/** A form POST with a repeated {@code p} field per item. */
function itemsForm(fields, items) {
  return {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: Object.entries(fields).map(([k, v]) => enc(k) + "=" + enc(v))
      .concat(items.map((p) => "p=" + enc(p))).join("&"),
  };
}

$("movehere").addEventListener("click", moveHere);
async function moveHere() {
  if (!validTarget()) return;
  const target = state.listing;
  const n = bag.length;
  if (!await ask("Move " + n + (n === 1 ? " item" : " items") + " to " + target.name + "?")) return;
  $("movehere").disabled = true;
  let res;
  try {
    res = await api("/api/move", itemsForm({ t: target.path }, bag.map((e) => e.p)));
  } catch (err) {
    if (err.code !== "auth") banner(err.message);
    return;
  } finally {
    $("movehere").disabled = false;
  }
  const entries = new Map(bag.map((e) => [e.p, e]));
  const done = new Set(res.items.filter((r) => r.status !== "failed").map((r) => r.p));
  bag = bag.filter((e) => !done.has(e.p));
  saveBag();
  const count = res.moved + res.renamed;
  showResults("Move to " + res.targetName, res, entries, res.id && count ? res.id : null);
  if (res.id && count) toast("Moved " + count + (count === 1 ? " item" : " items"), res.id);
  refreshAfterMove();
}

function refreshAfterMove() {
  collages.clear();              // folder collages may have gained or lost pictures
  if (state.listing) navigate(state.listing.path, false);
}

const STATUS_TEXT = { moved: "Moved", renamed: "Renamed to ", skipped: "Skipped: ", failed: "Failed: " };
/** The per-item outcome of a move or undo. */
function showResults(title, res, entries, undoId) {
  // Undo stays on the results sheet for as long as it is open (the message
  // card offers it too, but only while its countdown runs).
  const u = $("resundo");
  u.hidden = !undoId;
  u.disabled = false;
  u.onclick = undoId ? () => undo(undoId) : null;
  $("restitle").textContent = title;
  const parts = [];
  for (const k of ["moved", "renamed", "skipped", "failed"]) if (res[k]) parts.push(res[k] + " " + k);
  $("ressum").textContent = parts.join(" · ") || "Nothing to do";
  const list = $("reslist");
  list.textContent = "";
  for (const r of res.items) {
    const li = document.createElement("li");
    li.dataset.status = r.status;
    const e = entries && entries.get(r.p);
    li.appendChild(thumbFor({ p: r.to || r.p, k: e ? e.k : "", v: e && e.v }));
    const what = document.createElement("div");
    what.className = "what";
    const n = document.createElement("div");
    n.className = "n";
    n.textContent = r.n || (e && e.n) || r.p;
    const sub = document.createElement("div");
    sub.className = "sub " + (r.status === "failed" ? "bad" : r.status === "moved" ? "good" : "warn");
    sub.textContent = STATUS_TEXT[r.status] + (r.status === "renamed" ? r.name
      : r.status === "skipped" ? r.reason : r.status === "failed" ? r.error : "");
    what.append(n, sub);
    li.appendChild(what);
    list.appendChild(li);
  }
  $("result").hidden = false;
}

/** After a move: the message card, with Undo beside Dismiss until it counts down. */
function toast(text, id) {
  banner(text + ".", true, { label: "Undo", run: () => undo(id) }, UNDO_MS);
}

const undone = new Set();     // move ids already undone (from the card or the sheet)
async function undo(id) {
  if (undone.has(id)) return;
  undone.add(id);
  $("resundo").disabled = true;
  if (!$("notice").hidden && !$("notice-action").hidden) closeNotice();
  let res;
  try {
    res = await api("/api/undo", form({ id }));
  } catch (err) {
    undone.delete(id);
    $("resundo").disabled = false;
    if (err.code !== "auth") banner("Undo failed: " + err.message);
    return;
  }
  showResults("Undo", res, null);
  refreshAfterMove();
}

// ------------------------------------------------------------------ start

async function start() {
  try {
    await api("/api/status");
  } catch (err) {
    if (err.code !== "auth") banner(err.message);
    return;
  }
  $("login").hidden = true;
  navigate(routePath(), false);
}

(async function boot() {
  const m = location.hash.match(/^#k=([^&]*)/);
  if (m) {
    let key = "";
    try { key = decodeURIComponent(m[1]); } catch (e) { key = m[1]; }
    // the passkey must not linger in the address bar or history
    history.replaceState(null, "", location.pathname + location.search);
    try {
      await login(key);
    } catch (err) {
      showLogin(err.message);
      return;
    }
  }
  start();
})();
