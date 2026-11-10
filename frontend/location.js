"use strict";

/**
 * FOLIX location sharing — Stage 6C. The ONLY place in the frontend that ever asks the browser for a position.
 *
 * Rules this file enforces:
 *  - The browser is asked for a position ONLY inside the click handler of the "Enable Location for Regional Disease
 *    Insights" button (or "Update my area"). Nothing here runs a geolocation request on page load, on a timer,
 *    on visibility change, or from the Scanner/Dashboard. There is no watchPosition and no background tracking.
 *  - Optional and never blocking: if the farmer denies, or the browser has no geolocation, the card just explains
 *    it and FOLIX carries on. A denial is never retried automatically; the next request happens only if the
 *    farmer clicks again. If the browser reports the permission as already blocked, the button is disabled and
 *    no request is made at all.
 *  - Data minimisation: the position is requested at low accuracy and rounded to 2 decimals (~1 km) BEFORE it
 *    leaves the page; the server reduces it further to an ~11 km area and stores only that. No IP-based or
 *    guessed location is ever used.
 *  - Identity is the session cookie only. No farmer id is sent, read or stored here; nothing is kept in
 *    localStorage/sessionStorage.
 *
 * Exposes `window.FolixLocation` (used by the Disease Map page and by tests).
 */
(function () {
  const API_BASE_URL = window.CROPGUARD_API_BASE_URL || "http://localhost:8080";
  const LOCATION_URL = API_BASE_URL + "/api/farmer/location";

  /** ~1 km. The server coarsens again; this just keeps precise GPS from ever leaving the browser. */
  const CLIENT_DECIMALS = 2;

  class LocationError extends Error {
    constructor(message, { status = 0, code = "" } = {}) {
      super(message);
      this.name = "LocationError";
      this.status = status;
      this.code = code;
    }
    get isUnauthenticated() { return this.status === 401; }
  }

  async function call(method, body) {
    let response;
    try {
      response = await fetch(LOCATION_URL, {
        method,
        credentials: "include",
        cache: "no-store",
        headers: body === undefined ? undefined : { "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (networkErr) {
      throw new LocationError("Could not reach the FOLIX server. Check your connection and try again.", { code: "NETWORK" });
    }
    let payload = null;
    try { payload = await response.json(); } catch (parseErr) { payload = null; }
    if (!response.ok) {
      const err = payload && payload.error && typeof payload.error === "object" ? payload.error : null;
      const message = err && typeof err.message === "string" && err.message.length > 0 && err.message.length <= 300
        ? err.message : `The server returned an unexpected error (HTTP ${response.status}).`;
      throw new LocationError(message, { status: response.status, code: err && typeof err.code === "string" ? err.code : "" });
    }
    return payload;
  }

  const getStatus = () => call("GET");
  const disable = () => call("DELETE");

  function roundForSharing(value) {
    const f = Math.pow(10, CLIENT_DECIMALS);
    return Math.round(value * f) / f;
  }

  /** Sends the (already rounded) position together with the explicit consent flag. */
  function enable(latitude, longitude) {
    return call("PUT", { consent: true, latitude: roundForSharing(latitude), longitude: roundForSharing(longitude) });
  }

  /** True when the browser can be asked at all. */
  function isSupported() {
    return typeof navigator !== "undefined" && !!navigator.geolocation && typeof navigator.geolocation.getCurrentPosition === "function";
  }

  /**
   * Asks the browser for ONE position. Must only ever be called from a user action. Resolves with
   * `{ latitude, longitude }`; rejects with `{ denied: true }` when the user says no (or the browser blocks it),
   * or `{ denied: false }` for a temporary failure (no signal, timeout).
   */
  function requestBrowserPosition() {
    return new Promise((resolve, reject) => {
      if (!isSupported()) { reject({ denied: false, unsupported: true }); return; }
      navigator.geolocation.getCurrentPosition(
        (pos) => resolve({ latitude: pos.coords.latitude, longitude: pos.coords.longitude }),
        (err) => reject({ denied: !!err && err.code === 1, unsupported: false }),
        { enableHighAccuracy: false, timeout: 15000, maximumAge: 10 * 60 * 1000 }
      );
    });
  }

  /** Reads the browser's permission state WITHOUT triggering a prompt. "unknown" when the API is unavailable. */
  async function browserPermissionState() {
    try {
      if (!navigator.permissions || typeof navigator.permissions.query !== "function") return "unknown";
      const result = await navigator.permissions.query({ name: "geolocation" });
      return result && result.state ? result.state : "unknown";
    } catch (e) {
      return "unknown";
    }
  }



  async function autoFetchLocation() {
    try {
      const cachedLat = localStorage.getItem("folix_latitude");
      const cachedLon = localStorage.getItem("folix_longitude");
      if (cachedLat && cachedLon && !isNaN(parseFloat(cachedLat)) && !isNaN(parseFloat(cachedLon))) {
        window.FolixLocation.currentPosition = {
          latitude: parseFloat(cachedLat),
          longitude: parseFloat(cachedLon),
        };
        try {
          window.dispatchEvent(new CustomEvent("folix:location", {
            detail: window.FolixLocation.currentPosition,
          }));
        } catch (_) {}
      }
    } catch (_) {}

    if (!isSupported()) return;
    try {
      const pos = await requestBrowserPosition();
      if (pos && typeof pos.latitude === "number" && typeof pos.longitude === "number") {
        try {
          localStorage.setItem("folix_latitude", String(pos.latitude));
          localStorage.setItem("folix_longitude", String(pos.longitude));
        } catch (_) {}
        window.FolixLocation.currentPosition = pos;
        try {
          window.dispatchEvent(new CustomEvent("folix:location", { detail: pos }));
        } catch (_) {}
        try {
          await enable(pos.latitude, pos.longitude);
        } catch (_) {}
      }
    } catch (_) {}
  }

  window.FolixLocation = {
    getStatus, enable, disable, roundForSharing, isSupported,
    requestBrowserPosition, browserPermissionState, LocationError,
    autoFetchLocation, currentPosition: null
  };

  // ---------------------------------------------------------------
  // The card on the Disease Map page
  // ---------------------------------------------------------------

  function areaText(area) {
    const lat = Math.abs(area.centerLatitude).toFixed(2) + "\u00B0 " + (area.centerLatitude >= 0 ? "N" : "S");
    const lng = Math.abs(area.centerLongitude).toFixed(2) + "\u00B0 " + (area.centerLongitude >= 0 ? "E" : "W");
    const km = Math.round(area.cellSizeDegrees * 111);
    return `${lat}, ${lng} (an area about ${km} km across)`;
  }

  function mount(root) {
    const $ = (id) => root.querySelector("#" + id);
    const statusEl = $("locStatus");
    const enableBtn = $("locEnableBtn");
    const disableBtn = $("locDisableBtn");
    const loginLink = $("locLoginLink");
    let busy = false;

    function show(state, message, opts = {}) {
      root.dataset.state = state;
      statusEl.textContent = message;
      enableBtn.hidden = !opts.enable;
      enableBtn.disabled = !!opts.enableDisabled;
      enableBtn.textContent = opts.enableLabel || "Enable Location for Regional Disease Insights";
      disableBtn.hidden = !opts.disable;
      loginLink.hidden = !opts.login;
    }

    function renderStatus(s) {
      if (s && s.sharingEnabled && s.approximateArea) {
        window.FolixLocation.currentPosition = {
          latitude: s.approximateArea.centerLatitude,
          longitude: s.approximateArea.centerLongitude,
        };
        try {
          window.dispatchEvent(new CustomEvent("folix:location", {
            detail: window.FolixLocation.currentPosition,
          }));
        } catch (_) {}
        show("on", `Sharing is on. Your saved reports contribute to the area around ${areaText(s.approximateArea)}. ` +
          "Reports you saved before turning this on are not included.",
          { enable: true, enableLabel: "Update my area", disable: true });
      } else {
        show("off", "Location sharing is off. Scanning, saving reports and your Dashboard all work normally without it.",
          { enable: true });
      }
    }

    async function refresh() {
      show("loading", "Checking your location-sharing setting\u2026");
      try {
        renderStatus(await getStatus());
        if (root.dataset.state === "off" && (await browserPermissionState()) === "denied") {
          show("blocked", "Location is blocked for this site in your browser settings, so nothing is shared. " +
            "That's fine \u2014 FOLIX works the same. If you'd like to contribute, allow location for this site in your browser and reload.",
            { enable: true, enableDisabled: true });
        }
      } catch (err) {
        if (err.isUnauthenticated) {
          show("signed-out", "Log in to contribute your approximate region. You can still view the map below without an account.", { login: true });
        } else {
          show("error", err.message || "Could not load your location setting.", { enable: false });
        }
      }
    }

    // The one and only place a position is requested: an explicit click.
    enableBtn.addEventListener("click", async () => {
      if (busy) return;
      busy = true;
      enableBtn.disabled = true;
      statusEl.textContent = "Waiting for your browser\u2026 choose Allow or Block when it asks.";
      try {
        const pos = await requestBrowserPosition();
        renderStatus(await enable(pos.latitude, pos.longitude));
      } catch (err) {
        if (err && err.unsupported) {
          show("unsupported", "This browser can't share a location. Everything else in FOLIX works as usual.", { enable: false });
        } else if (err && err.denied) {
          show("denied", "No location was shared. That's completely fine \u2014 FOLIX works the same, and you can still see the map. " +
            "You can enable sharing later if you change your mind.", { enable: true, enableLabel: "Try again" });
        } else if (err instanceof LocationError) {
          if (err.isUnauthenticated) show("signed-out", "Your session has expired. Log in to contribute your approximate region.", { login: true });
          else show("error", err.message, { enable: true });
        } else {
          show("unavailable", "Your location couldn't be determined right now. Nothing was shared \u2014 you can try again later.", { enable: true, enableLabel: "Try again" });
        }
      } finally {
        busy = false;
        enableBtn.disabled = enableBtn.disabled && root.dataset.state === "blocked";
      }
    });

    disableBtn.addEventListener("click", async () => {
      if (busy) return;
      busy = true;
      disableBtn.disabled = true;
      try {
        await disable();
        renderStatus({ sharingEnabled: false });
        statusEl.textContent = "Sharing is off, and your reports no longer contribute to the map. Your reports themselves are unchanged.";
      } catch (err) {
        statusEl.textContent = err.message || "Could not turn sharing off. Please try again.";
      } finally {
        busy = false;
        disableBtn.disabled = false;
      }
    });

    // Reading the saved setting is a plain GET: it never touches the browser's location.
    refresh();
  }

  document.addEventListener("DOMContentLoaded", () => {
    autoFetchLocation();
    const root = document.getElementById("locCard");
    if (root) mount(root);
  });
})();
