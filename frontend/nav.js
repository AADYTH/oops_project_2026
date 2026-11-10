"use strict";

/**
 * Global nav behavior, shared by all six FOLIX pages (index, how-it-works,
 * scanner, dashboard, heatmap, agricultural-network):
 *
 * 1. Mobile nav toggle (hamburger menu). Below the 1180px breakpoint,
 *    styles.css hides the full nav and shows #navToggle instead; this wires
 *    up opening/closing it.
 * 2. The soft green "active page" glow (see .nav-active in styles.css),
 *    driven by <body data-page="...">.
 */
document.addEventListener("DOMContentLoaded", () => {
  setActiveNav();

  const toggle = document.getElementById("navToggle");
  const nav = document.querySelector(".header-nav");
  if (!toggle || !nav) return;

  function setOpen(isOpen) {
    nav.classList.toggle("open", isOpen);
    toggle.setAttribute("aria-expanded", String(isOpen));
    toggle.textContent = isOpen ? "✕" : "☰";
  }

  toggle.addEventListener("click", () => {
    setOpen(!nav.classList.contains("open"));
  });

  // Close the menu after tapping a link, and on outside taps.
  nav.querySelectorAll("a").forEach((link) => {
    link.addEventListener("click", () => setOpen(false));
  });
  document.addEventListener("click", (e) => {
    if (!nav.classList.contains("open")) return;
    if (nav.contains(e.target) || toggle.contains(e.target)) return;
    setOpen(false);
  });
  window.addEventListener("resize", () => {
    if (window.innerWidth >= 1180) setOpen(false);
  });
});

function setActiveNav() {
  // Every FOLIX page is its own file, so the active item is simply the one
  // matching <body data-page="...">: home, how, scanner, dashboard, map, network.
  const page = document.body.dataset.page || "";
  document.querySelectorAll(".header-nav a[data-nav]").forEach((a) => {
    const isActive = a.dataset.nav === page;
    a.classList.toggle("nav-active", isActive);
    if (isActive) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  });
}
