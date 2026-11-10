"use strict";

/**
 * Hero media: the bust's head follows the cursor.
 *
 * How it works
 * ------------
 * The footage is not played by time; it is scrubbed by the pointer. The clip
 * has been cut into a frame sequence (tools/build_hero_frames.py) laid out as
 * ONE continuous left <-> right axis with the neutral, front-facing pose in the
 * middle. Cursor to the left of the figure turns the head left, to the right
 * turns it right, over the figure returns it to neutral.
 *
 * Why it stays smooth (no cuts, no jitter)
 *  1. The cursor only sets a TARGET. The value that actually drives the head is
 *     run through a critically-damped spring ("SmoothDamp"): it accelerates
 *     and settles without ever changing velocity abruptly, and it is
 *     frame-rate independent (uses real elapsed time, not frame counts).
 *  2. The position is fractional. Two neighbouring frames are cross-blended by
 *     the fractional part, so motion is continuous at any speed, not stepped
 *     at the frame rate of the source.
 *  3. Frames are decoded ahead of the head (a sliding window of ImageBitmaps).
 *     The head is never allowed to move onto a frame that is not decoded yet;
 *     it holds for a moment instead of jumping, so a slow network or a very
 *     fast flick degrades to "slightly slower", never to a cut.
 *  4. Frames stream in from the neutral pose outward, so the centre of the
 *     range works almost immediately and the range widens as the download
 *     finishes.
 *
 * Fallbacks: reduced motion, no canvas / createImageBitmap, or any load
 * failure all leave the (identical-looking) poster <img> in place.
 */
document.addEventListener("DOMContentLoaded", () => {
  const root = document.querySelector("[data-hero-media]");
  if (!root) return;

  const canvas = root.querySelector("[data-hero-canvas]");
  const poster = root.querySelector("[data-hero-poster]");
  const stage = root.querySelector("[data-hero-stage]") || root;
  if (!canvas || !canvas.getContext || typeof createImageBitmap !== "function") return;
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

  // This 16:9, cursor-scrubbed frame sequence is a desktop-only experience
  // (see hero-media-mobile.js for the touch-scrubbed video used below the
  // breakpoint) — mainly so phones never pull down its 4+ MB frame set.
  // If the viewport is resized across the breakpoint (rotation, a resized
  // window) without a reload, start it the first time it becomes relevant.
  const mobileQuery = window.matchMedia("(max-width: 900px), (max-aspect-ratio: 13/10)");
  if (mobileQuery.matches) {
    const onChange = (e) => {
      if (!e.matches) { mobileQuery.removeEventListener("change", onChange); load().catch((err) => console.warn("Hero motion unavailable:", err)); }
    };
    mobileQuery.addEventListener("change", onChange);
    return;
  }

  // ---- Tuning -------------------------------------------------------------
  const REACH = 0.8;          // fraction of half-width at which the head is fully turned
  const SMOOTH_TIME = 0.3;    // seconds; higher = softer / more lag, lower = snappier
  const MAX_SPEED = 2.4;      // max axis units per second (1 unit = neutral -> full turn)
  const MAX_FRAMES_PER_S = 200; // hard safety cap on frames traversed per second
  const WINDOW = 8;           // frames decoded on each side of the head
  const LOOKAHEAD = 12;       // extra frames decoded in the direction of travel
  const MAX_PENDING = 4;      // concurrent decodes
  const MAX_REACH_PX = 560;   // ...but never more than this many px from the centre
  const MAX_DPR = 1.5;        // source is 1280x720; more backing pixels buy nothing
  const MAX_BACKING_W = 1600; // ...so never allocate a bigger canvas than this

  const ctx = canvas.getContext("2d", { alpha: false, desynchronized: true });
  if (!ctx) return;
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = "high";

  // ---- State --------------------------------------------------------------
  let manifest = null;
  let N = 0;                  // frames on the axis
  let neutral = 0;            // index of the front-facing frame
  const blobs = [];           // index -> Blob (as they arrive)
  const bitmaps = new Map();  // index -> ImageBitmap (decoded window)
  const pending = new Set();
  const failed = new Set();

  let target = 0;             // -1 (full left) .. 0 (neutral) .. 1 (full right)
  let s = 0;                  // smoothed axis value
  const vel = { v: 0 };
  let pos = 0;                // fractional frame index actually shown
  let want = 0;               // fractional frame index the spring is asking for
  let cw = 0, ch = 0;         // canvas backing size
  let lastTime = 0;
  let raf = 0;
  let dirty = true;
  let shown = false;

  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  // ---- Axis mapping -------------------------------------------------------
  // Left half and right half of the axis have different frame counts, so map
  // each side separately: s = -1 -> frame 0, s = 0 -> neutral, s = +1 -> N-1.
  function axisToFrame(v) {
    return v < 0 ? neutral + v * neutral : neutral + v * (N - 1 - neutral);
  }

  // Unity-style critically damped spring: smooth, no overshoot, continuous
  // velocity, independent of frame rate.
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

  // ---- Geometry -----------------------------------------------------------
  function resize() {
    // The canvas is the 16:9 stage; size its backing store to the stage (capped).
    const rect = stage.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, MAX_DPR);
    const w = clamp(Math.round(rect.width * dpr), 1, MAX_BACKING_W);
    const h = Math.max(1, Math.round((w * 9) / 16));
    if (w !== cw || h !== ch) {
      cw = canvas.width = w;
      ch = canvas.height = h;
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = "high";
      dirty = true;
    }
  }

  // ---- Frame availability -------------------------------------------------
  const have = (k) => bitmaps.has(k);

  function nearestLoaded(k) {
    let best = -1, bestD = Infinity;
    for (const key of bitmaps.keys()) {
      const d = Math.abs(key - k);
      if (d < bestD) { bestD = d; best = key; }
    }
    return best;
  }

  // Furthest frames the head may currently travel to without hitting one that
  // is not decoded yet: the contiguous decoded run around the current position.
  function reachBounds() {
    const a = Math.floor(pos), b = Math.ceil(pos);
    const hasA = have(a), hasB = have(b);
    if (!hasA && !hasB) {
      const k = nearestLoaded(Math.round(pos));
      return k < 0 ? [pos, pos] : [k, k];
    }
    let lo, hi;
    if (hasA) { lo = a; while (lo - 1 >= 0 && have(lo - 1)) lo--; } else { lo = b; }
    if (hasB) { hi = b; while (hi + 1 < N && have(hi + 1)) hi++; } else { hi = a; }
    return [lo, hi];
  }

  // ---- Decoding window ----------------------------------------------------
  function windowRange() {
    const ahead = clamp(Math.round(want - pos), -LOOKAHEAD, LOOKAHEAD);
    const a = Math.floor(pos), b = Math.ceil(pos);
    const lo = Math.max(0, Math.min(a, a + ahead) - WINDOW);
    const hi = Math.min(N - 1, Math.max(b, b + ahead) + WINDOW);
    return [lo, hi];
  }

  function pump() {
    if (!manifest) return;
    const [lo, hi] = windowRange();

    // Free memory outside the window (with a little hysteresis).
    for (const [k, bm] of bitmaps) {
      if (k < lo - 3 || k > hi + 3) { bm.close(); bitmaps.delete(k); }
    }

    if (pending.size >= MAX_PENDING) return;
    const centre = Math.round(pos);
    const todo = [];
    for (let k = lo; k <= hi; k++) {
      if (!bitmaps.has(k) && !pending.has(k) && !failed.has(k) && blobs[k]) todo.push(k);
    }
    // Nearest to the head first; when moving, favour the side it is heading to.
    const dir = Math.sign(want - pos);
    todo.sort((p, q) => {
      const dp = Math.abs(p - centre) + (dir && Math.sign(p - centre) !== dir ? 2 : 0);
      const dq = Math.abs(q - centre) + (dir && Math.sign(q - centre) !== dir ? 2 : 0);
      return dp - dq;
    });
    for (const k of todo) {
      if (pending.size >= MAX_PENDING) break;
      pending.add(k);
      createImageBitmap(blobs[k]).then(
        (bm) => {
          pending.delete(k);
          const [wl, wh] = windowRange();
          if (k < wl - 3 || k > wh + 3) { bm.close(); } else { bitmaps.set(k, bm); }
          dirty = true;
          start();
        },
        () => { pending.delete(k); failed.add(k); start(); }
      );
    }
  }

  // ---- Drawing ------------------------------------------------------------
  function drawFrame(bm, alpha) {
    if (!bm) return;
    // The canvas has the footage's own 16:9 shape, so this is an exact fit.
    ctx.globalAlpha = alpha;
    ctx.drawImage(bm, 0, 0, cw, ch);
  }

  function draw() {
    const a = Math.floor(pos);
    const f = pos - a;
    const bmA = bitmaps.get(a) || bitmaps.get(nearestLoaded(a));
    if (!bmA) return;
    drawFrame(bmA, 1);
    if (f > 0.003) {
      const bmB = bitmaps.get(a + 1);
      if (bmB) drawFrame(bmB, f);
    }
    ctx.globalAlpha = 1;
    if (!shown) {
      shown = true;
      canvas.hidden = false;
      // The poster is the same neutral frame, so the swap is invisible.
      if (poster) requestAnimationFrame(() => { poster.hidden = true; });
    }
  }

  // ---- Main loop (runs only while something is moving) --------------------
  function start() {
    if (!raf) { lastTime = 0; raf = requestAnimationFrame(tick); }
  }

  function tick(now) {
    raf = 0;
    const dt = lastTime ? Math.min((now - lastTime) / 1000, 0.05) : 1 / 60;
    lastTime = now;

    if (manifest) {
      s = smoothDamp(s, target, vel, SMOOTH_TIME, MAX_SPEED, dt);
      want = axisToFrame(s);

      // Move toward the spring's position, never faster than the safety cap
      // and never onto a frame that isn't decoded yet.
      const maxStep = MAX_FRAMES_PER_S * dt;
      let next = pos + clamp(want - pos, -maxStep, maxStep);
      const [lo, hi] = reachBounds();
      next = clamp(next, lo, hi);
      if (Math.abs(next - pos) > 1e-5) { pos = next; dirty = true; }

      pump();
      if (dirty) { draw(); dirty = false; }

      // Waiting on data (not on the spring): go idle; new data calls start().
      const blocked =
        (want - pos > 0.002 && pos >= hi - 1e-6) || (pos - want > 0.002 && pos <= lo + 1e-6);
      if (blocked && pending.size === 0 && Math.abs(target - s) < 0.0005 && Math.abs(vel.v) < 0.0005) return;
    }

    const moving =
      Math.abs(target - s) > 0.0005 || Math.abs(vel.v) > 0.0005 || Math.abs(want - pos) > 0.002 || pending.size > 0;
    if (moving) raf = requestAnimationFrame(tick);
  }

  // ---- Input --------------------------------------------------------------
  function aim(clientX) {
    const r = root.getBoundingClientRect();
    const half = Math.max(1, r.width / 2);
    // Fully turned at REACH of the half-width, but never further than
    // MAX_REACH_PX, so big monitors don't need a huge sweep to get a full turn.
    const reach = Math.min(half * REACH, MAX_REACH_PX);
    target = clamp((clientX - (r.left + half)) / reach, -1, 1);
    start();
  }

  window.addEventListener("pointermove", (e) => aim(e.clientX), { passive: true });
  window.addEventListener("pointerdown", (e) => aim(e.clientX), { passive: true });
  // Cursor left the window: settle back to the neutral, front-facing pose.
  document.documentElement.addEventListener("mouseleave", () => { target = 0; start(); });
  window.addEventListener("blur", () => { target = 0; start(); });

  if ("ResizeObserver" in window) new ResizeObserver(() => { resize(); start(); }).observe(stage);
  else window.addEventListener("resize", () => { resize(); start(); });

  // ---- Loading ------------------------------------------------------------
  async function load() {
    const res = await fetch(root.dataset.heroManifest);
    if (!res.ok) throw new Error("manifest " + res.status);
    manifest = await res.json();
    N = manifest.count;
    neutral = manifest.neutral;
    pos = want = neutral;
    resize();
    start();

    const fres = await fetch(root.dataset.heroFrames);
    if (!fres.ok) throw new Error("frames " + fres.status);

    const total = manifest.bytes;
    const type = manifest.mime || "image/webp";
    const publish = (buf, upTo, cursor) => {
      // Publish every frame whose bytes have fully arrived, in file order
      // (neutral outward).
      while (cursor.i < manifest.order.length) {
        const k = manifest.order[cursor.i];
        const [o, l] = manifest.frames[k];
        if (o + l > upTo) break;
        blobs[k] = new Blob([buf.subarray(o, o + l)], { type });
        cursor.i++;
      }
    };

    const cursor = { i: 0 };
    if (fres.body && fres.body.getReader) {
      const buf = new Uint8Array(total);
      const reader = fres.body.getReader();
      let got = 0;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (got + value.length > total) break; // unexpected size: keep what we have
        buf.set(value, got);
        got += value.length;
        publish(buf, got, cursor);
        pump();
        start();
      }
    } else {
      const buf = new Uint8Array(await fres.arrayBuffer());
      publish(buf, buf.length, cursor);
      pump();
      start();
    }
  }

  load().catch((err) => {
    // Leave the poster in place; the hero is still complete without motion.
    console.warn("Hero motion unavailable:", err);
  });

  window.addEventListener("beforeunload", () => {
    if (raf) cancelAnimationFrame(raf);
    for (const bm of bitmaps.values()) bm.close();
    bitmaps.clear();
  });
});
