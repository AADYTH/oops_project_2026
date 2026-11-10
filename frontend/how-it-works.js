"use strict";

/**
 * How-it-works page: one job only.
 *
 * Elements marked [data-reveal] (the process line and the uncertainty ring)
 * start in their "not yet drawn" state — see how-it-works.css — and get an
 * `is-in` class the first time they scroll into view, which lets their CSS
 * transitions play once. The rest of the page needs no script.
 *
 * Falls back to the finished state straight away when the visitor prefers
 * reduced motion or the browser has no IntersectionObserver.
 */
document.addEventListener("DOMContentLoaded", () => {
  const targets = document.querySelectorAll("[data-reveal]");
  if (!targets.length) return;

  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduceMotion || !("IntersectionObserver" in window)) {
    targets.forEach((el) => el.classList.add("is-in"));
    return;
  }

  const observer = new IntersectionObserver((entries, obs) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("is-in");
      obs.unobserve(entry.target);
    });
  }, { threshold: 0.3 });

  targets.forEach((el) => observer.observe(el));
});
