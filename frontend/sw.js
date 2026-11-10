"use strict";

/**
 * Offline-first mode (Tier 2 upgrade #4). This service worker caches the
 * app shell (HTML/CSS/JS) so FOLIX opens and is usable with no
 * connection at all — the highest-impact reliability upgrade for the
 * low-connectivity rural areas this app targets.
 *
 * What offline mode actually gets you, honestly:
 *  - The app loads and the camera/upload UI works with zero network.
 *  - A photo taken offline is queued in IndexedDB (see offline-classifier.js).
 *    If a real exported on-device model is installed at ./model/ (see
 *    onnx-classifier.js and inference-service/scripts/export_onnx_model.py),
 *    it gets a genuine local ML diagnosis immediately; otherwise it's
 *    honestly reported as "analysis unavailable" rather than guessed at —
 *    FOLIX's architecture explicitly forbids a color-heuristic fallback
 *    pretending to be a diagnosis.
 *  - When connectivity returns, queued photos are automatically re-submitted
 *    to the real backend for an authoritative diagnosis.
 *
 * The model files are cached opportunistically below (best-effort — a
 * missing/not-yet-exported model simply means those two addAll entries are
 * absent, which cache.addAll would otherwise fail the whole install on, so
 * they're added via a separate best-effort step instead of the required
 * APP_SHELL list).
 */
// v7: Stage 4 — scanner-experience.js/scanner.html changed; the cache-first shell must not serve the old no-op scanner.
// v8: Stage 6A — header now loads auth.js and styles.css gained the account control; drop the v7 shell.
// v9: Stage 6C — heatmap.html now loads location.js + disease-map.js (explicit location sharing, real Disease Map data).
// v11: Home hero is now the cursor-driven footage (index.html, styles.css, hero-media.js changed; poster replaces placeholder.svg).
// v12: Multi-page navigation fix — every local HTML page (and its local CSS/JS)
// is now in the app shell, and navigation requests are network-first with a
// same-page cache fallback instead of always falling back to index.html.
// v13: Scanner now wires up the offline queue + on-device classifier (see
// scanner-experience.js) — onnx-classifier.js and offline-classifier.js are
// required app-shell files so the offline path itself works with no
// connection; the model files stay best-effort (see OPTIONAL_MODEL_FILES).
// v14: scanner-experience.js/.css changed (low-confidence results now show severity %, spread-chance % and a real
// Forward Case flow) and every page's nav gained the "Authority" link; dashboard.js now shows saved severity/spread %. Static assets are cache-first, so the
// shell version MUST change or browsers keep running the old scanner.
// v15: new Authority area (authority.html/.css/.js); login.html now routes the Authority role there.
// v16: how-it-works CTA buttons no longer reuse .hero-actions (they overlapped the heading); the app shell was also
// v17: Workflow demo video added to how-it-works.html.
const CACHE_NAME = "folix-shell-v17";
const APP_SHELL = [
  "./",
  "./index.html",
  "./how-it-works.html",
  "./scanner.html",
  "./agricultural-network.html",
  "./dashboard.html",
  "./heatmap.html",
  "./farmer-alerts.html",
  "./login.html",
  "./register.html",
  "./profile.html",
  "./authority-dashboard.html",
  "./authority.html",
  "./styles.css",
  "./how-it-works.css",
  "./workflow-video.css",
  "./scanner-experience.css",
  "./auth.css",
  "./dashboard.css",
  "./agri-network.css",
  "./authority.css",
  "./farmer-alerts.css",
  "./scanner-experience.js",
  "./onnx-classifier.js",
  "./offline-classifier.js",
  "./i18n.js",
  "./protocol-check.js",
  "./nav.js",
  "./auth.js",
  "./auth-pages.js",
  "./location.js",
  "./disease-map.js",
  "./hero-media.js",
  "./hero-media-mobile.js",
  "./farmer-alerts.js",
  "./how-it-works.js",
  "./workflow-video.js",
  "./agri-network.js",
  "./dashboard.js",
  "./authority.js",
  "./assets/hero/hero-poster.webp",
  "./manifest.webmanifest",
];
// Best-effort: only cached if actually present (see export_onnx_model.py).
const OPTIONAL_MODEL_FILES = ["./model/model.onnx", "./model/labels.json"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(APP_SHELL).then(() => cache))
      .then((cache) => Promise.all(
        OPTIONAL_MODEL_FILES.map((path) =>
          fetch(path).then((r) => (r.ok ? cache.put(path, r) : null)).catch(() => null)
        )
      ))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);

  // Never cache/intercept API calls — those need real network round-trips
  // and have their own offline-queue handling in offline-classifier.js.
  if (url.pathname.startsWith("/api/")) {
    return;
  }

  // Navigation requests (loading a page): network-first, so the user always
  // gets the current page when online. If offline, fall back to that same
  // page from cache — never to index.html standing in for a different page.
  if (event.request.mode === "navigate") {
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy)).catch(() => {});
          return response;
        })
        .catch(() =>
          caches.match(event.request, { ignoreSearch: true }).then((cached) =>
            cached || new Response("", { status: 504, statusText: "Offline" })
          )
        )
    );
    return;
  }

  // Static assets (CSS/JS/images/etc.): cache-first, falling back to network,
  // same offline behavior as before.
  event.respondWith(
    caches.match(event.request).then((cached) => {
      if (cached) return cached;
      return fetch(event.request).catch(() => new Response("", { status: 504, statusText: "Offline" }));
    })
  );
});
