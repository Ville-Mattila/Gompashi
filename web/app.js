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
  const from = `${userPos.lon},${userPos.lat}`;
  const to = `${target.store.lon},${target.store.lat}`;
  try {
    const res = await fetch(`${ROUTE_URL}${from};${to}?overview=full&geometries=geojson`);
    const d = await res.json();
    if (d.code === "Ok" && d.routes && d.routes[0]) {
      const r = d.routes[0];
      currentRoute = {
        coords: r.geometry.coordinates.map(([lon, lat]) => [lat, lon]),
        distance: r.distance,
        duration: r.duration,
      };
      routeKey = key;
      routeOrigin = { lat: userPos.lat, lon: userPos.lon };
    } else {
      routeCooldownUntil = Date.now() + 15000;
    }
  } catch (_) {
    routeCooldownUntil = Date.now() + 15000; // offline / failed: fall back to straight line
    if (key !== routeKey) currentRoute = null;
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

  // Frame just the start (you) and finish (store), with a margin proportional to the view.
  const minLat = Math.min(userPos.lat, store.lat), maxLat = Math.max(userPos.lat, store.lat);
  const minLon = Math.min(userPos.lon, store.lon), maxLon = Math.max(userPos.lon, store.lon);
  const marginX = W * 0.18, marginY = H * 0.18;

  // Pick the slippy-map zoom that frames the two endpoints with that margin.
  const lonFrac = Math.max((maxLon - minLon) / 360, 1e-9);
  const latFrac = Math.max(mercY(minLat) - mercY(maxLat), 1e-9);
  let z = Math.floor(Math.min(
    Math.log2(Math.max(W - 2 * marginX, 1) / (TILE * lonFrac)),
    Math.log2(Math.max(H - 2 * marginY, 1) / (TILE * latFrac)),
  ));
  z = Math.max(3, Math.min(19, z));
  const scale = TILE * Math.pow(2, z);
  const nT = Math.pow(2, z);
  const cWx = ((minLon + maxLon) / 2 + 180) / 360 * scale;
  const cWy = (mercY(minLat) + mercY(maxLat)) / 2 * scale;
  const sx0 = cWx - W / 2, sy0 = cWy - H / 2; // viewport top-left in world px
  const project = ([la, lo]) => [(lo + 180) / 360 * scale - sx0, mercY(la) * scale - sy0];

  // Dim base map: CARTO dark tiles, faint behind the route.
  ctx.globalAlpha = 0.55;
  for (let tx = Math.floor(sx0 / TILE); tx <= Math.floor((sx0 + W) / TILE); tx++) {
    for (let ty = Math.floor(sy0 / TILE); ty <= Math.floor((sy0 + H) / TILE); ty++) {
      if (ty < 0 || ty >= nT) continue;
      const wx = ((tx % nT) + nT) % nT;
      const img = getTile(TILE_URL(z, wx, ty), () => { if (mapOpen) drawMapThrottled(currentTarget()); });
      if (img) ctx.drawImage(img, tx * TILE - sx0, ty * TILE - sy0, TILE, TILE);
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
    info.textContent = "Reitti vaatii verkon — näytetään linnuntie";
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
