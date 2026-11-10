"use strict";

/**
 * Page logic for agricultural-network.html (Stage 6D). Talks to
 * /api/farmer/agri-network/{profile,directory}, mirroring the fetch/error-envelope pattern used by
 * auth.js for /api/farmer/profile — but the Agricultural Network endpoints live outside FolixAuth's
 * scope, so this file makes its own calls rather than modifying auth.js. Session/login state is still
 * read from `window.FolixAuth` (the shared auth widget already loaded via auth.js/nav.js), so log
 * in/out here stays in sync with the rest of the site.
 *
 * Everything user-controlled is written with textContent — never innerHTML.
 */
(function () {
  const API_BASE_URL = window.FolixAuth ? window.FolixAuth.API_BASE_URL : (window.CROPGUARD_API_BASE_URL || "http://localhost:8080");
  const AGRI_URL = API_BASE_URL + "/api/farmer/agri-network";

  const CATEGORY_LABELS = {
    AGRICULTURAL_OFFICER: "Agricultural Officer",
    KRISHI_BHAVAN: "Krishi Bhavan",
    EXTENSION_SUPPORT: "Extension Support",
    CROP_DISEASE_ASSISTANCE: "Crop Disease Assistance",
    GENERAL_SUPPORT: "General Support",
  };
  const CATEGORY_ORDER = ["AGRICULTURAL_OFFICER", "KRISHI_BHAVAN", "EXTENSION_SUPPORT", "CROP_DISEASE_ASSISTANCE", "GENERAL_SUPPORT"];

  class AgriNetworkError extends Error {
    constructor(message, { status = 0, code = "", fields = {} } = {}) {
      super(message);
      this.name = "AgriNetworkError";
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
      throw new AgriNetworkError("Could not reach the FOLIX server. Check your connection and try again.", { status: 0, code: "NETWORK" });
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
      throw new AgriNetworkError(message, { status: response.status, code: err && typeof err.code === "string" ? err.code : "", fields });
    }
    return payload;
  }

  function getAgriProfile() { return call("GET", AGRI_URL + "/profile"); }
  function saveAgriProfile(body) { return call("PUT", AGRI_URL + "/profile", body); }
  function getDirectory() { return call("GET", AGRI_URL + "/directory"); }

  // ---------------------------------------------------------------
  // DOM helpers
  // ---------------------------------------------------------------

  const $ = (id) => document.getElementById(id);

  function formatDate(iso) {
    if (!iso) return "—";
    const d = new Date(iso);
    return isNaN(d.getTime()) ? "—" : d.toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" });
  }

  function categoryLabel(value) {
    return CATEGORY_LABELS[value] || null;
  }

  // ---------------------------------------------------------------
  // Profile section
  // ---------------------------------------------------------------

  function initProfileSection() {
    const panels = ["profileLoading", "profileAuthRequired", "profileErrorState", "profileEmptyState", "profileViewState", "apEditForm"];
    const showPanel = (id) => panels.forEach((p) => { $(p).hidden = p !== id; });

    let current = null; // last-loaded FolixAgriProfileResponse

    function renderView(p) {
      $("apVillage").textContent = p.villageOrArea || "—";
      $("apCrops").textContent = p.crops || "—";
      $("apCategory").textContent = categoryLabel(p.preferredCategory) || "—";
      $("apUpdated").textContent = p.updatedAt ? "Last updated " + formatDate(p.updatedAt) : "";
      showPanel("profileViewState");
    }

    function openEdit(p) {
      $("apFieldVillage").value = (p && p.villageOrArea) || "";
      $("apFieldCrops").value = (p && p.crops) || "";
      $("apFieldCategory").value = (p && p.preferredCategory) || "";
      $("apEditError").hidden = true;
      $("apEditError").textContent = "";
      showPanel("apEditForm");
    }

    async function load() {
      showPanel("profileLoading");
      try {
        const p = await getAgriProfile();
        current = p;
        if (p.hasProfile) renderView(p);
        else showPanel("profileEmptyState");
      } catch (err) {
        if (err.isUnauthenticated) {
          showPanel("profileAuthRequired");
        } else {
          $("profileErrorText").textContent = err.message || "Something went wrong while reaching the FOLIX server.";
          showPanel("profileErrorState");
        }
      }
    }

    $("profileRetry").addEventListener("click", load);
    $("apAddBtn").addEventListener("click", () => openEdit(current));
    $("apEditBtn").addEventListener("click", () => openEdit(current));
    $("apCancelBtn").addEventListener("click", () => {
      if (current && current.hasProfile) renderView(current);
      else showPanel("profileEmptyState");
    });

    $("apEditForm").addEventListener("submit", async (e) => {
      e.preventDefault();
      const submit = $("apSaveBtn");
      $("apEditError").hidden = true;
      submit.disabled = true;
      submit.textContent = "Saving…";
      try {
        const body = {
          villageOrArea: $("apFieldVillage").value.trim() || null,
          crops: $("apFieldCrops").value.trim() || null,
          preferredCategory: $("apFieldCategory").value || null,
        };
        const saved = await saveAgriProfile(body);
        current = saved;
        renderView(saved);
      } catch (err) {
        $("apEditError").textContent = err.message || "Couldn't save your profile. Please try again.";
        $("apEditError").hidden = false;
      } finally {
        submit.disabled = false;
        submit.textContent = "Save";
      }
    });

    load();
  }

  // ---------------------------------------------------------------
  // Directory section
  // ---------------------------------------------------------------

  function initDirectorySection() {
    const panels = ["dirLoading", "dirAuthRequired", "dirErrorState", "dirEmptyState", "dirList"];
    const showPanel = (id) => panels.forEach((p) => { $(p).hidden = p !== id; });

    function renderList(contacts) {
      const list = $("dirList");
      list.textContent = "";
      contacts.forEach((c) => {
        const li = document.createElement("li");
        li.className = "directory-item";

        const top = document.createElement("div");
        top.className = "directory-item-top";
        const name = document.createElement("span");
        name.className = "directory-name";
        name.textContent = c.name;
        top.appendChild(name);
        const label = categoryLabel(c.category);
        if (label) {
          const badge = document.createElement("span");
          badge.className = "directory-category";
          badge.textContent = label;
          top.appendChild(badge);
        }
        li.appendChild(top);

        if (c.organization) {
          const org = document.createElement("p");
          org.className = "directory-org";
          org.textContent = c.organization + (c.locality ? " · " + c.locality : "");
          li.appendChild(org);
        } else if (c.locality) {
          const org = document.createElement("p");
          org.className = "directory-org";
          org.textContent = c.locality;
          li.appendChild(org);
        }

        const meta = document.createElement("ul");
        meta.className = "directory-meta";
        if (c.phone) {
          const li2 = document.createElement("li");
          const a = document.createElement("a");
          a.href = "tel:" + c.phone;
          a.textContent = c.phone;
          li2.appendChild(a);
          meta.appendChild(li2);
        }
        if (c.email) {
          const li2 = document.createElement("li");
          const a = document.createElement("a");
          a.href = "mailto:" + c.email;
          a.textContent = c.email;
          li2.appendChild(a);
          meta.appendChild(li2);
        }
        if (c.address) {
          const li2 = document.createElement("li");
          li2.textContent = c.address;
          meta.appendChild(li2);
        }
        if (meta.childNodes.length) li.appendChild(meta);

        list.appendChild(li);
      });
      showPanel("dirList");
    }

    async function load() {
      showPanel("dirLoading");
      try {
        const contacts = await getDirectory();
        if (Array.isArray(contacts) && contacts.length > 0) renderList(contacts);
        else showPanel("dirEmptyState");
      } catch (err) {
        if (err.isUnauthenticated) {
          showPanel("dirAuthRequired");
        } else {
          $("dirErrorText").textContent = err.message || "Something went wrong while reaching the FOLIX server.";
          showPanel("dirErrorState");
        }
      }
    }

    $("dirRetry").addEventListener("click", load);
    load();
  }

  document.addEventListener("DOMContentLoaded", () => {
    if (document.body.dataset.page !== "network") return;
    initProfileSection();
    initDirectorySection();
  });
})();
