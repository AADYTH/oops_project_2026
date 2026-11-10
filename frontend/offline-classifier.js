"use strict";

/**
 * CropGuardOffline
 * -----------------
 * Two independent pieces of the "make it usable where it matters most" and
 * "makes today's version trustworthy" upgrades:
 *
 * 1. Image quality pre-check (Tier 3 upgrade #11) — a cheap client-side
 *    blur/brightness check using <canvas> pixel data, run before a photo
 *    is even uploaded. This is the client-side half; the Python inference
 *    service repeats an equivalent check server-side so a modified/bypassed
 *    client can't skip it (see inference-service/main.py).
 *
 * 2. Offline queue + genuine on-device inference, when available (Tier 2
 *    upgrade #4) — when there's no connection, a scan is:
 *      a) run through a REAL on-device model via onnx-classifier.js, IF
 *         one has actually been exported and installed (see
 *         inference-service/scripts/export_onnx_model.py) — never a
 *         colour/brightness heuristic dressed up as a diagnosis. CropGuard's
 *         architecture explicitly forbids using the color heuristic as a
 *         diagnosis system (see README, "Offline mode" / "Real-world image
 *         handling"), and an earlier version of this file did exactly
 *         that — classifying "green vs. yellow-brown pixel ratio" as a
 *         disease guess. That has been removed. If no on-device model is
 *         installed, the honest answer is ANALYSIS_UNAVAILABLE, not a
 *         fabricated guess wearing a low-confidence label.
 *      b) queued in IndexedDB and automatically re-submitted to the real
 *         backend the moment connectivity returns, replacing whatever
 *         offline result (real on-device estimate, or "unavailable") was
 *         shown with an authoritative diagnosis.
 *
 * Genuinely offline ML inference requires a model actually trained and
 * exported to ONNX with real weights for the browser to run — see
 * onnx-classifier.js and inference-service/scripts/export_onnx_model.py for
 * the (real, but currently un-populated-with-weights) wiring for that.
 */
const CropGuardOffline = (() => {
  const DB_NAME = "cropguard-offline";
  const DB_VERSION = 1;
  const STORE_NAME = "pendingScans";

  // -------------------------------------------------------------------
  // IndexedDB helpers
  // -------------------------------------------------------------------
  function openDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(STORE_NAME)) {
          db.createObjectStore(STORE_NAME, { keyPath: "id", autoIncrement: true });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function queueScan({ blob, plantPart, latitude, longitude, offlineEstimate }) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, "readwrite");
      const store = tx.objectStore(STORE_NAME);
      const record = { blob, plantPart, latitude, longitude, offlineEstimate, queuedAt: Date.now() };
      const req = store.add(record);
      req.onsuccess = () => resolve({ ...record, id: req.result });
      req.onerror = () => reject(req.error);
    });
  }

  async function getQueuedScans() {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, "readonly");
      const req = tx.objectStore(STORE_NAME).getAll();
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function removeQueuedScan(id) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, "readwrite");
      const req = tx.objectStore(STORE_NAME).delete(id);
      req.onsuccess = () => resolve();
      req.onerror = () => reject(req.error);
    });
  }

  /**
   * Re-submits every queued scan to the real backend. Call on reconnect.
   *
   * Posts to the FOLIX Gemini endpoint, POST {apiBaseUrl}/api/scan/analyze
   * — the same authoritative endpoint the live (online) scanner uses — not
   * the legacy /api/v1/scans endpoint this function used to default to.
   * That endpoint reads exactly one multipart part, "image" (see
   * FolixScanController's docstring: "no user identity, no coordinates, no
   * plant-part hint"), so only the image is sent here too, matching the
   * same privacy stance as an online scan; plantPart/latitude/longitude
   * stay recorded on the queued record itself for the caller's own use.
   * The response is the FOLIX diagnosis contract object, returned via
   * onEach exactly as received — this module does not interpret it.
   *
   * BUGFIX (Issue #2): scanner-experience.js calls this both once on page load and on every
   * browser `online` event, and flaky connectivity can fire `online` more than once in quick
   * succession. Without a guard, two overlapping calls could both read the same still-queued scan
   * (each snapshotting the queue with getQueuedScans() before either had removed it) and both POST
   * it, submitting the same offline scan twice. `inFlightSync` makes a second call while one is
   * already running simply await and share the FIRST call's result instead of starting its own
   * overlapping loop, so a given queued scan is only ever POSTed by one in-flight sync at a time.
   */
  let inFlightSync = null;

  function trySyncQueue(apiBaseUrl, onEach) {
    if (inFlightSync) return inFlightSync;
    inFlightSync = runSyncQueue(apiBaseUrl, onEach).finally(() => { inFlightSync = null; });
    return inFlightSync;
  }

  async function runSyncQueue(apiBaseUrl, onEach) {
    if (!navigator.onLine) return { synced: 0, remaining: (await getQueuedScans()).length };

    const pending = await getQueuedScans();
    let synced = 0;
    for (const scan of pending) {
      try {
        const formData = new FormData();
        formData.append("image", scan.blob, "offline-scan.jpg");
        const response = await fetch(`${apiBaseUrl}/api/scan/analyze`, { method: "POST", body: formData });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const result = await response.json();
        await removeQueuedScan(scan.id);
        synced++;
        if (onEach) onEach(scan, result, null);
      } catch (err) {
        // Leave it queued; we'll retry on the next sync trigger.
        if (onEach) onEach(scan, null, err);
        break; // stop on first failure — likely means we're offline again
      }
    }
    const remaining = (await getQueuedScans()).length;
    return { synced, remaining };
  }

  // -------------------------------------------------------------------
  // Image quality pre-check (Tier 3 #11)
  // -------------------------------------------------------------------
  function getImageData(imgOrCanvas) {
    const canvas = document.createElement("canvas");
    // Downscale for speed; blur/brightness estimates don't need full resolution.
    const targetW = 256;
    const scale = targetW / (imgOrCanvas.naturalWidth || imgOrCanvas.width);
    canvas.width = targetW;
    canvas.height = Math.round((imgOrCanvas.naturalHeight || imgOrCanvas.height) * scale);
    const ctx = canvas.getContext("2d");
    ctx.drawImage(imgOrCanvas, 0, 0, canvas.width, canvas.height);
    return ctx.getImageData(0, 0, canvas.width, canvas.height);
  }

  function toGrayscale(imageData) {
    const { data, width, height } = imageData;
    const gray = new Float32Array(width * height);
    for (let i = 0, p = 0; i < data.length; i += 4, p++) {
      gray[p] = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
    }
    return { gray, width, height };
  }

  /** Simple discrete Laplacian variance — the same cheap blur estimator used server-side. */
  function laplacianVariance(gray, width, height) {
    let sum = 0, sumSq = 0, count = 0;
    for (let y = 1; y < height - 1; y++) {
      for (let x = 1; x < width - 1; x++) {
        const i = y * width + x;
        const lap =
          4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width];
        sum += lap;
        sumSq += lap * lap;
        count++;
      }
    }
    if (count === 0) return 0;
    const mean = sum / count;
    return sumSq / count - mean * mean;
  }

  function checkImageQuality(imgOrCanvas, thresholds = {}) {
    const blurMin = thresholds.blurMin ?? 25;
    const brightnessMin = thresholds.brightnessMin ?? 20;
    const brightnessMax = thresholds.brightnessMax ?? 250;
    // Mirrors the server-side rule (see inference-service/main.py): a photo
    // is only rejected for brightness if it's ALSO texture-flat. Pale/white
    // symptoms (powdery mildew, chlorosis) are legitimately bright but full
    // of detail, and must not be rejected just for being light-colored.
    const flatImageBlurCeiling = thresholds.flatImageBlurCeiling ?? 60;

    const imageData = getImageData(imgOrCanvas);
    const { gray, width, height } = toGrayscale(imageData);
    const blurScore = laplacianVariance(gray, width, height);
    let brightnessSum = 0;
    for (let i = 0; i < gray.length; i++) brightnessSum += gray[i];
    const brightness = brightnessSum / gray.length;

    if (blurScore < blurMin) {
      return { passed: false, reason: "IMAGE_TOO_BLURRY", blurScore, brightness };
    }
    if (brightness < brightnessMin && blurScore < flatImageBlurCeiling) {
      return { passed: false, reason: "IMAGE_TOO_DARK", blurScore, brightness };
    }
    if (brightness > brightnessMax && blurScore < flatImageBlurCeiling) {
      return { passed: false, reason: "IMAGE_OVEREXPOSED", blurScore, brightness };
    }
    return { passed: true, blurScore, brightness };
  }

  // -------------------------------------------------------------------
  // Offline result while a real diagnosis is pending sync.
  //
  // Deliberately NOT a color/brightness heuristic pretending to be a
  // diagnosis (CropGuard architecture forbids that — see README "Offline
  // mode": "No color heuristic. No hard-coded disease guessing."). If a
  // real on-device model has been installed (see onnx-classifier.js), the
  // caller should use CropGuardOnnx.classify(...) directly instead of this
  // function and only fall back to this honest "unavailable" result when
  // CropGuardOnnx reports no model is installed.
  // -------------------------------------------------------------------
  function offlineAnalysisUnavailable() {
    return {
      cropName: null,
      diseaseName: null,
      confidencePercent: null,
      confidenceLevel: "ANALYSIS_UNAVAILABLE",
      severityPercent: null,
      isEpidemicRisk: false,
      isOfflineEstimate: true,
      note: "You're offline and no on-device model is installed on this device, so this photo can't be " +
            "diagnosed right now. It's been queued and will be diagnosed automatically the moment you're " +
            "back online — no guess has been made in the meantime.",
    };
  }

  return {
    queueScan,
    getQueuedScans,
    removeQueuedScan,
    trySyncQueue,
    checkImageQuality,
    offlineAnalysisUnavailable,
  };
})();
