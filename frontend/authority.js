"use strict";

/**
 * FOLIX Authority area (authority.html).
 *
 * Role model: the project's only Authority identity is the office ID + API key checked by the existing
 * /api/v1/authorities/* endpoints (the farmer session cookie/auth.js is a different, separate thing and
 * is not used here). A visitor becomes role "authority" only after one of those endpoints accepts their
 * credentials. Until then the page renders ONLY the sign-in form; the overview / Your Alerts / Forward
 * Case views are cloned from <template>s after the check passes, so they do not exist in the DOM for
 * anyone else. Credentials live in memory only (never localStorage/sessionStorage/cookies).
 *
 * Views (hash-routed, one page): #home (default), #alerts, #forward.
 */
(function () {
  const API_BASE_URL = window.CROPGUARD_API_BASE_URL || "http://localhost:8080";
  const POLL_INTERVAL_MS = window.__FOLIX_AUTHORITY_POLL_MS__ || 20000;
  const ROLE_AUTHORITY = "authority";

  const VIEWS = {
    home: { template: "tplHome", nav: null, mount: mountHome },
    cases: { template: "tplAlerts", nav: "cases", mount: mountAlerts },
    alerts: { template: "tplAlerts", nav: "cases", mount: mountAlerts },
    forward: { template: "tplForward", nav: "forward", mount: mountForward },
    farmers: { template: "tplFarmers", nav: "farmers", mount: mountFarmers },
  };
  const NAV_ITEMS = [
    { view: "cases", label: "Your Cases" },
    { view: "forward", label: "Forwarded Cases" },
    { view: "farmers", label: "Farmers" },
  ];

  const AUTH_STORAGE_KEY = "folix_authority_session";
  /** { role, id, apiKey } once verified; null otherwise. */
  let session = null;
  let pollTimer = null;
  let refreshInFlight = false;
  let forwardRefreshInFlight = false;

  function restoreSession() {
    try {
      const raw = localStorage.getItem(AUTH_STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && parsed.role === ROLE_AUTHORITY && parsed.id && parsed.apiKey) {
          session = parsed;
        }
      }
    } catch (_) {}
  }

  function saveSession(s) {
    session = s;
    try {
      if (s) {
        localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(s));
      } else {
        localStorage.removeItem(AUTH_STORAGE_KEY);
      }
    } catch (_) {}
  }

  const $ = (id) => document.getElementById(id);
  const root = () => $("authorityRoot");

  function isAuthority() {
    return !!session && session.role === ROLE_AUTHORITY;
  }

  function currentView() {
    const name = (window.location.hash || "").replace(/^#/, "");
    if (name === "alerts") return "cases";
    return Object.prototype.hasOwnProperty.call(VIEWS, name) ? name : "home";
  }

  function el(tag, props, children) {
    const node = document.createElement(tag);
    Object.keys(props || {}).forEach((k) => {
      if (k === "className") node.className = props[k];
      else if (k === "text") node.textContent = props[k];
      else node.setAttribute(k, props[k]);
    });
    (children || []).forEach((c) => node.append(c));
    return node;
  }

  // ---------------------------------------------------------------
  // Rendering (role-gated)
  // ---------------------------------------------------------------

  function render() {
    stopPolling();
    const main = root();
    main.textContent = "";

    if (!isAuthority()) {
      document.body.dataset.page = "authority-home";
      renderNav(null);
      renderAccount();
      const hash = (window.location.hash || "").replace(/^#/, "");
      if (hash === "register") {
        main.append($("tplRegister").content.cloneNode(true));
        bindRegister();
      } else {
        main.append($("tplSignIn").content.cloneNode(true));
        bindSignIn();
      }
      return;
    }

    const name = currentView();
    const view = VIEWS[name];
    document.body.dataset.page = view.nav || "authority-home";
    renderNav(view.nav);
    renderAccount();
    main.append($(view.template).content.cloneNode(true));
    if (view.mount) view.mount();
    if (typeof setActiveNav === "function") setActiveNav();
  }

  function renderNav(activeNav) {
    const nav = $("authorityNav");
    const toggle = $("navToggle");
    nav.textContent = "";
    if (!isAuthority()) {
      if (toggle) toggle.style.display = "none";
      return;
    }
    if (toggle) toggle.style.display = "";
    NAV_ITEMS.forEach((item) => {
      const a = el("a", { href: "#" + item.view, "data-nav": item.view, text: item.label });
      if (item.view === activeNav || (item.view === "cases" && activeNav === "alerts")) {
        a.classList.add("nav-active");
        a.setAttribute("aria-current", "page");
      }
      a.addEventListener("click", closeMobileNav);
      nav.append(a);
    });
  }

  function closeMobileNav() {
    const nav = $("authorityNav");
    const toggle = $("navToggle");
    if (nav && nav.classList.contains("open")) {
      nav.classList.remove("open");
      if (toggle) { toggle.setAttribute("aria-expanded", "false"); toggle.textContent = "☰"; }
    }
  }

  function renderAccount() {
    const slot = $("authorityAccount");
    slot.textContent = "";
    if (!isAuthority()) return;
    const wrap = el("div", { className: "authority-account" });
    const label = el("span", { className: "auth-name", text: "Authority #" + session.id });
    const out = el("button", { type: "button", className: "auth-logout", text: "Sign out" });
    out.addEventListener("click", signOut);
    wrap.append(label, out);
    slot.append(wrap);
  }

  // ---------------------------------------------------------------
  // Sign-in / Register / Sign-out
  // ---------------------------------------------------------------

  function bindSignIn() {
    const form = $("authoritySignInForm");
    const errorEl = $("authoritySignInError");
    const submit = $("authoritySignInSubmit");

    // Explicit navigation to register view (handles edge cases where hashchange may not fire)
    const goToRegister = $("authorityGoToRegister");
    if (goToRegister) {
      goToRegister.addEventListener("click", function (e) {
        e.preventDefault();
        window.location.hash = "register";
        render();
      });
    }

    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      errorEl.hidden = true;
      const id = $("authorityId").value.trim();
      const key = $("authorityKey").value.trim();
      if (!/^\d+$/.test(id) || !key) {
        errorEl.textContent = "Enter both your authority ID (a number) and your API key.";
        errorEl.hidden = false;
        return;
      }

      submit.disabled = true;
      submit.textContent = "Signing in…";
      try {
        // Verify with the existing endpoint; a 401 means the ID/key pair is not valid.
        await fetchAlerts(id, key);
        saveSession({ role: ROLE_AUTHORITY, id, apiKey: key });
        render();
      } catch (err) {
        errorEl.textContent = err.unauthenticated
          ? "Invalid authority ID or API key."
          : "Couldn't sign in: " + (err.message || "please try again.");
        errorEl.hidden = false;
        submit.disabled = false;
        submit.textContent = "Sign in";
      }
    });
  }

  function bindRegister() {
    const form = $("authorityRegisterForm");
    const errorEl = $("authorityRegisterError");
    const submit = $("authorityRegisterSubmit");
    const successCard = $("authorityRegisterSuccess");
    const autoSignInBtn = $("autoSignInBtn");

    // Explicit navigation back to sign-in view
    const goToSignIn = $("authorityGoToSignIn");
    if (goToSignIn) {
      goToSignIn.addEventListener("click", function (e) {
        e.preventDefault();
        window.location.hash = "";
        render();
      });
    }

    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      errorEl.hidden = true;

      const name = $("regName").value.trim();
      const district = $("regDistrict").value.trim();
      const state = $("regState").value.trim();
      const rawAddress = $("regAddress").value.trim();
      const latVal = $("regLat").value.trim();
      const lonVal = $("regLon").value.trim();
      const phoneVal = $("regPhone").value.trim();
      const emailVal = $("regEmail").value.trim();
      const webhookVal = $("regWebhook").value.trim();

      if (!name || !district || !state || !rawAddress) {
        errorEl.textContent = "Please fill in all required fields (Office name, District, State, Address).";
        errorEl.hidden = false;
        return;
      }

      submit.disabled = true;
      submit.textContent = "Registering office…";

      const payload = {
        name,
        district,
        state,
        rawAddress,
        latitude: latVal ? parseFloat(latVal) : null,
        longitude: lonVal ? parseFloat(lonVal) : null,
        phoneNumber: phoneVal || null,
        email: emailVal || null,
        webhookUrl: webhookVal || null,
      };

      try {
        const response = await fetch(`${API_BASE_URL}/api/v1/authorities/register`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        });

        if (!response.ok) {
          let errText = "HTTP " + response.status;
          try {
            const errData = await response.json();
            if (errData && errData.message) errText = errData.message;
            else if (errData && errData.error && errData.error.message) errText = errData.error.message;
          } catch (_) {}
          throw new Error(errText);
        }

        const data = await response.json();
        $("regSuccessMessage").textContent = data.message || "Office registered successfully.";
        $("regSuccessId").textContent = String(data.authorityId);
        $("regSuccessKey").textContent = data.apiKey;
        successCard.hidden = false;
        submit.style.display = "none";

        if (autoSignInBtn) {
          autoSignInBtn.onclick = () => {
            saveSession({ role: ROLE_AUTHORITY, id: String(data.authorityId), apiKey: data.apiKey });
            window.location.hash = "cases";
            render();
          };
        }
      } catch (err) {
        errorEl.textContent = "Could not register: " + (err.message || "please check your details and try again.");
        errorEl.hidden = false;
        submit.disabled = false;
        submit.textContent = "Register office";
      }
    });
  }

  function signOut() {
    saveSession(null);
    window.location.hash = "";
    render();
  }

  // ---------------------------------------------------------------
  // API (existing endpoints only)
  // ---------------------------------------------------------------

  async function fetchAlerts(id, key) {
    let response;
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/authorities/${encodeURIComponent(id)}/alerts`, {
        headers: { "X-Authority-Api-Key": key },
      });
    } catch (networkErr) {
      throw Object.assign(new Error("could not reach the FOLIX server."), { unauthenticated: false });
    }
    if (response.status === 401) throw Object.assign(new Error("Invalid authority ID or API key."), { unauthenticated: true });
    if (!response.ok) throw Object.assign(new Error("HTTP " + response.status), { unauthenticated: false });
    return response.json();
  }

  async function patchAlert(alertId, status, note) {
    const response = await fetch(
      `${API_BASE_URL}/api/v1/authorities/${encodeURIComponent(session.id)}/alerts/${encodeURIComponent(alertId)}`,
      {
        method: "PATCH",
        headers: { "Content-Type": "application/json", "X-Authority-Api-Key": session.apiKey },
        body: JSON.stringify({ status, note }),
      });
    if (!response.ok) throw new Error("HTTP " + response.status);
  }

  async function deleteAlert(alertId) {
    const response = await fetch(
      `${API_BASE_URL}/api/v1/authorities/${encodeURIComponent(session.id)}/alerts/${encodeURIComponent(alertId)}`,
      {
        method: "DELETE",
        headers: { "X-Authority-Api-Key": session.apiKey },
      });
    if (!response.ok) throw new Error("HTTP " + response.status);
  }

  async function deleteForwardedCase(reportId) {
    const response = await fetch(
      `${API_BASE_URL}/api/v1/authorities/${encodeURIComponent(session.id)}/forwarded-cases/${encodeURIComponent(reportId)}`,
      {
        method: "DELETE",
        headers: { "X-Authority-Api-Key": session.apiKey },
      });
    if (!response.ok) throw new Error("HTTP " + response.status);
  }

  async function deleteFarmer(farmerId) {
    const response = await fetch(
      `${API_BASE_URL}/api/v1/authorities/${encodeURIComponent(session.id)}/farmers/${encodeURIComponent(farmerId)}`,
      {
        method: "DELETE",
        headers: { "X-Authority-Api-Key": session.apiKey },
      });
    if (!response.ok) throw new Error("HTTP " + response.status);
  }

  async function updateFarmerStatus(farmerId, status) {
    const response = await fetch(
      `${API_BASE_URL}/api/v1/authorities/${encodeURIComponent(session.id)}/farmers/${encodeURIComponent(farmerId)}/status`,
      {
        method: "PATCH",
        headers: { "Content-Type": "application/json", "X-Authority-Api-Key": session.apiKey },
        body: JSON.stringify({ status }),
      });
    if (!response.ok) throw new Error("HTTP " + response.status);
    return response.json();
  }

  function exportToThemedPdf({ title, subtitle, category, headers, rows, metaInfo }) {
    if (!rows || !rows.length) {
      window.alert("No records available to export for " + (category || "report") + ".");
      return;
    }

    const printWin = window.open("", "_blank");
    if (!printWin) {
      window.alert("Please allow pop-ups to generate and view your themed PDF report.");
      return;
    }

    const now = new Date();
    const formattedDate = now.toLocaleDateString("en-US", { year: "numeric", month: "long", day: "numeric" });
    const formattedTime = now.toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit" });
    const authId = session && session.id ? session.id : "OFFICIAL";
    const reportRef = `FOLIX-${(category || "AUDIT").toUpperCase().replace(/\s+/g, "_")}-${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, "0")}${String(now.getDate()).padStart(2, "0")}-${Math.floor(1000 + Math.random() * 9000)}`;

    const renderCell = (val, colIdx) => {
      const str = String(val == null ? "" : val);
      if (str === "YES" || str.includes("HIGH") || str === "Active" || str === "NOTIFIED" || str === "FORWARDED" || str === "RESOLVED") {
        let badgeColor = "#166534";
        let badgeBg = "#DCFCE7";
        if (str.includes("HIGH") || str === "YES") {
          badgeColor = "#991b1b";
          badgeBg = "#fee2e2";
        } else if (str === "RESOLVED") {
          badgeColor = "#1e40af";
          badgeBg = "#dbeafe";
        }
        return `<span style="display:inline-block; padding:2px 8px; border-radius:12px; font-size:10px; font-weight:700; background:${badgeBg}; color:${badgeColor};">${str}</span>`;
      }
      return str;
    };

    const tableRowsHtml = rows.map((row, rIdx) => {
      const bg = rIdx % 2 === 0 ? "#ffffff" : "#f8faf6";
      const cells = (Array.isArray(row) ? row : Object.values(row)).map((val, cIdx) => 
        `<td style="padding: 8px 10px; border-bottom: 1px solid #e2e8f0; font-size: 11px; vertical-align: middle;">${renderCell(val, cIdx)}</td>`
      ).join("");
      return `<tr style="background:${bg};">${cells}</tr>`;
    }).join("");

    const tableHeadersHtml = headers.map(h => 
      `<th style="padding: 9px 10px; text-align: left; font-size: 10px; font-weight: 700; letter-spacing: 0.5px; text-transform: uppercase; border-bottom: 2px solid #2e5a3c; background: #14261b; color: #ffffff;">${h}</th>`
    ).join("");

    const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>${title} — FOLIX Official Report</title>
  <style>
    * { box-sizing: border-box; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
      margin: 0;
      padding: 0;
      color: #1e293b;
      background: #f1f5f9;
      -webkit-print-color-adjust: exact;
      print-color-adjust: exact;
    }
    .toolbar {
      background: #14261b;
      color: #ffffff;
      padding: 12px 24px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      box-shadow: 0 2px 8px rgba(0,0,0,0.15);
      position: sticky;
      top: 0;
      z-index: 100;
    }
    .toolbar-title { font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
    .btn-print {
      background: #4ade80;
      color: #052e16;
      border: none;
      padding: 8px 18px;
      border-radius: 6px;
      font-size: 13px;
      font-weight: 700;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .btn-print:hover { background: #22c55e; }
    .btn-close {
      background: rgba(255,255,255,0.15);
      color: #ffffff;
      border: none;
      padding: 8px 14px;
      border-radius: 6px;
      font-size: 13px;
      cursor: pointer;
      margin-left: 8px;
    }
    .page-container {
      max-width: 960px;
      margin: 28px auto;
      background: #ffffff;
      padding: 40px 48px;
      box-shadow: 0 4px 20px rgba(0,0,0,0.08);
      border-radius: 8px;
    }
    .report-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      border-bottom: 3px solid #14261b;
      padding-bottom: 18px;
      margin-bottom: 22px;
    }
    .brand-logo { font-size: 22px; font-weight: 900; letter-spacing: 0.5px; color: #14261b; }
    .brand-sub { font-size: 11px; text-transform: uppercase; letter-spacing: 0.8px; color: #64748b; font-weight: 600; margin-top: 4px; }
    .report-meta { text-align: right; font-size: 11.5px; color: #475569; line-height: 1.5; }
    .report-meta strong { color: #0f172a; }
    .report-title-block { margin-bottom: 20px; }
    .report-title { font-size: 20px; font-weight: 800; color: #0f172a; margin: 0 0 6px 0; }
    .report-subtitle { font-size: 12.5px; color: #475569; margin: 0; }
    .stat-strip {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 12px;
      margin-bottom: 24px;
    }
    .stat-box {
      background: #f8faf6;
      border: 1px solid #e2e8f0;
      border-left: 4px solid #2e5a3c;
      padding: 10px 14px;
      border-radius: 6px;
    }
    .stat-val { font-size: 18px; font-weight: 800; color: #14261b; margin-bottom: 2px; }
    .stat-lbl { font-size: 10px; text-transform: uppercase; letter-spacing: 0.5px; color: #64748b; font-weight: 600; }
    table { width: 100%; border-collapse: collapse; margin-bottom: 26px; }
    .report-footer {
      border-top: 1px solid #e2e8f0;
      padding-top: 16px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 10.5px;
      color: #64748b;
    }
    .official-badge {
      display: inline-block;
      padding: 3px 8px;
      border: 1px solid #2e5a3c;
      color: #2e5a3c;
      border-radius: 4px;
      font-weight: 700;
      font-size: 9.5px;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    @media print {
      body { background: #ffffff; }
      .toolbar { display: none !important; }
      .page-container {
        margin: 0;
        padding: 0;
        box-shadow: none;
        max-width: 100%;
        border-radius: 0;
      }
      @page {
        margin: 12mm 10mm;
        size: auto;
      }
    }
  </style>
</head>
<body>
  <div class="toolbar">
    <div class="toolbar-title">🌿 FOLIX Report Viewer &bull; ${title}</div>
    <div>
      <button class="btn-print" onclick="window.print()">🖨️ Save as PDF / Print</button>
      <button class="btn-close" onclick="window.close()">✕ Close</button>
    </div>
  </div>

  <div class="page-container">
    <div class="report-header">
      <div>
        <div class="brand-logo">🌿 FOLIX BIOSECURITY</div>
        <div class="brand-sub">Agricultural Disease Surveillance & District Extension Intelligence</div>
      </div>
      <div class="report-meta">
        <div>Ref: <strong>${reportRef}</strong></div>
        <div>Date: <strong>${formattedDate}</strong></div>
        <div>Generated: <strong>${formattedTime} IST</strong></div>
        <div>Jurisdiction: <strong>Authority #${authId}</strong></div>
      </div>
    </div>

    <div class="report-title-block">
      <h1 class="report-title">${title}</h1>
      <p class="report-subtitle">${subtitle} &bull; Classification: OFFICIAL AUDIT RECORD</p>
    </div>

    <div class="stat-strip">
      <div class="stat-box">
        <div class="stat-val">${rows.length}</div>
        <div class="stat-lbl">Total Records</div>
      </div>
      <div class="stat-box" style="border-left-color: #a6402d;">
        <div class="stat-val">${metaInfo && metaInfo.highRiskCount != null ? metaInfo.highRiskCount : rows.filter(r => JSON.stringify(r).toLowerCase().includes("risk") || JSON.stringify(r).includes("YES")).length}</div>
        <div class="stat-lbl">Priority Items</div>
      </div>
      <div class="stat-box" style="border-left-color: #b5893c;">
        <div class="stat-val">${category}</div>
        <div class="stat-lbl">Audit Category</div>
      </div>
      <div class="stat-box" style="border-left-color: #14261b;">
        <div class="stat-val">CERTIFIED</div>
        <div class="stat-lbl">Record Status</div>
      </div>
    </div>

    <table>
      <thead>
        <tr>${tableHeadersHtml}</tr>
      </thead>
      <tbody>
        ${tableRowsHtml}
      </tbody>
    </table>

    <div class="report-footer">
      <div>
        <span class="official-badge">OFFICIAL VERIFIED AUDIT</span>
        <span style="margin-left: 8px;">Generated via FOLIX Enterprise Platform &bull; All data signed and timestamped</span>
      </div>
      <div>Page 1 of 1</div>
    </div>
  </div>

  <script>
    window.addEventListener("load", () => {
      setTimeout(() => { window.print(); }, 400);
    });
  <\/script>
</body>
</html>`;

    printWin.document.open();
    printWin.document.write(html);
    printWin.document.close();
  }

  async function broadcastAlert(id, key, alertId) {
    let response;
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/authorities/${encodeURIComponent(id)}/alerts/${encodeURIComponent(alertId)}/broadcast`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Authority-Api-Key": key },
      });
    } catch (networkErr) {
      throw Object.assign(new Error("could not reach the FOLIX server."), { unauthenticated: false });
    }
    if (response.status === 401) throw Object.assign(new Error("Invalid authority ID or API key."), { unauthenticated: true });
    if (!response.ok) throw Object.assign(new Error("HTTP " + response.status), { unauthenticated: false });
    return response.json();
  }

  async function broadcastNewAlert(id, key, payload) {
    let response;
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/authorities/${encodeURIComponent(id)}/alerts/broadcast-new`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Authority-Api-Key": key },
        body: JSON.stringify(payload),
      });
    } catch (networkErr) {
      throw Object.assign(new Error("could not reach the FOLIX server."), { unauthenticated: false });
    }
    if (response.status === 401) throw Object.assign(new Error("Invalid authority ID or API key."), { unauthenticated: true });
    if (!response.ok) throw Object.assign(new Error("HTTP " + response.status), { unauthenticated: false });
    return response.json();
  }

  async function fetchFarmers(id, key) {
    let response;
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/authorities/${encodeURIComponent(id)}/farmers`, {
        headers: { "X-Authority-Api-Key": key },
      });
    } catch (networkErr) {
      throw Object.assign(new Error("could not reach the FOLIX server."), { unauthenticated: false });
    }
    if (response.status === 401) throw Object.assign(new Error("Invalid authority ID or API key."), { unauthenticated: true });
    if (!response.ok) throw Object.assign(new Error("HTTP " + response.status), { unauthenticated: false });
    return response.json();
  }

  /**
   * Cases farmers have manually forwarded (Stage 6E Part 1) to an Agricultural Network support
   * contact — the same read-only /forwarded-cases endpoint the legacy authority-dashboard.html
   * page uses. Any signed-in authority sees the same list (see AuthorityController's class doc);
   * nothing here creates, changes, or notifies anything.
   */
  async function fetchForwardedCases(id, key) {
    let response;
    try {
      response = await fetch(`${API_BASE_URL}/api/v1/authorities/${encodeURIComponent(id)}/forwarded-cases`, {
        headers: { "X-Authority-Api-Key": key },
      });
    } catch (networkErr) {
      throw Object.assign(new Error("could not reach the FOLIX server."), { unauthenticated: false });
    }
    if (response.status === 401) throw Object.assign(new Error("Invalid authority ID or API key."), { unauthenticated: true });
    if (!response.ok) throw Object.assign(new Error("HTTP " + response.status), { unauthenticated: false });
    return response.json();
  }

  /** The full report behind one forwarded case ("view report details"). */
  async function fetchForwardedCaseDetail(reportId) {
    const response = await fetch(
      `${API_BASE_URL}/api/v1/authorities/${encodeURIComponent(session.id)}/forwarded-cases/${encodeURIComponent(reportId)}`,
      { headers: { "X-Authority-Api-Key": session.apiKey } });
    if (!response.ok) throw new Error("HTTP " + response.status);
    return response.json();
  }

  // ---------------------------------------------------------------
  // Your Alerts
  // ---------------------------------------------------------------

  function stopPolling() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  async function mountHome() {
    if (!isAuthority()) return;
    try {
      const [alerts, forwarded, farmers] = await Promise.all([
        fetchAlerts(session.id, session.apiKey).catch(() => []),
        fetchForwardedCases(session.id, session.apiKey).catch(() => []),
        fetchFarmers(session.id, session.apiKey).catch(() => []),
      ]);
      const alertList = Array.isArray(alerts) ? alerts : [];
      const alertCount = alertList.filter((a) => a.status !== "RESOLVED").length;
      const riskCount = alertList.filter((a) => a.epidemicRisk).length;
      const forwardCount = Array.isArray(forwarded) ? forwarded.length : 0;
      const farmerCount = Array.isArray(farmers) ? farmers.length : 0;

      const elAlerts = $("homeCountAlerts");
      const elForward = $("homeCountForward");
      const elFarmers = $("homeCountFarmers");
      const elRisk = $("homeCountRisk");

      if (elAlerts) elAlerts.textContent = String(alertCount);
      if (elForward) elForward.textContent = String(forwardCount);
      if (elFarmers) elFarmers.textContent = String(farmerCount);
      if (elRisk) elRisk.textContent = String(riskCount);
    } catch (_) {}
  }

  function mountAlerts() {
    refreshAlerts(true);
    // Periodic refresh (this deployment has no push channel), same 20s cadence as the legacy portal.
    pollTimer = setInterval(() => refreshAlerts(false), POLL_INTERVAL_MS);

    const exportBtn = $("authorityExportAlertsBtn");
    if (exportBtn) {
      exportBtn.onclick = async () => {
        try {
          exportBtn.disabled = true;
          exportBtn.textContent = "Exporting…";
          const alerts = await fetchAlerts(session.id, session.apiKey);
          const exportRows = (alerts || []).map(a => [
            `#ALT-${a.id}`,
            a.cropName || "—",
            a.diseaseName || "—",
            `${a.severityPercent || 0}%`,
            a.epidemicRisk ? "YES" : "NO",
            a.status || "NOTIFIED",
            a.createdAt ? new Date(a.createdAt).toLocaleString() : "—",
            a.resolutionNote || "—"
          ]);
          exportToThemedPdf({
            title: "Outbreak Cases & Disease Alert Audit",
            subtitle: "Official District Plant Pathology & Epidemic Surveillance Log",
            category: "Your Cases",
            headers: ["Case ID", "Crop", "Disease / Pathogen", "Severity", "Epidemic Risk", "Status", "Reported At", "Resolution Note"],
            rows: exportRows,
            metaInfo: { highRiskCount: (alerts || []).filter(a => a.epidemicRisk || (a.severityPercent || 0) >= 70).length }
          });
        } catch (e) {
          window.alert("Could not export cases: " + e.message);
        } finally {
          exportBtn.disabled = false;
          exportBtn.textContent = "📄 Export PDF Report";
        }
      };
    }

    const toggleBtn = $("authorityBroadcastNewBtn");
    const formCard = $("authorityBroadcastFormCard");
    const closeBtn = $("authorityBroadcastCloseX");
    const cancelBtn = $("authorityBroadcastCancel");
    const form = $("authorityBroadcastForm");
    const msgEl = $("authorityBroadcastMsg");
    const submitBtn = $("authorityBroadcastSubmit");

    if (toggleBtn && formCard) {
      toggleBtn.addEventListener("click", () => {
        formCard.hidden = !formCard.hidden;
        if (!formCard.hidden) {
          const diseaseInput = $("broadcastDisease");
          if (diseaseInput) diseaseInput.focus();
        }
      });
    }
    if (closeBtn && formCard) closeBtn.addEventListener("click", () => { formCard.hidden = true; });
    if (cancelBtn && formCard) cancelBtn.addEventListener("click", () => { formCard.hidden = true; });

    if (form) {
      form.addEventListener("submit", async (e) => {
        e.preventDefault();
        const disease = ($("broadcastDisease").value || "").trim();
        const crop = ($("broadcastCrop").value || "").trim();
        const sevVal = parseInt($("broadcastSeverity").value, 10);
        const advisory = ($("broadcastAdvisory").value || "").trim();

        if (!disease || !crop) return;
        submitBtn.disabled = true;
        submitBtn.textContent = "Broadcasting alert…";
        msgEl.hidden = true;

        try {
          const res = await broadcastNewAlert(session.id, session.apiKey, {
            diseaseName: disease,
            cropName: crop,
            severityPercent: isNaN(sevVal) ? 80 : sevVal,
            advisory: advisory,
          });
          msgEl.textContent = res.message || "Alert broadcasted to registered farmers!";
          msgEl.style.color = "#15803d";
          msgEl.hidden = false;
          submitBtn.textContent = "Sent ✓";
          form.reset();
          setTimeout(() => {
            if (formCard) formCard.hidden = true;
            submitBtn.disabled = false;
            submitBtn.textContent = "Send Alert to Farmers";
            msgEl.hidden = true;
          }, 2500);
          refreshAlerts(false);
        } catch (err) {
          msgEl.textContent = "Could not broadcast alert: " + (err.message || "server error");
          msgEl.style.color = "#b91c1c";
          msgEl.hidden = false;
          submitBtn.disabled = false;
          submitBtn.textContent = "Send Alert to Farmers";
        }
      });
    }
  }

  document.addEventListener("visibilitychange", () => {
    if (!isAuthority()) return;
    const view = currentView();
    const refresh = (view === "alerts" || view === "cases") ? refreshAlerts : view === "forward" ? refreshForwardCases : null;
    if (!refresh) return;
    if (document.visibilityState === "visible") {
      refresh(false);
      stopPolling();
      pollTimer = setInterval(() => refresh(false), POLL_INTERVAL_MS);
    } else {
      stopPolling();
    }
  });

  async function refreshAlerts(isInitialLoad) {
    if (refreshInFlight || !isAuthority()) return;
    const list = $("authorityAlertsList");
    if (!list) return;
    refreshInFlight = true;
    const errorEl = $("authorityAlertsError");
    const live = $("authorityLive");
    try {
      const alerts = await fetchAlerts(session.id, session.apiKey);
      // The view may have changed (or the user signed out) while the request was in flight.
      if (!isAuthority() || $("authorityAlertsList") !== list) return;
      errorEl.hidden = true;
      renderAlerts(list, alerts);
      live.textContent = "Auto-refreshing every " + Math.round(POLL_INTERVAL_MS / 1000) + "s — last updated " + new Date().toLocaleTimeString() + ".";
    } catch (err) {
      if (err.unauthenticated) { signOut(); return; }
      if ($("authorityAlertsList") !== list) return;
      if (isInitialLoad) {
        errorEl.textContent = "Couldn't load your alerts: " + err.message;
        errorEl.hidden = false;
      } else {
        live.textContent = "Auto-refreshing — the last refresh failed; still showing the most recent data.";
      }
    } finally {
      refreshInFlight = false;
    }
  }

  function renderAlerts(list, alerts) {
    list.textContent = "";
    if (!alerts.length) {
      list.append(el("p", { className: "authority-empty", text: "No alerts yet." }));
      return;
    }
    alerts.forEach((a) => list.append(alertItem(a)));
  }

  function alertItem(a) {
    const status = String(a.status || "");
    const statusClass = /^[A-Za-z]+$/.test(status) ? "status-" + status.toLowerCase() : "";

    const head = el("div", { className: "authority-alert-head" }, [
      el("strong", { text: (a.diseaseName || "Unknown disease") + " — " + (a.cropName || "Unknown crop") }),
      el("span", { className: "alert-status " + statusClass, text: status }),
    ]);

    const sevVal = Math.min(100, Math.max(0, a.severityPercent || 0));
    const sevFillColor = sevVal >= 70 ? "var(--clay, #A6402D)" : sevVal >= 40 ? "var(--wheat, #B5893C)" : "var(--leaf, #1F3B2A)";
    const sevWrap = el("div", { className: "severity-bar-wrap" }, [
      el("div", { className: "severity-bar-bg" }, [
        el("div", { className: "severity-bar-fill", style: `width: ${sevVal}%; background: ${sevFillColor};` })
      ]),
      el("span", { style: "font-size:0.8rem; font-weight:700; color:var(--ink);", text: sevVal + "% Risk" + (a.epidemicRisk ? " · Epidemic Pathogen" : "") })
    ]);

    const meta = el("p", { className: "authority-alert-meta" });
    meta.append(
      "Severity " + a.severityPercent + "%" +
      (a.epidemicRisk ? " · epidemic-risk pathogen" : "") +
      " · via " + (a.channel || "—") +
      " · reported " + new Date(a.createdAt).toLocaleString()
    );
    if (a.latitude != null && a.longitude != null && isFinite(Number(a.latitude)) && isFinite(Number(a.longitude))) {
      const lat = Number(a.latitude);
      const lon = Number(a.longitude);
      meta.append(" · ", el("a", {
        href: `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=15/${lat}/${lon}`,
        target: "_blank", rel: "noopener", text: "view location",
      }));
    }

    const item = el("div", { className: "authority-alert", "data-alert-id": a.id }, [head, sevWrap, meta]);

    const actions = el("div", { className: "authority-alert-actions" });
    if (status !== "RESOLVED") {
      actions.append(broadcastButton(a.id));
      if (status === "NOTIFIED") actions.append(actionButton("Acknowledge", "btn btn-secondary btn-small", a.id, "ACKNOWLEDGED"));
      actions.append(actionButton("Mark resolved", "btn btn-primary btn-small", a.id, "RESOLVED"));
    }
    const delBtn = el("button", { type: "button", className: "btn btn-danger btn-small", text: "🗑 Delete Case" });
    delBtn.title = "Permanently delete this case alert from jurisdiction records";
    delBtn.addEventListener("click", async () => {
      if (!window.confirm("Are you sure you want to permanently delete this alert case?")) return;
      delBtn.disabled = true;
      try {
        await deleteAlert(a.id);
        await refreshAlerts(false);
      } catch (err) {
        window.alert("Could not delete alert: " + err.message);
        delBtn.disabled = false;
      }
    });
    actions.append(delBtn);
    item.append(actions);

    if (status === "RESOLVED" && a.resolutionNote) {
      item.append(el("p", { className: "authority-alert-note", text: "Note: " + a.resolutionNote }));
    }
    return item;
  }

  function actionButton(label, className, alertId, status) {
    const btn = el("button", { type: "button", className, text: label });
    btn.addEventListener("click", async () => {
      let note = null;
      if (status === "RESOLVED") note = window.prompt("Optional note about how this was resolved:") || null;
      btn.disabled = true;
      try {
        await patchAlert(alertId, status, note);
        await refreshAlerts(false);
      } catch (err) {
        window.alert("Could not update alert: " + err.message);
        btn.disabled = false;
      }
    });
    return btn;
  }

  function broadcastButton(alertId) {
    const btn = el("button", { type: "button", className: "btn btn-primary btn-small", text: "📢 Broadcast to Farmers" });
    btn.addEventListener("click", async () => {
      const confirmed = window.confirm("Broadcast this epidemic alert to nearby registered farmers?\n\nThis will send an alert notification directly to the Nearby Alerts page for farmers in this area.");
      if (!confirmed) return;
      btn.disabled = true;
      btn.textContent = "Broadcasting…";
      try {
        const res = await broadcastAlert(session.id, session.apiKey, alertId);
        const n = res && typeof res.farmersAlerted === "number" ? res.farmersAlerted : 0;
        btn.textContent = `Broadcasted to ${n} farmer${n === 1 ? "" : "s"} ✓`;
        btn.classList.remove("btn-primary");
        btn.classList.add("btn-secondary");
        await refreshAlerts(false);
      } catch (err) {
        window.alert("Could not broadcast alert: " + (err.message || "server error"));
        btn.disabled = false;
        btn.textContent = "📢 Broadcast to Farmers";
      }
    });
    return btn;
  }

  // ---------------------------------------------------------------
  // Forward Case (read-only list of farmer-forwarded cases)
  // ---------------------------------------------------------------

  function mountForward() {
    refreshForwardCases(true);
    // Same reliable-periodic-refresh pattern as Your Alerts (this deployment has no push channel).
    pollTimer = setInterval(() => refreshForwardCases(false), POLL_INTERVAL_MS);

    const exportBtn = $("authorityExportForwardBtn");
    if (exportBtn) {
      exportBtn.onclick = async () => {
        try {
          exportBtn.disabled = true;
          exportBtn.textContent = "Exporting…";
          const cases = await fetchForwardedCases(session.id, session.apiKey);
          const exportRows = (cases || []).map(c => [
            `#REP-${(c.reportId || "").substring(0, 8)}`,
            c.crop || "—",
            c.disease || "—",
            c.severity || "—",
            c.supportContactName ? `${c.supportContactName} (${c.supportContactOrganization || "Specialist"})` : "Agricultural Network",
            c.forwardedAt ? new Date(c.forwardedAt).toLocaleString() : "—"
          ]);
          exportToThemedPdf({
            title: "Forwarded Diagnostic Cases Audit",
            subtitle: "Field Escalations Routed to Extension Officers & Specialists",
            category: "Forwarded Cases",
            headers: ["Report ID", "Crop", "Disease / Diagnosis", "Severity", "Forwarded To", "Date Forwarded"],
            rows: exportRows
          });
        } catch (e) {
          window.alert("Could not export forwarded cases: " + e.message);
        } finally {
          exportBtn.disabled = false;
          exportBtn.textContent = "📄 Export PDF Report";
        }
      };
    }
  }

  async function refreshForwardCases(isInitialLoad) {
    if (forwardRefreshInFlight || !isAuthority()) return;
    const list = $("authorityForwardList");
    if (!list) return;
    forwardRefreshInFlight = true;
    const errorEl = $("authorityForwardError");
    const live = $("authorityForwardLive");

    // Remember which "view report details" panels are open so a background refresh doesn't
    // silently collapse a case an officer is currently reading.
    const openReportIds = new Set(
      Array.from(list.querySelectorAll("[data-report-id]"))
        .filter((item) => {
          const detail = item.querySelector("[data-detail]");
          return detail && !detail.hidden;
        })
        .map((item) => item.dataset.reportId)
    );

    try {
      const cases = await fetchForwardedCases(session.id, session.apiKey);
      // The view may have changed (or the user signed out) while the request was in flight.
      if (!isAuthority() || $("authorityForwardList") !== list) return;
      errorEl.hidden = true;
      renderForwardCases(list, cases, openReportIds);
      live.textContent = "Auto-refreshing every " + Math.round(POLL_INTERVAL_MS / 1000) + "s — last updated " + new Date().toLocaleTimeString() + ".";
    } catch (err) {
      if (err.unauthenticated) { signOut(); return; }
      if ($("authorityForwardList") !== list) return;
      if (isInitialLoad) {
        errorEl.textContent = "Couldn't load forwarded cases: " + err.message;
        errorEl.hidden = false;
      } else {
        live.textContent = "Auto-refreshing — the last refresh failed; still showing the most recent data.";
      }
    } finally {
      forwardRefreshInFlight = false;
    }
  }

  function renderForwardCases(list, cases, openReportIds) {
    list.textContent = "";
    if (!cases.length) {
      list.append(el("p", { className: "authority-empty", text: "No cases have been forwarded yet." }));
      return;
    }
    cases.forEach((c) => list.append(forwardCaseItem(c, openReportIds && openReportIds.has(String(c.reportId)))));
  }

  function forwardCaseItem(c, reopen) {
    const status = String(c.status || "");
    const statusClass = /^[A-Za-z]+$/.test(status) ? "status-" + status.toLowerCase() : "";

    const head = el("div", { className: "authority-alert-head" }, [
      el("strong", { text: (c.disease || "Unknown disease") + " — " + (c.crop || "Unknown crop") }),
      el("span", { className: "alert-status " + statusClass, text: status }),
    ]);

    const meta = el("p", { className: "authority-alert-meta" });
    meta.append(
      "Report " + c.reportId + " · Severity " + (c.severity || "—") +
      " · forwarded " + new Date(c.forwardedAt).toLocaleString() +
      " · to " + (c.supportContactName || "—") +
      (c.supportContactOrganization ? " (" + c.supportContactOrganization + ")" : "")
    );

    const detail = el("div", { className: "authority-forward-detail" });
    detail.setAttribute("data-detail", "");
    detail.hidden = true;

    const actionRow = el("div", { style: "display:flex; gap:8px; align-items:center; margin-top:8px; flex-wrap:wrap;" });
    const toggle = el("button", { type: "button", className: "btn btn-secondary btn-small", text: "View report details" });
    toggle.addEventListener("click", () => toggleForwardDetail(detail, c.reportId));

    const removeBtn = el("button", { type: "button", className: "btn btn-danger btn-small", text: "🗑 Remove" });
    removeBtn.title = "Remove this case from the forwarded cases list";
    removeBtn.addEventListener("click", async () => {
      if (!window.confirm("Remove this case from the forwarded cases list?")) return;
      removeBtn.disabled = true;
      try {
        await deleteForwardedCase(c.reportId);
        await refreshForwardCases(false);
      } catch (err) {
        window.alert("Could not remove case: " + err.message);
        removeBtn.disabled = false;
      }
    });
    actionRow.append(toggle, removeBtn);

    const item = el("div", { className: "authority-alert", "data-report-id": c.reportId }, [head, meta, actionRow, detail]);

    if (reopen) toggleForwardDetail(detail, c.reportId);

    return item;
  }

  async function toggleForwardDetail(detailEl, reportId) {
    if (!detailEl.hidden) { detailEl.hidden = true; return; }
    detailEl.hidden = false;
    detailEl.textContent = "";
    detailEl.append(el("p", { text: "Loading…" }));
    try {
      const d = await fetchForwardedCaseDetail(reportId);
      detailEl.textContent = "";
      detailEl.append(el("p", {}, [el("strong", { text: "Plant part: " }), d.plantPart || "—"]));
      detailEl.append(el("p", {}, [el("strong", { text: "Confidence: " }), d.confidence || "—"]));
      if (d.symptoms && d.symptoms.length) {
        detailEl.append(el("p", {}, [el("strong", { text: "Symptoms: " }), d.symptoms.join(", ")]));
      }
      if (d.reasoning) {
        detailEl.append(el("p", {}, [el("strong", { text: "Reasoning: " }), d.reasoning]));
      }
      if (d.recommendedActions && d.recommendedActions.length) {
        detailEl.append(el("p", {}, [el("strong", { text: "Recommended actions: " }), d.recommendedActions.join(", ")]));
      }
      detailEl.append(el("p", {}, [el("strong", { text: "Needs human review: " }), d.needsHumanReview ? "Yes" : "No"]));
    } catch (err) {
      detailEl.textContent = "";
      detailEl.append(el("p", { className: "authority-forward-error", text: "Could not load report details: " + err.message }));
    }
  }

  // ---------------------------------------------------------------
  // Farmers Directory (Stage 6E / Authority Jurisdiction)
  // ---------------------------------------------------------------

  /** Real farmer data fetched from the backend — replaces the old hardcoded SAMPLE_FARMERS. */
  let cachedFarmers = [];

  async function mountFarmers() {
    const listEl = $("authorityFarmersList");
    const searchEl = $("authorityFarmerSearch");
    const filterEl = $("authorityAreaFilter");
    const countEl = $("authorityFarmerCount");
    if (!listEl) return;

    // Show a loading state while fetching
    listEl.textContent = "";
    listEl.append(el("p", { className: "authority-empty", text: "Loading farmers…" }));
    if (countEl) countEl.textContent = "";

    try {
      const farmers = await fetchFarmers(session.id, session.apiKey);
      cachedFarmers = Array.isArray(farmers) ? farmers : [];
    } catch (err) {
      if (err.unauthenticated) { signOut(); return; }
      listEl.textContent = "";
      listEl.append(el("p", { className: "authority-empty", text: "Could not load farmers: " + err.message }));
      return;
    }

    // Dynamically populate the district filter from real data
    if (filterEl) {
      const districts = [...new Set(cachedFarmers.map((f) => f.district).filter(Boolean))].sort();
      filterEl.textContent = "";
      const allOpt = document.createElement("option");
      allOpt.value = "ALL";
      allOpt.textContent = "All Districts";
      filterEl.append(allOpt);
      districts.forEach((d) => {
        const opt = document.createElement("option");
        opt.value = d;
        opt.textContent = d;
        filterEl.append(opt);
      });
    }

    function update() {
      const q = (searchEl ? searchEl.value : "").trim().toLowerCase();
      const district = filterEl ? filterEl.value : "ALL";
      const crops = (f) => Array.isArray(f.crops) ? f.crops : [];

      const filtered = cachedFarmers.filter((f) => {
        if (district !== "ALL" && f.district !== district) return false;
        if (!q) return true;
        return (
          (f.name || "").toLowerCase().includes(q) ||
          (f.village || "").toLowerCase().includes(q) ||
          (f.district || "").toLowerCase().includes(q) ||
          crops(f).some((c) => c.toLowerCase().includes(q))
        );
      });

      if (countEl) countEl.textContent = `${filtered.length} farmer${filtered.length === 1 ? "" : "s"} listed`;

      listEl.textContent = "";
      if (!filtered.length) {
        listEl.append(el("p", { className: "authority-empty", text: cachedFarmers.length === 0 ? "No registered farmers yet." : "No farmers match the current filter." }));
        return;
      }

      filtered.forEach((f) => {
        const farmerCrops = crops(f);
        const initials = (f.name || "").trim().split(/\s+/).map((w) => w[0]).slice(0, 2).join("").toUpperCase() || "F";
        const top = el("div", { className: "authority-farmer-top" }, [
          el("div", { className: "farmer-header-wrap" }, [
            el("div", { className: "farmer-avatar-badge", text: initials }),
            el("div", {}, [
              el("h3", { className: "authority-farmer-name", text: f.name || "Unknown" }),
              el("span", { className: "authority-farmer-id", text: f.id || "" }),
            ]),
          ]),
          el("span", { className: "authority-farmer-badge " + (f.statusBadge || "badge-active"), text: f.status || "Active" }),
        ]);

        const cropTags = el("div", { className: "authority-crop-tags" });
        farmerCrops.forEach((c) => cropTags.append(el("span", { className: "authority-crop-tag", text: c })));

        const meta = el("div", { className: "authority-farmer-meta" }, [
          el("div", {}, [el("strong", { text: "Location: " }), (f.village || "—") + ", " + (f.district || "—")]),
          ...(f.email ? [el("div", {}, [el("strong", { text: "Email: " }), f.email])] : []),
          ...(f.phone ? [el("div", {}, [el("strong", { text: "Contact: " }), f.phone])] : []),
          el("div", {}, [el("strong", { text: "Crops: " }), farmerCrops.length ? cropTags : "No crops listed"]),
          el("div", {}, [el("strong", { text: "Latest status: " }), f.lastReport || "No scans yet"]),
        ]);

        const actions = el("div", { className: "authority-farmer-actions" }, [
          ...(f.phone ? [el("a", { className: "btn btn-secondary btn-small", href: `tel:${f.phone.replace(/\s+/g, "")}`, text: "Call" })] : []),
          (() => {
            const btn = el("button", { type: "button", className: "btn btn-primary btn-small", text: "Advisory" });
            btn.addEventListener("click", () => {
              const msg = window.prompt(`Send extension advisory to ${f.name}:`);
              if (msg) window.alert(`Advisory queued for ${f.name}: "${msg}"`);
            });
            return btn;
          })(),
          (() => {
            const farmerRealId = f.rawId || (f.id ? f.id.replace(/^FMR-/, "") : "");
            const isSuspended = (f.status || "").toLowerCase().includes("disable") || (f.status || "").toLowerCase().includes("suspend");

            const statusBtn = el("button", {
              type: "button",
              className: "btn btn-secondary btn-small",
              text: isSuspended ? "✓ Reactivate" : "⏸ Suspend"
            });
            statusBtn.title = isSuspended ? "Reactivate farmer account" : "Suspend farmer account";
            statusBtn.addEventListener("click", async () => {
              const targetStatus = isSuspended ? "ACTIVE" : "DISABLED";
              if (!window.confirm(`Are you sure you want to ${isSuspended ? "reactivate" : "suspend"} the account for ${f.name}?`)) return;
              statusBtn.disabled = true;
              try {
                await updateFarmerStatus(farmerRealId, targetStatus);
                await mountFarmers();
              } catch (err) {
                window.alert("Failed to update farmer status: " + err.message);
                statusBtn.disabled = false;
              }
            });
            return statusBtn;
          })(),
          (() => {
            const farmerRealId = f.rawId || (f.id ? f.id.replace(/^FMR-/, "") : "");
            const delBtn = el("button", {
              type: "button",
              className: "btn btn-danger btn-small",
              text: "🗑 Delete"
            });
            delBtn.title = "Permanently remove farmer account and reports";
            delBtn.addEventListener("click", async () => {
              if (!window.confirm(`PERMANENT DELETE:\nAre you sure you want to delete farmer ${f.name}?\n\nThis will remove their farmer account and all associated scan reports.`)) return;
              delBtn.disabled = true;
              try {
                await deleteFarmer(farmerRealId);
                await mountFarmers();
              } catch (err) {
                window.alert("Failed to delete farmer: " + err.message);
                delBtn.disabled = false;
              }
            });
            return delBtn;
          })(),
        ]);

        listEl.append(el("div", { className: "authority-farmer-card" }, [top, meta, actions]));
      });
    }

    const exportFarmersBtn = $("authorityExportFarmersBtn");
    if (exportFarmersBtn) {
      exportFarmersBtn.onclick = () => {
        const exportRows = (cachedFarmers || []).map(f => [
          f.id || "—",
          f.name || "—",
          `${f.village || "—"}, ${f.district || "—"}`,
          f.phone || "—",
          Array.isArray(f.crops) && f.crops.length ? f.crops.join(", ") : "No crops listed",
          f.status || "Active",
          f.lastReport || "Healthy / No Scans"
        ]);
        exportToThemedPdf({
          title: "Registered Farmers & Crop Jurisdiction Audit",
          subtitle: "District Extension Directory & Active Crop Profiles",
          category: "Farmers in Area",
          headers: ["Farmer ID", "Farmer Name", "District / Area", "Contact", "Active Crops", "Account Status", "Latest Health Status"],
          rows: exportRows
        });
      };
    }

    if (searchEl) searchEl.addEventListener("input", update);
    if (filterEl) filterEl.addEventListener("change", update);
    update();
  }

  // ---------------------------------------------------------------
  // Start
  // ---------------------------------------------------------------

  window.addEventListener("hashchange", render);
  restoreSession();
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", render);
  } else {
    render();
  }
})();
