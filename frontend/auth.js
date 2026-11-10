"use strict";

/**
 * FOLIX farmer accounts — shared client (Stage 6A). Loaded on every farmer-facing page; NOT on the
 * legacy Authority Dashboard, which has its own separate portal.
 *
 * Authentication model, in one paragraph: the server sets an HttpOnly session cookie at login. This
 * script can neither read nor write it — it only asks the server (`credentials: "include"`) who the
 * cookie belongs to. Nothing about the login is kept in localStorage/sessionStorage, no token is ever
 * visible to page JavaScript, and no user id is ever sent to the server: the server decides who you
 * are from the cookie alone.
 *
 * Exposes `window.FolixAuth` and renders a small account control ("Log in" / name + "Log out") into the
 * header's `.header-tools`.
 */
(function () {
  // Same convention as the rest of the app: pages/tests may set window.CROPGUARD_API_BASE_URL.
  const API_BASE_URL = window.CROPGUARD_API_BASE_URL || "http://localhost:8080";
  const AUTH_URL = API_BASE_URL + "/api/auth";
  const PROFILE_URL = API_BASE_URL + "/api/farmer/profile";

  /** An API failure with the server's FOLIX error envelope unpacked. `status` 0 means the server was unreachable. */
  class FolixAuthError extends Error {
    constructor(message, { status = 0, code = "", fields = {} } = {}) {
      super(message);
      this.name = "FolixAuthError";
      this.status = status;
      this.code = code;
      this.fields = fields;
    }
    get isUnauthenticated() { return this.status === 401 && this.code === "UNAUTHENTICATED"; }
  }

  async function call(method, url, body) {
    let response;
    try {
      response = await fetch(url, {
        method,
        credentials: "include",
        cache: "no-store",
        headers: body === undefined ? undefined : { "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (networkErr) {
      throw new FolixAuthError("Could not reach the FOLIX server. Check your connection and try again.", { status: 0, code: "NETWORK" });
    }

    let payload = null;
    if (response.status !== 204) {
      try { payload = await response.json(); } catch (parseErr) { payload = null; }
    }

    if (!response.ok) {
      const err = payload && typeof payload === "object" && payload.error && typeof payload.error === "object" ? payload.error : null;
      const message = err && typeof err.message === "string" && err.message.length > 0 && err.message.length <= 300
        ? err.message
        : `The server returned an unexpected error (HTTP ${response.status}).`;
      const fields = err && err.fields && typeof err.fields === "object" ? err.fields : {};
      throw new FolixAuthError(message, { status: response.status, code: err && typeof err.code === "string" ? err.code : "", fields });
    }
    return payload;
  }

  // ---------------------------------------------------------------
  // API
  // ---------------------------------------------------------------

  let sessionPromise = null;

  /** `{ authenticated, farmer }`. Never throws: an unreachable server is reported as `{ authenticated: false, unreachable: true }`. */
  function getSession(forceRefresh) {
    if (!sessionPromise || forceRefresh) {
      sessionPromise = call("GET", AUTH_URL + "/session")
        .then((s) => ({ authenticated: !!(s && s.authenticated && s.farmer), farmer: s && s.farmer ? s.farmer : null }))
        .catch(() => ({ authenticated: false, farmer: null, unreachable: true }));
    }
    return sessionPromise;
  }

  async function login(email, password) {
    const farmer = await call("POST", AUTH_URL + "/login", { email, password });
    sessionPromise = Promise.resolve({ authenticated: true, farmer });
    return farmer;
  }

  function register(name, email, password) {
    return call("POST", AUTH_URL + "/register", { name, email, password });
  }

  async function logout() {
    await call("POST", AUTH_URL + "/logout");
    sessionPromise = Promise.resolve({ authenticated: false, farmer: null });
  }

  /** The authenticated farmer's profile, or rejects with a FolixAuthError (`isUnauthenticated` when logged out). */
  function getProfile() {
    return call("GET", PROFILE_URL);
  }

  /**
   * Only ever redirect to a plain page in this folder ("dashboard.html"), or the special value "close".
   * Anything else — an absolute URL, a path, a protocol-relative link — falls back to the Dashboard, so
   * the `?next=` parameter cannot be used as an open redirect.
   */
  function safeNext(value) {
    if (value === "close") return "close";
    return typeof value === "string" && /^[A-Za-z0-9_-]+\.html$/.test(value) ? value : "dashboard.html";
  }

  window.FolixAuth = { API_BASE_URL, FolixAuthError, getSession, login, register, logout, getProfile, safeNext };

  // ---------------------------------------------------------------
  // Header account control
  // ---------------------------------------------------------------

  function firstName(name) {
    const n = String(name || "").trim().split(/\s+/)[0];
    return n || "Account";
  }

  function renderWidget(slot, session) {
    slot.textContent = "";
    if (session.authenticated) {
      const link = document.createElement("a");
      link.className = "auth-name";
      link.href = "profile.html";
      link.title = "Your account";
      link.textContent = firstName(session.farmer.name);

      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "auth-logout";
      btn.textContent = "Log out";
      btn.addEventListener("click", async () => {
        btn.disabled = true;
        try {
          await logout();
        } catch (err) {
          btn.disabled = false;
          btn.textContent = "Retry log out";
          btn.title = err && err.message ? err.message : "Could not log out.";
          return;
        }
        const page = document.body.dataset.page || "";
        if (page === "dashboard" || page === "profile") {
          window.location.href = "login.html";
        } else {
          renderWidget(slot, { authenticated: false, farmer: null });
        }
      });
      slot.append(link, btn);
    } else {
      const link = document.createElement("a");
      link.className = "auth-login";
      link.href = "login.html";
      link.textContent = "Log in";
      slot.append(link);
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    // The legacy Authority Dashboard is a separate portal; never put farmer login on it.
    if (document.body.dataset.page === "authority" || document.body.dataset.authWidget === "off") return;
    const tools = document.querySelector(".header-tools");
    if (!tools) return;

    const slot = document.createElement("div");
    slot.className = "auth-widget";
    slot.id = "authWidget";
    const toggle = document.getElementById("navToggle");
    tools.insertBefore(slot, toggle && toggle.parentNode === tools ? toggle : null);

    getSession().then((session) => renderWidget(slot, session));
  });
})();
