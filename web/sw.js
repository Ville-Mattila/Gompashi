// Gompashi PWA service worker — offline-first app shell + bundled store data.
// Bump CACHE when assets or data change so clients pick up the new version.
const CACHE = "gompashi-v19";
// Persistent, user-downloaded base-map tiles (kept across app-shell updates).
const TILE_CACHE = "gompashi-tiles";
const TILE_HOST = "basemaps.cartocdn.com";
const ASSETS = [
  "./",
  "./index.html",
  "./styles.css",
  "./app.js",
  "./manifest.webmanifest",
  "./alko_stores.json",
  "./closed_dates.json",
  "./assets/favicon-32.png",
  "./assets/favicon-48.png",
  "./assets/compass_needle.png",
  "./assets/compass_needle_north.png",
  "./assets/title.svg",
  "./assets/bitcount_single.ttf",
  "./assets/icon-192.png",
  "./assets/icon-512.png",
];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE && k !== TILE_CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  if (e.request.method !== "GET") return;
  const url = new URL(e.request.url);
  // Map tiles: serve from the downloaded-tiles cache first (works offline), else network.
  // Downloads populate this cache explicitly; we don't auto-add live tiles here.
  if (url.hostname === TILE_HOST) {
    e.respondWith(
      caches.open(TILE_CACHE).then((c) =>
        c.match(e.request).then((hit) => hit || fetch(e.request))
      )
    );
    return;
  }
  // Only the app shell is cached. Other cross-origin calls (e.g. the OSM routing API) go
  // straight to the network so routes stay fresh and offline failures fall back cleanly.
  if (url.origin !== self.location.origin) return;
  e.respondWith(
    caches.match(e.request).then((hit) =>
      hit ||
      fetch(e.request).then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
        return res;
      }).catch(() => hit)
    )
  );
});
