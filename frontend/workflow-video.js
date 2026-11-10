"use strict";

/**
 * FOLIX workflow-video component.
 *
 * Any <figure data-workflow-video data-src="..." data-poster="..."> on the page
 * is upgraded by this file. Markup contract (see how-it-works.html):
 *
 *   [data-workflow-video]   root; carries data-src / data-poster
 *     [data-wf-video]       the <video> element (starts hidden)
 *     [data-wf-toggle]      the play/pause button (starts hidden)
 *     .wf-placeholder       what people see until a real video is ready
 *
 * The root's data-state drives the CSS:
 *   "placeholder"  no src set, or the file failed to load/decode
 *   "loading"      src set, waiting for the first frame
 *   "ready"        video is showing (placeholder faded out)
 * data-playing ("true"/"false") reflects whether the video is playing.
 *
 * Behaviour:
 *  - No src           -> stays on the placeholder. Nothing is requested.
 *  - Missing/bad file -> falls back to the placeholder, never an empty box.
 *  - Autoplays muted + looped only while at least half on screen; pauses when
 *    scrolled away. A manual pause is respected and not overridden.
 *  - "Reduce motion" or Data Saver -> no autoplay. The poster shows and the
 *    visitor starts the video themselves.
 *  - No native controls (no browser chrome); a single small play/pause button
 *    provides the pause control that autoplaying media needs for accessibility.
 */
(function () {
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const saveData = !!(navigator.connection && navigator.connection.saveData);
  const deferPlayback = reduceMotion || saveData;

  function initWorkflowVideo(root) {
    const video = root.querySelector("[data-wf-video]");
    const toggle = root.querySelector("[data-wf-toggle]");
    const src = (root.dataset.src || "").trim();
    const poster = (root.dataset.poster || "").trim();

    if (!video || !src) {
      root.dataset.state = "placeholder";
      return;
    }

    let userPaused = false;
    let observer = null;

    // Autoplay in most browsers requires the muted *property*, not just the attribute.
    video.muted = true;
    video.defaultMuted = true;
    video.loop = true;
    video.playsInline = true;
    video.setAttribute("playsinline", "");
    video.removeAttribute("controls");
    video.preload = saveData ? "none" : "metadata";
    if (poster) video.poster = poster;
    video.src = src;
    video.hidden = false;

    // With a poster (or when playback is deferred) there is something to show
    // straight away. Otherwise keep the placeholder until the first frame.
    root.dataset.state = poster || deferPlayback ? "ready" : "loading";
    root.dataset.playing = "false";
    if (toggle) toggle.hidden = false;

    function sync() {
      const playing = !video.paused;
      root.dataset.playing = String(playing);
      if (toggle) toggle.setAttribute("aria-label", playing ? "Pause workflow video" : "Play workflow video");
    }

    function play() {
      const attempt = video.play();
      if (attempt && typeof attempt.catch === "function") {
        // Autoplay blocked (e.g. low-power mode): the visitor can press play.
        attempt.catch(sync);
      }
    }

    function fallBackToPlaceholder() {
      if (observer) observer.disconnect();
      video.pause();
      video.removeAttribute("src");
      video.removeAttribute("poster");
      video.load();
      video.hidden = true;
      if (toggle) toggle.hidden = true;
      root.dataset.state = "placeholder";
      root.dataset.playing = "false";
    }

    video.addEventListener("loadeddata", () => { root.dataset.state = "ready"; });
    video.addEventListener("error", fallBackToPlaceholder);
    video.addEventListener("play", sync);
    video.addEventListener("pause", sync);

    if (toggle) {
      sync();
      toggle.addEventListener("click", () => {
        if (video.paused) {
          userPaused = false;
          play();
        } else {
          userPaused = true;
          video.pause();
        }
      });
    }

    if (!deferPlayback) {
      if ("IntersectionObserver" in window) {
        observer = new IntersectionObserver((entries) => {
          entries.forEach((entry) => {
            if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
              if (!userPaused) play();
            } else if (!entry.isIntersecting || entry.intersectionRatio < 0.2) {
              video.pause();
            }
          });
        }, { threshold: [0, 0.2, 0.5] });
        observer.observe(root);
      } else {
        play();
      }
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("[data-workflow-video]").forEach(initWorkflowVideo);
  });
})();
