"use strict";

/**
 * Hero media (mobile): the same bust-turns-toward-you idea as hero-media.js,
 * but built around a real <video> instead of a scrubbed frame sequence, and
 * driven by touch instead of the cursor.
 *
 * Why a video here instead of hero-media.js's canvas/frame-sequence:
 *  - hero-media.js downloads a 4+ MB frame set built for a 16:9 desktop
 *    crop. This is a lighter (~1.2 MB), pre-cut 9:16 portrait version of the
 *    same footage (assets/hero/hero-mobile.mp4) - a better fit for phones
 *    and mobile data.
 *  - Only one of the two ever runs: this file and hero-media.js each check
 *    the same breakpoint and bail out (for now, listening in case a resize
 *    crosses it later) if it doesn't apply to them.
 *
 * How the touch reactivity matches the desktop (mouse) version:
 *  - Same idea: touch position sets a TARGET (-1 full left .. 0 neutral ..
 *    1 full right), never the footage directly.
 *  - Same spring: the exact critically-damped "SmoothDamp" used by
 *    hero-media.js (same constants: REACH, SMOOTH_TIME, MAX_SPEED,
 *    MAX_REACH_PX) turns that target into a smoothed value every frame, so
 *    a drag settles without ever changing velocity abruptly.
 *  - The smoothed value maps onto the clip's timeline (neutral = the first
 *    frame, i.e. what the poster already shows) instead of onto a frame
 *    index, and while dragging the video is paused and scrubbed by setting
 *    currentTime directly - a video element can seek on its own, so none of
 *    hero-media.js's frame-decoding/window bookkeeping is needed here.
 *  - Lifting your finger springs back to neutral, exactly like the cursor
 *    leaving the window on desktop, then the clip resumes its own idle loop
 *    (a little faster than real time, since nothing is "idle" once you
 *    stop touching a phone screen the way a cursor can just sit still).
 *
 * Fallbacks: reduced motion, Data Saver, or any load failure all leave the
 * (identical-looking) poster <img> in place - same contract as desktop.
 */
document.addEventListener("DOMContentLoaded", () => {
  const root = document.querySelector("[data-hero-media-mobile]");
  if (!root) return;

  const video = root.querySelector("[data-hero-video]");
  const poster = root.querySelector("[data-hero-poster-mobile]");
  const stage = root.querySelector("[data-hero-stage-mobile]") || root;
  const src = (root.dataset.heroVideoSrc || "").trim();
  if (!video || !src) return;

  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const saveData = !!(navigator.connection && navigator.connection.saveData);
  if (reduceMotion || saveData) return; // poster stays; footage is still complete without motion

  // Only relevant below the same breakpoint styles.css swaps the markup at.
  // If it doesn't apply yet, wait - a rotation or a resized window can make
  // it apply later without a reload.
  const mobileQuery = window.matchMedia("(max-width: 900px), (max-aspect-ratio: 13/10)");
  if (!mobileQuery.matches) {
    const onChange = (e) => {
      if (e.matches) { mobileQuery.removeEventListener("change", onChange); init(); }
    };
    mobileQuery.addEventListener("change", onChange);
    return;
  }
  init();

  function init() {
    // ---- Tuning (identical to hero-media.js, so a drag feels like the same
    // hand behind it as a mouse move does on desktop) ------------------------
    const REACH = 0.8;
    const SMOOTH_TIME = 0.3;
    const MAX_SPEED = 2.4;
    const MAX_REACH_PX = 560;
    const IDLE_RATE = 1.3; // "increase the speed" for the untouched idle loop

    const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

    function smoothDamp(current, goal, v, smoothTime, maxSpeed, dt) {
      const omega = 2 / Math.max(0.0001, smoothTime);
      const x = omega * dt;
      const decay = 1 / (1 + x + 0.48 * x * x + 0.235 * x * x * x);
      const maxChange = maxSpeed * smoothTime;
      const change = clamp(current - goal, -maxChange, maxChange);
      const goal2 = current - change;
      const temp = (v.v + omega * change) * dt;
      v.v = (v.v - omega * temp) * decay;
      let out = goal2 + (change + temp) * decay;
      if (goal - current > 0 === out > goal) {
        out = goal;
        v.v = (out - goal) / dt;
      }
      return out;
    }

    // ---- State ---------------------------------------------------------
    let duration = 0;
    let target = 0;      // -1 (full left) .. 0 (neutral) .. 1 (full right)
    let s = 0;            // smoothed axis value
    const vel = { v: 0 };
    let dragging = false;
    let ready = false;
    let raf = 0;
    let lastTime = 0;

    // The clip's own first frame is neutral (it's exactly what the poster
    // shows), and the timeline loops - so "full left"/"full right" are just
    // the two directions around that point, wrapping at the clip's end.
    function axisToTime(v) {
      const half = duration / 2;
      let t = (v * half) % duration;
      if (t < 0) t += duration;
      return t;
    }

    function aim(clientX) {
      const r = stage.getBoundingClientRect();
      const half = Math.max(1, r.width / 2);
      const reach = Math.min(half * REACH, MAX_REACH_PX);
      target = clamp((clientX - (r.left + half)) / reach, -1, 1);
      start();
    }

    function start() {
      if (!raf) { lastTime = 0; raf = requestAnimationFrame(tick); }
    }

    function tick(now) {
      raf = 0;
      const dt = lastTime ? Math.min((now - lastTime) / 1000, 0.05) : 1 / 60;
      lastTime = now;

      s = smoothDamp(s, target, vel, SMOOTH_TIME, MAX_SPEED, dt);
      if (ready && duration > 0) video.currentTime = axisToTime(s);

      const settled = Math.abs(target - s) < 0.0005 && Math.abs(vel.v) < 0.0005;
      if (!dragging && settled) {
        // Back at neutral: hand off to the clip's own idle loop again.
        if (ready) { video.playbackRate = IDLE_RATE; video.play().catch(() => {}); }
        return;
      }
      raf = requestAnimationFrame(tick);
    }

    // ---- Input -----------------------------------------------------------
    function onDown(e) {
      if (!ready) return;
      dragging = true;
      video.pause();
      aim(e.clientX);
    }
    function onMove(e) {
      if (!dragging) return;
      aim(e.clientX);
    }
    function onUp() {
      if (!dragging) return;
      dragging = false;
      target = 0; // spring back to neutral, then tick() resumes the idle loop
      start();
    }

    root.addEventListener("pointerdown", onDown);
    window.addEventListener("pointermove", onMove, { passive: true });
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onUp);

    // ---- Loading -----------------------------------------------------------
    video.muted = true;
    video.defaultMuted = true;
    video.loop = true;
    video.playsInline = true;
    video.setAttribute("playsinline", "");
    video.removeAttribute("controls");

    video.addEventListener("loadedmetadata", () => { duration = video.duration || 0; }, { once: true });

    video.addEventListener("canplay", () => {
      ready = true;
      video.hidden = false;
      video.playbackRate = IDLE_RATE;
      video.play().catch(() => {});
      // The poster is the clip's own first frame, so the swap is invisible.
      if (poster) requestAnimationFrame(() => { poster.hidden = true; });
    }, { once: true });

    video.addEventListener("error", () => {
      // Leave the poster in place; the hero is still complete without motion.
      console.warn("Hero motion unavailable: mobile video failed to load");
    }, { once: true });

    video.src = src;
    video.load();
  }
});
