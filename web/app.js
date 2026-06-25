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
const toggleEl = document.getElementById("toggle");
const segs = [...document.querySelectorAll(".seg")];
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

// ---------- Render ----------
function render() {
  if (!userPos || !stores.length) return;
  const ranked = rankStores(userPos.lat, userPos.lon, stores);
  const rank = Math.min(selectedRank, ranked.length - 1);
  const target = ranked[rank];

  renderDistance(formatDistance(target.dist), target.dist);
  storeEl.textContent = target.store.name;
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

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => navigator.serviceWorker.register("sw.js").catch(() => {}));
}
