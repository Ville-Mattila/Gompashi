"use strict";

// ---------- Geo math (mirrors the Android GeoUtils) ----------
const R = 6371000;
const rad = (d) => (d * Math.PI) / 180;
const deg = (r) => (r * 180) / Math.PI;

function distanceMeters(lat1, lon1, lat2, lon2) {
  const dLat = rad(lat2 - lat1), dLon = rad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function bearingTo(lat1, lon1, lat2, lon2) {
  const phi1 = rad(lat1), phi2 = rad(lat2), dLon = rad(lon2 - lon1);
  const y = Math.sin(dLon) * Math.cos(phi2);
  const x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
  return (deg(Math.atan2(y, x)) + 360) % 360;
}

function smallestAngleDelta(from, to) {
  let d = (to - from) % 360;
  if (d > 180) d -= 360;
  if (d < -180) d += 360;
  return d;
}

function rankStores(lat, lon, stores) {
  return stores
    .map((s) => ({ store: s, dist: distanceMeters(lat, lon, s.lat, s.lon), bearing: bearingTo(lat, lon, s.lat, s.lon) }))
    .sort((a, b) => a.dist - b.dist);
}

function formatDistance(m) {
  if (m < 1000) {
    const r = Math.round(m);
    return (r < 100 ? r : Math.round(r / 10) * 10) + " m";
  }
  return (m / 1000).toFixed(1) + " km";
}

// ---------- State ----------
let stores = [];            // bundled + custom, used for ranking
let bundledStores = [];
let customStores = [];
const LS_NEEDLE = "gompashi.needle";
const LS_STORES = "gompashi.customStores";
let userPos = null;
let azimuth = NaN;       // device heading, 0 = north, clockwise
let tiltBeta = 0, tiltGamma = 0;
let selectedRank = 0;
let hasCompass = false;
let gotOrientation = false;
const cont = {};         // continuous (unwrapped) rotation per element

// Walking-route state (keyless OSM foot routing via FOSSGIS OSRM).
const ROUTE_URL = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/";
let currentRoute = null;     // { coords: [[lat,lon],...], distance, duration }
let routeKey = null;         // store key the current route is for
let routeOrigin = null;      // { lat, lon } where the route was computed
let routeFetching = false;
let routeCooldownUntil = 0;  // backoff after a failed fetch
let mapOpen = false;

// Dim base map: CARTO dark raster tiles (OSM data), drawn faintly under the route.
const TILE_URL = (z, x, y) => `https://basemaps.cartocdn.com/dark_all/${z}/${x}/${y}.png`;
const tileCache = new Map(); // url -> HTMLImageElement (img.ok marks loaded)
let lastMapDraw = 0;

const TILE = 256;
const mercY = (lat) => (1 - Math.log(Math.tan(rad(lat)) + 1 / Math.cos(rad(lat))) / Math.PI) / 2;

// ----- Offline map regions (downloaded tiles + routing graph) -----
const TILE_CACHE_NAME = "gompashi-tiles";
const ROUTE_CACHE_NAME = "gompashi-routing";
const LS_REGIONS = "gompashi.regions";
const OFFLINE_WIDE_KM = 25;     // wide overview area, low zoom
const OFFLINE_WIDE_MINZ = 11;
const OFFLINE_WIDE_MAXZ = 14;
const OFFLINE_SHARP_KM = 3;     // sharp inner ring around the centre
const OFFLINE_SHARP_MINZ = 15;
const OFFLINE_SHARP_MAXZ = 16;
const OFFLINE_ROUTE_KM = 2.5;   // walkable network radius for offline routing

const tileX = (lon, z) => Math.floor((lon + 180) / 360 * 2 ** z);
const tileY = (lat, z) => Math.floor(mercY(lat) * 2 ** z);

function tilesForBox(lat, lon, km, zMin, zMax, out) {
  const dLat = km / 111.32, dLon = km / (111.32 * Math.cos(rad(lat)));
  for (let z = zMin; z <= zMax; z++) {
    const x0 = tileX(lon - dLon, z), x1 = tileX(lon + dLon, z);
    const y0 = tileY(lat + dLat, z), y1 = tileY(lat - dLat, z); // north has the smaller y
    for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) out.push({ z, x, y });
  }
}

// Wide low-zoom overview + a sharp high-zoom ring around the centre.
function regionTiles(lat, lon) {
  const out = [];
  tilesForBox(lat, lon, OFFLINE_WIDE_KM, OFFLINE_WIDE_MINZ, OFFLINE_WIDE_MAXZ, out);
  tilesForBox(lat, lon, OFFLINE_SHARP_KM, OFFLINE_SHARP_MINZ, OFFLINE_SHARP_MAXZ, out);
  return out;
}

function getTile(url, onload) {
  let img = tileCache.get(url);
  if (img) return img.ok ? img : null;
  img = new Image();
  img.crossOrigin = "anonymous";
  img.ok = false;
  img.onload = () => { img.ok = true; onload(); };
  img.onerror = () => {};
  img.src = url;
  tileCache.set(url, img);
  return null;
}

// ---------- DOM ----------
const bottle = document.getElementById("bottle");
const northEl = document.getElementById("north");
const distanceEl = document.getElementById("distance");
const storeEl = document.getElementById("store");
const hintEl = document.getElementById("hint");
const hoursEl = document.getElementById("hours");
const hoursNoteEl = document.getElementById("hoursnote");
const toggleEl = document.getElementById("toggle");
const segs = [...document.querySelectorAll(".seg")];

let closedByCountry = {};      // ISO country -> Set of public-holiday dates (YYYY-MM-DD)
let currentHours = null;       // selected store's 7-day schedule
let currentHoursKnown = true;
let currentCountry = "FI";     // selected store's country (selects the holiday list)
const overlay = document.getElementById("overlay");
const overlayText = document.getElementById("overlayText");
const overlayBtn = document.getElementById("overlayBtn");

// ---------- Rotation + tilt ----------
function lowpass(target, prev, alpha = 0.1) {
  return isNaN(prev) ? target : prev + alpha * (target - prev);
}
function lowpassAngle(target, prev, alpha = 0.12) {
  if (isNaN(prev)) return target;
  return (prev + alpha * smallestAngleDelta(prev, target) + 360) % 360;
}
function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

function setRotation(el, key, targetDeg) {
  let c = key in cont ? cont[key] : targetDeg;
  const delta = smallestAngleDelta(c, targetDeg);
  if (key in cont && Math.abs(delta) < 0.5) return; // deadband: ignore magnetometer micro-jitter
  c += delta;
  cont[key] = c;
  el.style.setProperty("--rot", c + "deg");
}
function applyTilt(el) {
  el.style.setProperty("--tiltX", clamp(tiltBeta * -0.4, -7, 7) + "deg");
  el.style.setProperty("--tiltY", clamp(tiltGamma * -0.4, -7, 7) + "deg");
}

// ---------- Distance odometer ----------
let prevChars = [];
let prevMeters = null;
function renderDistance(text, meters) {
  const dir = prevMeters == null || meters >= prevMeters ? "roll-up" : "roll-down";
  prevMeters = meters;
  const chars = [...text];
  const frag = document.createDocumentFragment();
  chars.forEach((ch, i) => {
    const span = document.createElement("span");
    span.className = "ch";
    span.textContent = ch;
    if (prevChars[i] !== ch) span.classList.add(dir);
    frag.appendChild(span);
  });
  distanceEl.replaceChildren(frag);
  prevChars = chars;
}

// ---------- Opening hours / countdown ----------
function pad2(n) { return String(n).padStart(2, "0"); }
function dateKey(d) { return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate()); }
function dowMon0(d) { return (d.getDay() + 6) % 7; } // JS Sun=0 -> Mon=0..Sun=6

// Returns { state: "open"|"closed", at: Date } or null if no schedule found.
function openingStatus(now, hours, closed) {
  for (let offset = 0; offset < 14; offset++) {
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset);
    const sched = closed.has(dateKey(day)) ? null : hours[dowMon0(day)];
    if (!sched) continue;
    const [oh, om] = sched[0].split(":").map(Number);
    const [ch, cm] = sched[1].split(":").map(Number);
    const open = new Date(day.getFullYear(), day.getMonth(), day.getDate(), oh, om);
    const close = new Date(day.getFullYear(), day.getMonth(), day.getDate(), ch, cm);
    if (offset === 0) {
      if (now < open) return { state: "closed", at: open };
      if (now < close) return { state: "open", at: close };
      continue; // already closed for today
    }
    return { state: "closed", at: open };
  }
  return null;
}

function fmtDur(ms) {
  let s = Math.max(0, Math.floor(ms / 1000));
  const d = Math.floor(s / 86400); s -= d * 86400;
  const h = Math.floor(s / 3600); s -= h * 3600;
  const m = Math.floor(s / 60); s -= m * 60;
  const hms = `${h}:${pad2(m)}:${pad2(s)}`;
  return d > 0 ? `${d} pv ${hms}` : hms;
}

function updateHours() {
  if (!currentHours) { hoursEl.textContent = ""; hoursNoteEl.textContent = ""; return; }
  const now = new Date();
  const st = openingStatus(now, currentHours, closedByCountry[currentCountry] || new Set());
  if (!st) { hoursEl.textContent = ""; hoursNoteEl.textContent = ""; return; }
  const label = st.state === "open" ? "Auki vielä " : "Aukeaa ";
  hoursEl.innerHTML = `<span class="${st.state}">${label}${fmtDur(st.at - now)}</span>`;
  hoursNoteEl.textContent = currentHoursKnown ? "" : "aukioloaika ei tiedossa — vakioajat käytössä";
}

// ---------- Walking route + map ----------
function currentTarget() {
  if (!userPos || !stores.length) return null;
  const ranked = rankStores(userPos.lat, userPos.lon, stores);
  const rank = Math.min(selectedRank, ranked.length - 1);
  return ranked[rank];
}

function storeKey(s) { return `${s.lat.toFixed(5)},${s.lon.toFixed(5)}`; }

// ---------- Offline walking router (downloaded OSM network + A*) ----------
const OVERPASS_ENDPOINTS = [
  "https://overpass-api.de/api/interpreter",
  "https://overpass.kumi.systems/api/interpreter",
  "https://overpass.private.coffee/api/interpreter",
];
const WALK_TYPES = "footway|path|pedestrian|living_street|residential|steps|unclassified|tertiary|service";
const routeGraphKey = (lat, lon) => `https://gompashi.local/route/${lat.toFixed(5)}_${lon.toFixed(5)}`;
let routeGraphs = null; // lazily-loaded array of {nodes:[[lat,lon]], adj:[[i,...]]}

// Fetch the walkable network around (lat,lon) and build a routable graph.
async function fetchNetworkGraph(lat, lon) {
  const dLat = OFFLINE_ROUTE_KM / 111.32, dLon = OFFLINE_ROUTE_KM / (111.32 * Math.cos(rad(lat)));
  const b = `(${(lat - dLat).toFixed(5)},${(lon - dLon).toFixed(5)},${(lat + dLat).toFixed(5)},${(lon + dLon).toFixed(5)})`;
  const q = `[out:json][timeout:60];way["highway"~"^(${WALK_TYPES})$"]${b};out geom;`;
  let d = null;
  for (const ep of OVERPASS_ENDPOINTS) {
    try {
      const res = await fetch(ep, {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: "data=" + encodeURIComponent(q),
      });
      if (res.ok) { d = await res.json(); break; }
    } catch (_) { /* try next mirror */ }
  }
  if (!d) throw new Error("overpass-unreachable");
  const idx = new Map(), nodes = [], adj = [];
  const nodeId = (la, lo) => {
    const k = Math.round(la * 1e5) + "," + Math.round(lo * 1e5);
    let i = idx.get(k);
    if (i === undefined) { i = nodes.length; idx.set(k, i); nodes.push([Math.round(la * 1e5) / 1e5, Math.round(lo * 1e5) / 1e5]); adj.push([]); }
    return i;
  };
  for (const el of d.elements || []) {
    let prev = -1;
    for (const p of el.geometry || []) {
      const id = nodeId(p.lat, p.lon);
      if (prev >= 0 && prev !== id) {
        if (!adj[prev].includes(id)) adj[prev].push(id);
        if (!adj[id].includes(prev)) adj[id].push(prev);
      }
      prev = id;
    }
  }
  return { nodes, adj };
}

async function loadGraphs() {
  if (routeGraphs) return routeGraphs;
  routeGraphs = [];
  try {
    const c = await caches.open(ROUTE_CACHE_NAME);
    for (const req of await c.keys()) {
      const res = await c.match(req);
      if (res) routeGraphs.push(await res.json());
    }
  } catch (_) {}
  return routeGraphs;
}

function snapNode(graph, lat, lon) {
  let best = -1, bestD = Infinity;
  const n = graph.nodes;
  for (let i = 0; i < n.length; i++) {
    const d = distanceMeters(lat, lon, n[i][0], n[i][1]);
    if (d < bestD) { bestD = d; best = i; }
  }
  return { idx: best, dist: bestD };
}

// A* over the node graph; returns {coords,distance} or null.
function aStar(graph, start, goal) {
  const { nodes, adj } = graph;
  const N = nodes.length;
  const g = new Float64Array(N).fill(Infinity);
  const came = new Int32Array(N).fill(-1);
  const [glat, glon] = nodes[goal];
  const h = (i) => distanceMeters(nodes[i][0], nodes[i][1], glat, glon);
  const heap = [{ n: start, p: h(start) }];
  g[start] = 0;
  const up = (a) => { let i = a.length - 1; while (i > 0) { const pa = (i - 1) >> 1; if (a[pa].p <= a[i].p) break; [a[pa], a[i]] = [a[i], a[pa]]; i = pa; } };
  const down = (a) => { let i = 0; for (;;) { const l = 2 * i + 1, r = l + 1; let s = i; if (l < a.length && a[l].p < a[s].p) s = l; if (r < a.length && a[r].p < a[s].p) s = r; if (s === i) break; [a[s], a[i]] = [a[i], a[s]]; i = s; } };
  while (heap.length) {
    const cur = heap[0]; const last = heap.pop(); if (heap.length) { heap[0] = last; down(heap); }
    const u = cur.n;
    if (u === goal) break;
    if (cur.p - h(u) > g[u] + 1e-6) continue; // stale entry
    for (const v of adj[u]) {
      const nd = g[u] + distanceMeters(nodes[u][0], nodes[u][1], nodes[v][0], nodes[v][1]);
      if (nd < g[v]) { g[v] = nd; came[v] = u; heap.push({ n: v, p: nd + h(v) }); up(heap); }
    }
  }
  if (g[goal] === Infinity) return null;
  const coords = [];
  for (let c = goal; c >= 0; c = came[c]) coords.push(nodes[c]);
  coords.reverse();
  return { coords, distance: g[goal] };
}

// Compute a walking route from a downloaded graph, or null if none covers both ends.
async function offlineRoute(fLat, fLon, tLat, tLon) {
  for (const graph of await loadGraphs()) {
    if (!graph.nodes.length) continue;
    const s = snapNode(graph, fLat, fLon), e = snapNode(graph, tLat, tLon);
    if (s.dist > 300 || e.dist > 300) continue;
    const path = aStar(graph, s.idx, e.idx);
    if (path) {
      const coords = [[fLat, fLon], ...path.coords, [tLat, tLon]];
      const distance = path.distance + s.dist + e.dist;
      return { coords, distance, duration: distance / 1.35 };
    }
  }
  return null;
}

// Fetch a foot route to the target, but only when needed: store changed, user moved
// far enough, or no route yet. Backs off after failures so we respect fair-use.
async function maybeFetchRoute(target) {
  if (!userPos || !target) return;
  const key = storeKey(target.store);
  const moved = routeOrigin
    ? distanceMeters(userPos.lat, userPos.lon, routeOrigin.lat, routeOrigin.lon) : Infinity;
  const need = key !== routeKey || moved > 75;
  if (!need || routeFetching || Date.now() < routeCooldownUntil) return;
  routeFetching = true;
  try {
    let r = null;
    if (navigator.onLine) {
      try {
        const from = `${userPos.lon},${userPos.lat}`, to = `${target.store.lon},${target.store.lat}`;
        const res = await fetch(`${ROUTE_URL}${from};${to}?overview=full&geometries=geojson`);
        const d = await res.json();
        if (d.code === "Ok" && d.routes && d.routes[0]) {
          const rt = d.routes[0];
          r = { coords: rt.geometry.coordinates.map(([lon, lat]) => [lat, lon]), distance: rt.distance, duration: rt.duration };
        }
      } catch (_) { /* fall through to offline */ }
    }
    if (!r) r = await offlineRoute(userPos.lat, userPos.lon, target.store.lat, target.store.lon);
    if (r) {
      currentRoute = r;
      routeKey = key;
      routeOrigin = { lat: userPos.lat, lon: userPos.lon };
    } else {
      routeCooldownUntil = Date.now() + 15000;
      if (key !== routeKey) currentRoute = null;
    }
  } finally {
    routeFetching = false;
    if (mapOpen) drawMap(currentTarget());
  }
}

// Redraw at most ~6 fps when driven by the frequent render loop.
function drawMapThrottled(target) {
  const now = Date.now();
  if (now - lastMapDraw < 160) return;
  lastMapDraw = now;
  drawMap(target);
}

function drawMap(target) {
  const canvas = document.getElementById("mapcanvas");
  if (!canvas) return;
  const dpr = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  if (rect.width === 0) return;
  canvas.width = Math.round(rect.width * dpr);
  canvas.height = Math.round(rect.height * dpr);
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  const W = rect.width, H = rect.height;
  ctx.fillStyle = "#0b0b0b"; ctx.fillRect(0, 0, W, H);
  const info = document.getElementById("mapinfo");
  if (!userPos || !target) { info.textContent = "Odotetaan sijaintia"; return; }

  const store = target.store;
  const haveRoute = currentRoute && routeKey === storeKey(store);
  const line = haveRoute ? currentRoute.coords : [[userPos.lat, userPos.lon], [store.lat, store.lon]];

  // Frame just the start (you) and finish (store) tightly, with a small view-proportional
  // margin. Uses a continuous (fractional) zoom so the framing isn't loosened by snapping to
  // integer tile levels; tiles are drawn scaled to match.
  const nx = (lon) => (lon + 180) / 360;
  const aNx = nx(userPos.lon), bNx = nx(store.lon);
  const aNy = mercY(userPos.lat), bNy = mercY(store.lat);
  const minNx = Math.min(aNx, bNx), maxNx = Math.max(aNx, bNx);
  const minNy = Math.min(aNy, bNy), maxNy = Math.max(aNy, bNy);
  const marginX = W * 0.10, marginY = H * 0.10;
  const dnx = Math.max(maxNx - minNx, 1e-12), dny = Math.max(maxNy - minNy, 1e-12);
  let scale = Math.min((W - 2 * marginX) / dnx, (H - 2 * marginY) / dny); // px per normalized world unit
  scale = Math.max(TILE * 2 ** 3, Math.min(TILE * 2 ** 20, scale));
  // Offline: cap the tile zoom to what's downloaded. The sharp ring (z15-16) only exists near
  // a region centre; elsewhere overzoom the wide z14. Online: full detail.
  let maxZ = 19;
  if (!navigator.onLine) {
    const nearSharp = loadRegions().some(
      (r) => distanceMeters(userPos.lat, userPos.lon, r.lat, r.lon) < OFFLINE_SHARP_KM * 1000,
    );
    maxZ = nearSharp ? OFFLINE_SHARP_MAXZ : OFFLINE_WIDE_MAXZ;
  }
  const tz = Math.max(3, Math.min(maxZ, Math.floor(Math.log2(scale / TILE))));
  const nT = 2 ** tz;
  const tilePx = scale / nT; // on-screen tile size (256..512)
  const cNx = (minNx + maxNx) / 2, cNy = (minNy + maxNy) / 2;
  const sx0 = cNx * scale - W / 2, sy0 = cNy * scale - H / 2; // viewport top-left in world px
  const project = ([la, lo]) => [nx(lo) * scale - sx0, mercY(la) * scale - sy0];

  // Dim base map: CARTO dark tiles, faint behind the route.
  ctx.globalAlpha = 0.55;
  for (let tx = Math.floor(sx0 / tilePx); tx <= Math.floor((sx0 + W) / tilePx); tx++) {
    for (let ty = Math.floor(sy0 / tilePx); ty <= Math.floor((sy0 + H) / tilePx); ty++) {
      if (ty < 0 || ty >= nT) continue;
      const wx = ((tx % nT) + nT) % nT;
      // Redraw directly on tile load (cached tiles arrive faster than the throttle window).
      const img = getTile(TILE_URL(tz, wx, ty), () => { if (mapOpen) drawMap(currentTarget()); });
      if (img) ctx.drawImage(img, tx * tilePx - sx0, ty * tilePx - sy0, tilePx, tilePx);
    }
  }
  ctx.globalAlpha = 1;

  // Route on top.
  ctx.lineWidth = 4; ctx.lineJoin = "round"; ctx.lineCap = "round";
  ctx.strokeStyle = haveRoute ? "#d7263d" : "rgba(215,38,61,0.7)";
  if (!haveRoute) ctx.setLineDash([7, 7]);
  ctx.beginPath();
  line.forEach((p, i) => { const [x, y] = project(p); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
  ctx.stroke();
  ctx.setLineDash([]);

  const [sx, sy] = project([store.lat, store.lon]);
  ctx.fillStyle = "#d7263d"; ctx.beginPath(); ctx.arc(sx, sy, 7, 0, 6.2832); ctx.fill();
  ctx.fillStyle = "#000"; ctx.beginPath(); ctx.arc(sx, sy, 3, 0, 6.2832); ctx.fill();
  const [ux, uy] = project([userPos.lat, userPos.lon]);
  ctx.fillStyle = "#f5f5f5"; ctx.beginPath(); ctx.arc(ux, uy, 6, 0, 6.2832); ctx.fill();
  ctx.lineWidth = 2; ctx.strokeStyle = "rgba(0,0,0,0.6)"; ctx.stroke();

  ctx.fillStyle = "rgba(255,255,255,0.35)"; ctx.font = "9px sans-serif"; ctx.textAlign = "right";
  ctx.fillText("© OpenStreetMap, CARTO", W - 6, H - 6); ctx.textAlign = "left";

  if (haveRoute) {
    info.textContent = `Kävellen ${formatDistance(currentRoute.distance)} · ~${Math.max(1, Math.round(currentRoute.duration / 60))} min`;
  } else {
    info.textContent = "Reittiä ei saatavilla — näytetään linnuntie";
  }
}

// ---------- Render ----------
function render() {
  if (!userPos || !stores.length) return;
  const ranked = rankStores(userPos.lat, userPos.lon, stores);
  const rank = Math.min(selectedRank, ranked.length - 1);
  const target = ranked[rank];

  // The route only feeds the map view on web, so fetch it only while the map is open.
  if (mapOpen) { maybeFetchRoute(target); drawMapThrottled(target); }

  renderDistance(formatDistance(target.dist), target.dist);
  storeEl.textContent = target.store.name;
  currentHours = target.store.hours;
  currentHoursKnown = target.store.hoursKnown;
  currentCountry = target.store.country || "FI";
  updateHours();
  segs[1].disabled = stores.length < 2;
  toggleEl.className = "toggle rank" + rank;

  if (hasCompass && !isNaN(azimuth)) {
    setRotation(bottle, "bottle", target.bearing - azimuth);
    setRotation(northEl, "north", -azimuth);
    northEl.style.display = "";
    hintEl.textContent = "";
  } else {
    setRotation(bottle, "bottle", target.bearing);
    northEl.style.display = "none";
    hintEl.textContent = "Ei kompassia — suunta pohjoisesta " + Math.round(target.bearing) + "°";
  }
  applyTilt(bottle);
  applyTilt(northEl);
}

// ---------- Sensors ----------
function onOrientation(e) {
  let az = null;
  if (typeof e.webkitCompassHeading === "number" && !isNaN(e.webkitCompassHeading)) {
    az = e.webkitCompassHeading;                 // iOS: true heading, 0 = N, clockwise
  } else if (typeof e.alpha === "number" && e.alpha !== null) {
    const screenAngle = (screen.orientation && screen.orientation.angle) || 0;
    az = (360 - e.alpha + screenAngle) % 360;    // Android absolute
  }
  if (az != null) { azimuth = lowpassAngle(az, azimuth); hasCompass = true; gotOrientation = true; }
  if (typeof e.beta === "number") tiltBeta = lowpass(e.beta, tiltBeta);
  if (typeof e.gamma === "number") tiltGamma = lowpass(e.gamma, tiltGamma);
  render();
}

function startOrientation() {
  // Use a single source: the absolute (sensor-fused) event on Android, or the plain
  // event (which carries webkitCompassHeading) on iOS. Listening to both makes the
  // heading flip between two references and jitter badly.
  if ("ondeviceorientationabsolute" in window) {
    window.addEventListener("deviceorientationabsolute", onOrientation, true);
  } else {
    window.addEventListener("deviceorientation", onOrientation, true);
  }
  // If nothing arrives, fall back to bearing-from-north mode.
  setTimeout(() => { if (!gotOrientation) { hasCompass = false; render(); } }, 2500);
}

function startGeo() {
  if (!navigator.geolocation) { showError("Selaimesi ei tue sijaintia."); return; }
  navigator.geolocation.watchPosition(
    (p) => { userPos = { lat: p.coords.latitude, lon: p.coords.longitude }; hideOverlay(); render(); },
    (err) => {
      showError(err.code === err.PERMISSION_DENIED
        ? "Sijaintilupa evätty. Salli sijainti ja yritä uudelleen."
        : "Sijaintia ei saatu. Yritä uudelleen.");
    },
    { enableHighAccuracy: true, maximumAge: 2000, timeout: 30000 }
  );
}

// ---------- Overlay ----------
function hideOverlay() { overlay.classList.add("hidden"); }
function showError(msg) {
  overlay.classList.remove("hidden");
  overlayText.textContent = msg;
  overlayBtn.classList.remove("hidden");
  overlayBtn.textContent = "Yritä uudelleen";
}

async function start() {
  overlayBtn.classList.add("hidden");
  overlayText.textContent = "Haetaan sijaintia…";
  // iOS requires a user-gesture permission request for motion/orientation.
  try {
    const DOE = window.DeviceOrientationEvent;
    if (DOE && typeof DOE.requestPermission === "function") {
      const res = await DOE.requestPermission();
      hasCompass = res === "granted";
    } else {
      hasCompass = true; // confirmed once events actually arrive
    }
  } catch (_) { hasCompass = false; }
  if (hasCompass) startOrientation();
  startGeo();
}

overlayBtn.addEventListener("click", start);
segs.forEach((seg) =>
  seg.addEventListener("click", () => {
    if (seg.disabled) return;
    selectedRank = Number(seg.dataset.rank);
    render();
  })
);

// ---------- Map panel ----------
function initMap() {
  const panel = document.getElementById("mappanel");
  const mapToggle = document.getElementById("maptoggle");
  const mapsegs = [...document.querySelectorAll(".mapseg")];

  function syncMapToggle() {
    mapToggle.className = "maptoggle rank" + selectedRank;
    mapsegs[1].disabled = stores.length < 2;
  }

  mapsegs.forEach((seg) =>
    seg.addEventListener("click", () => {
      if (seg.disabled) return;
      selectedRank = Number(seg.dataset.rank);
      syncMapToggle();
      render();                       // compass + route fetch for the new target
      drawMap(currentTarget());       // immediate reframe
    })
  );

  document.getElementById("mapBtn").onclick = () => {
    mapOpen = true;
    panel.classList.add("open");
    syncMapToggle();
    const t = currentTarget();
    if (t) maybeFetchRoute(t);
    // Wait a frame so the panel has its expanded size before measuring the canvas.
    requestAnimationFrame(() => drawMap(currentTarget()));
  };
  document.getElementById("mapClose").onclick = () => {
    mapOpen = false;
    panel.classList.remove("open");
  };
  window.addEventListener("resize", () => { if (mapOpen) drawMap(currentTarget()); });
}

// ---------- Offline map regions ----------
function loadRegions() { try { return JSON.parse(localStorage.getItem(LS_REGIONS) || "[]"); } catch (_) { return []; } }
function saveRegions(r) { localStorage.setItem(LS_REGIONS, JSON.stringify(r)); }

// Download every tile (z11..14) around the current location into the persistent tile cache.
async function downloadCurrentRegion(onProgress) {
  if (!userPos) throw new Error("no-loc");
  const tiles = regionTiles(userPos.lat, userPos.lon);
  const cache = await caches.open(TILE_CACHE_NAME);
  let done = 0, bytes = 0, idx = 0;
  async function worker() {
    while (idx < tiles.length) {
      const t = tiles[idx++];
      const url = TILE_URL(t.z, t.x, t.y);
      try {
        const existing = await cache.match(url);
        if (existing) { bytes += (await existing.clone().blob()).size; }
        else {
          const res = await fetch(url, { mode: "cors" });
          if (res.ok) { bytes += (await res.clone().blob()).size; await cache.put(url, res); }
        }
      } catch (_) { /* skip failed tile */ }
      done++;
      if (onProgress && done % 12 === 0) onProgress(done, tiles.length);
    }
  }
  await Promise.all(Array.from({ length: 6 }, worker));

  // Walkable network for offline routing (best-effort; tiles still useful without it).
  if (onProgress) onProgress(tiles.length, tiles.length, "Haetaan tieverkkoa…");
  try {
    const graph = await fetchNetworkGraph(userPos.lat, userPos.lon);
    const blob = JSON.stringify(graph);
    bytes += blob.length;
    const rc = await caches.open(ROUTE_CACHE_NAME);
    await rc.put(routeGraphKey(userPos.lat, userPos.lon), new Response(blob, { headers: { "Content-Type": "application/json" } }));
    routeGraphs = null; // invalidate cache so the new graph is used
  } catch (_) { /* routing stays online-only for this region */ }

  const regions = loadRegions();
  regions.push({ lat: userPos.lat, lon: userPos.lon, tiles: tiles.length, bytes, ts: Date.now() });
  saveRegions(regions);
  return { tiles: tiles.length, bytes };
}

async function deleteRegion(i) {
  const regions = loadRegions();
  const r = regions[i];
  if (!r) return;
  const cache = await caches.open(TILE_CACHE_NAME);
  for (const t of regionTiles(r.lat, r.lon)) {
    try { await cache.delete(TILE_URL(t.z, t.x, t.y)); } catch (_) {}
  }
  try { await (await caches.open(ROUTE_CACHE_NAME)).delete(routeGraphKey(r.lat, r.lon)); } catch (_) {}
  routeGraphs = null;
  regions.splice(i, 1);
  saveRegions(regions);
}

function renderRegionList() {
  const list = document.getElementById("offlineList");
  list.replaceChildren();
  loadRegions().forEach((r, i) => {
    const row = document.createElement("div");
    row.className = "custom-row";
    const label = document.createElement("span");
    label.textContent = `${r.lat.toFixed(3)}, ${r.lon.toFixed(3)} · ${(r.bytes / 1048576).toFixed(0)} MB`;
    const del = document.createElement("button");
    del.textContent = "✕";
    del.setAttribute("aria-label", "Poista");
    del.onclick = async () => { await deleteRegion(i); renderRegionList(); };
    row.append(label, del);
    list.appendChild(row);
  });
}

function initOfflineMaps() {
  const btn = document.getElementById("offlineDownload");
  const prog = document.getElementById("offlineProgress");
  renderRegionList();
  btn.onclick = async () => {
    if (!userPos) { prog.textContent = "Sijaintia ei vielä saatu — käynnistä ensin ja salli sijainti."; return; }
    btn.disabled = true;
    prog.textContent = "Ladataan…";
    try {
      const r = await downloadCurrentRegion((d, total, status) => {
        prog.textContent = status || `Ladataan ${d}/${total}…`;
      });
      prog.textContent = `Valmis: ${(r.bytes / 1048576).toFixed(1)} MB tallennettu.`;
      renderRegionList();
    } catch (_) {
      prog.textContent = "Lataus epäonnistui. Tarkista verkkoyhteys.";
    }
    btn.disabled = false;
  };
}

// ---------- Settings: custom needle + own stores (localStorage) ----------
function rebuildStores() {
  stores = bundledStores.concat(customStores);
  if (userPos) render();
}

function loadCustom() {
  try { customStores = JSON.parse(localStorage.getItem(LS_STORES) || "[]"); } catch (_) { customStores = []; }
}

function renderCustomList() {
  const list = document.getElementById("customList");
  list.replaceChildren();
  customStores.forEach((s, i) => {
    const row = document.createElement("div");
    row.className = "custom-row";
    const label = document.createElement("span");
    label.textContent = s.name;
    const del = document.createElement("button");
    del.textContent = "✕";
    del.setAttribute("aria-label", "Poista");
    del.onclick = () => {
      customStores.splice(i, 1);
      localStorage.setItem(LS_STORES, JSON.stringify(customStores));
      rebuildStores();
      renderCustomList();
    };
    row.append(label, del);
    list.appendChild(row);
  });
}

// Downscale an uploaded image to keep localStorage small; preserves transparency (PNG).
function downscaleImage(file, maxDim) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const scale = Math.min(1, maxDim / Math.max(img.width, img.height));
      const w = Math.round(img.width * scale), h = Math.round(img.height * scale);
      const canvas = document.createElement("canvas");
      canvas.width = w; canvas.height = h;
      canvas.getContext("2d").drawImage(img, 0, 0, w, h);
      resolve(canvas.toDataURL("image/png"));
    };
    img.onerror = reject;
    img.src = URL.createObjectURL(file);
  });
}

function initSettings() {
  const settings = document.getElementById("settings");
  const preview = document.getElementById("needlePreview");

  // Apply a saved custom needle on boot.
  const saved = localStorage.getItem(LS_NEEDLE);
  if (saved) { bottle.src = saved; preview.src = saved; }

  document.getElementById("settingsBtn").onclick = () => { renderCustomList(); settings.classList.remove("hidden"); };
  document.getElementById("settingsClose").onclick = () => settings.classList.add("hidden");

  document.getElementById("needleInput").onchange = (e) => {
    const file = e.target.files[0];
    if (!file) return;
    downscaleImage(file, 720).then((url) => {
      try {
        localStorage.setItem(LS_NEEDLE, url);
        bottle.src = url; preview.src = url;
      } catch (_) { document.getElementById("customMsg").textContent = "Kuva on liian suuri tallennettavaksi."; }
    });
  };
  document.getElementById("needleReset").onclick = () => {
    localStorage.removeItem(LS_NEEDLE);
    bottle.src = "assets/compass_needle.png";
    preview.src = "assets/compass_needle.png";
  };

  document.getElementById("customAdd").onclick = () => {
    const msg = document.getElementById("customMsg");
    if (!userPos) { msg.textContent = "Sijaintia ei vielä saatu — käynnistä ensin ja salli sijainti."; return; }
    const nameEl = document.getElementById("customName");
    const name = nameEl.value.trim() || "Oma Alko";
    customStores.push({ name, lat: userPos.lat, lon: userPos.lon, hours: [], hoursKnown: true, country: "FI", custom: true });
    localStorage.setItem(LS_STORES, JSON.stringify(customStores));
    nameEl.value = "";
    msg.textContent = `Lisätty: ${name}`;
    rebuildStores();
    renderCustomList();
  };
}

// ---------- Boot ----------
loadCustom();
initSettings();
initMap();
initOfflineMaps();

fetch("alko_stores.json")
  .then((r) => r.json())
  .then((data) => { bundledStores = data; rebuildStores(); })
  .catch(() => showError("Myymälädataa ei voitu ladata."));

fetch("closed_dates.json")
  .then((r) => r.json())
  .then((byCountry) => {
    closedByCountry = {};
    for (const c in byCountry) closedByCountry[c] = new Set(byCountry[c]);
    updateHours();
  })
  .catch(() => {});

setInterval(updateHours, 1000); // tick the countdown every second

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => navigator.serviceWorker.register("sw.js").catch(() => {}));
}
