"use strict";

/**
 * Detects the single most common way to accidentally break this entire app:
 * opening one of these .html files directly from disk (double-click, or a
 * file:// URL) instead of serving it over http(s) (via `docker compose up`,
 * `npx serve frontend`, VS Code's "Live Server", etc.).
 *
 * Under file://, every `fetch(`${API_BASE_URL}/...`)` call in app.js,
 * dashboard.html and heatmap.html fails — not because the backend, the
 * model, or the disease-detection code is broken, but because the browser
 * sends `Origin: null` on a file:// page, and no server's CORS policy (this
 * one included — see backend `cropscanner.cors.allowed-origin`) can be
 * configured to allow that safely. The failures that result all LOOK like
 * unrelated bugs (a scan that silently never returns a result, an authority
 * dashboard that never shows alerts after "logging in", an outbreak map
 * stuck on "Loading…" forever) but share this one root cause.
 *
 * This banner is the fix for the *confusing failure mode*, not a
 * replacement for actually serving the app correctly — see README.md
 * "Running it".
 */
// Auto-configure backend API base URL when accessing from LAN / mobile phone
(function () {
  if (!window.CROPGUARD_API_BASE_URL && window.location.hostname) {
    if (window.location.hostname !== "localhost" && window.location.hostname !== "127.0.0.1" && window.location.protocol.startsWith("http")) {
      window.CROPGUARD_API_BASE_URL = window.location.protocol + "//" + window.location.hostname + ":8080";
    }
  }
})();

(function () {
  if (window.location.protocol !== "file:") return;

  document.addEventListener("DOMContentLoaded", () => {
    const banner = document.createElement("div");
    banner.setAttribute("role", "alert");
    banner.style.cssText = [
      "position:sticky", "top:0", "left:0", "right:0", "z-index:999",
      "background:#A6402D", "color:#fff", "padding:12px 16px",
      "font-family:system-ui,sans-serif", "font-size:0.9rem",
      "line-height:1.4", "text-align:center",
    ].join(";");
    banner.innerHTML =
      "This page was opened directly from a file (the address bar starts with <code>file://</code>). " +
      "Every diagnosis, login and map feature on this site needs to be served over <code>http://</code> " +
      "instead — that's why nothing here is loading. Run <code>docker compose up --build</code> from the " +
      "project folder and open <code>http://localhost:5173</code>, or serve the <code>frontend/</code> " +
      "folder with any static file server, then reload this page.";
    document.body.prepend(banner);
  });
})();
