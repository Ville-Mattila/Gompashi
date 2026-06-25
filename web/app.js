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
  if (m < 1000) return Math.round(m / 10) * 10 + " m";
  return (m / 1000).toFixed(1) + " km";
}

// ---------- State ----------
let stores = [];
let userPos = null;
let azimuth = NaN;       // device heading, 0 = north, clockwise
let tiltBeta = 0, tiltGamma = 0;
let selectedRank = 0;
let hasCompass = false;
let gotOrientation = false;
const cont = {};         // continuous (unwrapped) rotation per element

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

let closedDates = new Set();   // Alko public-holiday closed dates (YYYY-MM-DD)
let currentHours = null;       // selected store's 7-day schedule
let currentHoursKnown = true;
const overlay = document.getElementById("overlay");
const overlayText = document.getElementById("overlayText");
const overlayBtn = document.getElementById("overlayBtn");

// ---------- Rotation + tilt ----------
function lowpass(target, prev, alpha = 0.15) {
  return isNaN(prev) ? target : prev + alpha * (target - prev);
}
function lowpassAngle(target, prev, alpha = 0.2) {
  if (isNaN(prev)) return target;
  return (prev + alpha * smallestAngleDelta(prev, target) + 360) % 360;
}
function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

function setRotation(el, key, targetDeg) {
  let c = key in cont ? cont[key] : targetDeg;
  c += smallestAngleDelta(c, targetDeg);
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
function openingStatus(now, hours) {
  for (let offset = 0; offset < 14; offset++) {
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset);
    const sched = closedDates.has(dateKey(day)) ? null : hours[dowMon0(day)];
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
  const st = openingStatus(now, currentHours);
  if (!st) { hoursEl.textContent = ""; hoursNoteEl.textContent = ""; return; }
  const label = st.state === "open" ? "Auki vielä " : "Aukeaa ";
  hoursEl.innerHTML = `<span class="${st.state}">${label}${fmtDur(st.at - now)}</span>`;
  hoursNoteEl.textContent = currentHoursKnown ? "" : "aukioloaika ei tiedossa — vakioajat käytössä";
}

// ---------- Render ----------
function render() {
  if (!userPos || !stores.length) return;
  const ranked = rankStores(userPos.lat, userPos.lon, stores);
  const rank = Math.min(selectedRank, ranked.length - 1);
  const target = ranked[rank];

  renderDistance(formatDistance(target.dist), target.dist);
  storeEl.textContent = target.store.name;
  currentHours = target.store.hours;
  currentHoursKnown = target.store.hoursKnown;
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
  window.addEventListener("deviceorientationabsolute", onOrientation, true);
  window.addEventListener("deviceorientation", onOrientation, true);
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

// ---------- Boot ----------
fetch("alko_stores.json")
  .then((r) => r.json())
  .then((data) => { stores = data; })
  .catch(() => showError("Myymälädataa ei voitu ladata."));

fetch("closed_dates.json")
  .then((r) => r.json())
  .then((dates) => { closedDates = new Set(dates); updateHours(); })
  .catch(() => {});

setInterval(updateHours, 1000); // tick the countdown every second

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => navigator.serviceWorker.register("sw.js").catch(() => {}));
}
