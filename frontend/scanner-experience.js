"use strict";

/**
 * FOLIX Scanner — Stage 3 frontend experience.
 *
 * This file owns ONLY the frontend states of the Scanner page:
 *   empty -> (camera) -> preview -> analyzing -> result | uncertain | error
 *
 * Stage 4: `requestAnalysis()` now performs the real analysis. It downsizes
 * the photo in the browser (which also strips EXIF/GPS), makes exactly ONE
 * `POST /api/scan/analyze` request per scan, validates the structured
 * response, and routes it to `renderDiagnosis(result)` or
 * `renderUncertain(result)`. Failures of the request itself that are NOT a
 * connectivity problem (server error, rate limit, timeout, bad file) go to a
 * separate calm error state that never looks like a diagnosis. The browser
 * never sees an API key and never contacts Gemini directly.
 *
 * Offline queue + Gemini-on-reconnect: when the device is offline (or the
 * `/api/scan/analyze` fetch itself fails to reach the network), the scan is
 * queued locally via `CropGuardOffline.queueScan` instead of showing the
 * error state. If `CropGuardOnnx` reports a real on-device model is
 * installed and confident enough (see `ONNX_CONFIDENCE_THRESHOLD`), its
 * estimate is shown as a clearly-marked provisional result through the same
 * `renderDiagnosis` card; otherwise the honest
 * `CropGuardOffline.offlineAnalysisUnavailable()` state is shown through the
 * same `renderUncertain` card. The on-device model is entirely optional —
 * the queue-then-confirm flow works with no model file present at all. On
 * reconnect (`online` event, and once on load), `CropGuardOffline.trySyncQueue`
 * re-submits every queued scan to the real `/api/scan/analyze` endpoint; a
 * successful sync replaces whatever was shown for that scan with the
 * authoritative Gemini result via the normal `routeResult` path, indistinguishable
 * from an online scan. See `handleOfflineScan`/`syncOfflineQueue` below.
 *
 * The two render functions accept the structured result object described in
 * the project's AI integration contract:
 *
 *   {
 *     plant_detected: boolean,
 *     plant_part: "leaf" | "stem" | "fruit" | "whole_plant" | "unknown",
 *     crop: string,
 *     disease: string | "unknown",
 *     severity: "mild" | "moderate" | "severe" | "unknown",
 *     severity_percent: number 0-100 | null,      // optional: visible share of tissue affected
 *     spread_chance_percent: number 0-100 | null, // optional: backend estimate, independent of confidence
 *     confidence: "high" | "moderate" | "low",
 *     symptoms: string[],
 *     reasoning: string,
 *     recommended_actions: string[],
 *     needs_human_review: boolean
 *   }
 *
 * Both render functions are exposed on `window.FolixScanner` so a future
 * integration stage (or a developer testing the UI) can call them directly,
 * e.g. from the console:
 *
 *   FolixScanner.renderDiagnosis({ crop: "Tomato", disease: "Early blight", ... })
 *   FolixScanner.renderUncertain({ reasoning: "...", ... })
 *
 * without needing to trigger a real scan.
 *
 * Stage 5B: the "Save Report" button on both the result and uncertain panels
 * now sends the diagnosis exactly as displayed to `POST /api/scan/reports`
 * (see `saveReport`/`toReportRequestBody`). This never calls Gemini again
 * and never alters the diagnosis — only what is already on screen is sent.
 */
(function () {
  const MAX_FILE_BYTES = 20 * 1024 * 1024; // 20MB — a generous ceiling, not an image-quality gate.
  const SUPPORTED_TYPES = ["image/jpeg", "image/jpg", "image/png", "image/webp"];

  // ---- Stage 4: analysis request settings ----
  // Same convention as the rest of the app: pages/tests may set window.CROPGUARD_API_BASE_URL.
  const API_BASE_URL = window.CROPGUARD_API_BASE_URL || "http://localhost:8080";
  const ANALYZE_URL = API_BASE_URL + "/api/scan/analyze";
  // Stage 5B: connects the existing "Save Report" action to the existing FOLIX report persistence layer.
  const SAVE_REPORT_URL = API_BASE_URL + "/api/scan/reports";
  // Forward Case: the same endpoints the Dashboard's forward flow uses.
  const DIRECTORY_URL = API_BASE_URL + "/api/farmer/agri-network/directory";
  const FORWARD_URL = (reportId) => SAVE_REPORT_URL + "/" + encodeURIComponent(reportId) + "/forward";
  // Must exceed the server's own Gemini timeout (45s) so the server gets to answer first.
  const ANALYSIS_TIMEOUT_MS = Number(window.FOLIX_ANALYSIS_TIMEOUT_MS) > 0 ? Number(window.FOLIX_ANALYSIS_TIMEOUT_MS) : 60000;
  const UPLOAD_MAX_EDGE = 1920;              // px, long edge. Plenty for lesions; keeps uploads small on rural links.
  const UPLOAD_JPEG_QUALITY = 0.88;
  const RESIZE_ABOVE_BYTES = 2 * 1024 * 1024; // smaller files are sent as-is (the server strips metadata too)
  const SERVER_MAX_UPLOAD_BYTES = 10 * 1024 * 1024; // mirrors the backend limit
  const PLANT_PARTS = ["leaf", "stem", "fruit", "whole_plant", "unknown"];
  const SEVERITIES = ["mild", "moderate", "severe", "unknown"];
  const CONFIDENCES = ["high", "moderate", "low"];

  // ---- Offline queue + on-device model settings ----
  // Minimum on-device confidence (0–1) required to show CropGuardOnnx's estimate as a provisional
  // result instead of the honest "analysis unavailable" state. Configurable per the same
  // window.FOLIX_* override convention as ANALYSIS_TIMEOUT_MS above.
  const ONNX_CONFIDENCE_THRESHOLD = Number(window.FOLIX_ONNX_CONFIDENCE_THRESHOLD) > 0 ? Number(window.FOLIX_ONNX_CONFIDENCE_THRESHOLD) : 0.6;
  const GEO_TIMEOUT_MS = 4000; // best-effort only; never block queuing an offline scan on this

  const state = {
    status: "empty", // empty | camera | preview | analyzing | result | uncertain | error
    file: null,
    objectUrl: null,
    cameraStream: null,
    analysis: { runId: 0, controller: null }, // Stage 4: identifies the in-flight request so stale results are ignored
    offline: { queueId: null }, // id (from CropGuardOffline) of the queued scan currently shown on screen, if any
    lastScanTime: null,
  };

  const el = {};

  function qs(id) { return document.getElementById(id); }

  function cacheEls() {
    el.root = qs("scnRoot");
    el.live = qs("scnLiveRegion");

    el.panels = {
      empty: qs("scnPanelEmpty"),
      camera: qs("scnPanelCamera"),
      preview: qs("scnPanelPreview"),
      analyzing: qs("scnPanelAnalyzing"),
      result: qs("scnPanelResult"),
      uncertain: qs("scnPanelUncertain"),
      error: qs("scnPanelError"),
    };

    el.dropzone = qs("scnDropzone");
    el.fileInput = qs("scnFileInput");
    el.useCameraBtn = qs("scnUseCameraBtn");

    el.cameraVideo = qs("scnCameraVideo");
    el.cameraCanvas = qs("scnCameraCanvas");
    el.cameraCancelBtn = qs("scnCameraCancelBtn");
    el.cameraCaptureBtn = qs("scnCameraCaptureBtn");

    el.previewImg = qs("scnPreviewImg");
    el.changeBtn = qs("scnChangeBtn");
    el.removeBtn = qs("scnRemoveBtn");
    el.analyzeBtn = qs("scnAnalyzeBtn");
    el.fileError = qs("scnFileError");

    el.analyzingImg = qs("scnAnalyzingImg");
    el.cancelAnalysisBtn = qs("scnCancelAnalysisBtn");

    el.resultContent = qs("scnResultContent");
    el.uncertainContent = qs("scnUncertainContent");

    el.errorTitle = qs("scnErrorTitle");
    el.errorMessage = qs("scnErrorMessage");
    el.errorRetryBtn = qs("scnErrorRetryBtn");
    el.errorChooseBtn = qs("scnErrorChooseBtn");

    el.imageModal = qs("scnImageModal");
    el.modalImg = qs("scnModalImg");
    el.modalCloseBtn = qs("scnModalCloseBtn");
    el.modalBackdrop = qs("scnModalBackdrop");
  }

  function openImageModal(url) {
    if (!el.imageModal || !el.modalImg || !url) return;
    el.modalImg.src = url;
    el.imageModal.hidden = false;
    el.imageModal.setAttribute("aria-hidden", "false");
    document.body.style.overflow = "hidden";
  }

  function closeImageModal() {
    if (!el.imageModal) return;
    el.imageModal.hidden = true;
    el.imageModal.setAttribute("aria-hidden", "true");
    if (el.modalImg) el.modalImg.removeAttribute("src");
    document.body.style.overflow = "";
  }

  function announce(msg) {
    if (el.live) el.live.textContent = msg;
  }

  function setStatus(next) {
    state.status = next;
    if (el.root) el.root.dataset.state = next;
    Object.entries(el.panels).forEach(([key, panel]) => {
      if (!panel) return;
      panel.hidden = key !== next;
    });
    const frame = el.panels.analyzing ? el.panels.analyzing.querySelector(".scn-frame") : null;
    if (frame) frame.classList.toggle("scn-is-analyzing", next === "analyzing");
  }

  // ---------------------------------------------------------------------
  // Image selection / preview / removal
  // ---------------------------------------------------------------------

  function isSupportedFile(file) {
    return SUPPORTED_TYPES.includes((file.type || "").toLowerCase());
  }

  function showFileError(msg) {
    if (!el.fileError) return;
    el.fileError.textContent = msg;
    el.fileError.hidden = false;
  }

  function clearFileError() {
    if (!el.fileError) return;
    el.fileError.hidden = true;
    el.fileError.textContent = "";
  }

  function revokeObjectUrl() {
    if (state.objectUrl) {
      URL.revokeObjectURL(state.objectUrl);
      state.objectUrl = null;
    }
  }

  function selectFile(file) {
    if (!file) return;
    clearFileError();

    if (!isSupportedFile(file)) {
      showFileError("That file type isn't supported. Please choose a JPG, PNG or WEBP image.");
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      showFileError("That image is too large. Please choose a file under 20MB.");
      return;
    }

    revokeObjectUrl();
    state.file = file;
    state.objectUrl = URL.createObjectURL(file);

    if (el.previewImg) el.previewImg.src = state.objectUrl;

    setStatus("preview");
    announce("Image selected. Ready to analyze.");
  }

  function removeImage() {
    abortAnalysis();
    revokeObjectUrl();
    state.file = null;
    state.offline.queueId = null; // this panel is going away; a later sync must not try to update it
    if (el.fileInput) el.fileInput.value = "";
    if (el.previewImg) el.previewImg.removeAttribute("src");
    if (el.analyzingImg) el.analyzingImg.removeAttribute("src");
    clearFileError();
    stopCamera();
    setStatus("empty");
    announce("Image removed.");
  }

  function changeImage() {
    if (el.fileInput) el.fileInput.click();
  }

  // ---------------------------------------------------------------------
  // Drag & drop
  // ---------------------------------------------------------------------

  function wireDropzone() {
    if (!el.dropzone) return;

    el.dropzone.addEventListener("click", () => changeImage());

    el.dropzone.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        changeImage();
      }
    });

    ["dragenter", "dragover"].forEach((evt) => {
      el.dropzone.addEventListener(evt, (e) => {
        e.preventDefault();
        el.dropzone.classList.add("scn-dragover");
      });
    });
    ["dragleave", "dragend", "drop"].forEach((evt) => {
      el.dropzone.addEventListener(evt, (e) => {
        e.preventDefault();
        el.dropzone.classList.remove("scn-dragover");
      });
    });
    el.dropzone.addEventListener("drop", (e) => {
      const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
      if (file) selectFile(file);
    });
  }

  // ---------------------------------------------------------------------
  // Camera capture
  // ---------------------------------------------------------------------

  function cameraSupported() {
    return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
  }

  async function startCamera() {
    if (!cameraSupported()) {
      showFileError("Camera capture isn't supported on this browser or device. Please upload a photo instead.");
      return;
    }
    setStatus("camera");
    try {
      state.cameraStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: "environment" },
        audio: false,
      });
      if (el.cameraVideo) el.cameraVideo.srcObject = state.cameraStream;
    } catch (err) {
      stopCamera();
      setStatus("empty");
      showFileError("Camera access was not available. Please upload a photo instead.");
    }
  }

  function stopCamera() {
    if (state.cameraStream) {
      state.cameraStream.getTracks().forEach((track) => track.stop());
      state.cameraStream = null;
    }
    if (el.cameraVideo) el.cameraVideo.srcObject = null;
  }

  function capturePhoto() {
    if (!el.cameraVideo || !el.cameraCanvas) return;
    const video = el.cameraVideo;
    const canvas = el.cameraCanvas;
    const size = Math.min(video.videoWidth || 0, video.videoHeight || 0);
    if (!size) return;

    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext("2d");
    const sx = (video.videoWidth - size) / 2;
    const sy = (video.videoHeight - size) / 2;
    ctx.drawImage(video, sx, sy, size, size, 0, 0, size, size);

    canvas.toBlob((blob) => {
      if (!blob) return;
      const file = new File([blob], `folix-capture-${Date.now()}.jpg`, { type: "image/jpeg" });
      stopCamera();
      selectFile(file);
    }, "image/jpeg", 0.92);
  }

  function cancelCamera() {
    stopCamera();
    setStatus("empty");
  }

  // ---------------------------------------------------------------------
  // Analysis — Stage 4: the real Gemini-backed request
  // ---------------------------------------------------------------------

  /** A failure of the analysis REQUEST (not a diagnosis). Carries only user-safe text. */
  class AnalysisFailure extends Error {
    constructor(code, message, retryable) {
      super(message || (ERROR_COPY[code] || ERROR_COPY.ANALYSIS_FAILED).message);
      this.code = code;
      this.retryable = typeof retryable === "boolean" ? retryable : (ERROR_COPY[code] || ERROR_COPY.ANALYSIS_FAILED).retryable;
    }
  }

  // Local copy of the user-facing wording, used whenever the server's own structured error is absent
  // (network failures, proxies, framework-level rejections such as 413 before our handler runs).
  const ERROR_COPY = {
    INVALID_IMAGE: { message: "That file couldn't be read as an image. Please choose a different photo.", retryable: false },
    UNSUPPORTED_IMAGE_TYPE: { message: "That file type isn't supported. Please choose a JPG, PNG or WEBP image.", retryable: false },
    IMAGE_TOO_LARGE: { message: "That image is too large. Please choose a smaller photo.", retryable: false },
    RATE_LIMITED: { message: "FOLIX is handling a lot of requests right now. Please wait a moment, then try again.", retryable: true },
    ANALYSIS_TIMEOUT: { message: "The analysis took too long to finish. Please try again.", retryable: true },
    ANALYSIS_UNAVAILABLE: { message: "FOLIX analysis isn't available right now. Please try again later.", retryable: false },
    ANALYSIS_FAILED: { message: "FOLIX couldn't complete the analysis. Please try again.", retryable: true },
    NETWORK_ERROR: { message: "FOLIX couldn't reach the analysis service. Check your connection and try again.", retryable: true },
  };

  function codeFromStatus(status) {
    if (status === 400) return "INVALID_IMAGE";
    if (status === 413) return "IMAGE_TOO_LARGE";
    if (status === 415) return "UNSUPPORTED_IMAGE_TYPE";
    if (status === 429) return "RATE_LIMITED";
    if (status === 504) return "ANALYSIS_TIMEOUT";
    if (status === 401 || status === 403 || status === 404) return "ANALYSIS_UNAVAILABLE";
    return "ANALYSIS_FAILED";
  }

  /** Builds an AnalysisFailure from an HTTP error response, preferring the server's structured `error` object. */
  function failureFromResponse(status, payload) {
    const err = payload && typeof payload === "object" && payload.error && typeof payload.error === "object" ? payload.error : null;
    const code = err && typeof err.code === "string" && ERROR_COPY[err.code] ? err.code : codeFromStatus(status);
    const message = err && typeof err.message === "string" && err.message.length > 0 && err.message.length <= 240 ? err.message : null;
    const retryable = err && typeof err.retryable === "boolean" ? err.retryable : undefined;
    return new AnalysisFailure(code, message, retryable);
  }

  function isStringArray(v) {
    return Array.isArray(v) && v.every((x) => typeof x === "string");
  }

  /**
   * Defence in depth: the backend already validates, but a proxy/misrouted request can still return a
   * 200 that is not our contract (e.g. an HTML page). Anything that is not exactly the contract is
   * treated as a failed request — it is never rendered as a diagnosis.
   */
  function validateResult(p) {
    if (!p || typeof p !== "object" || Array.isArray(p)) return null;
    if (typeof p.plant_detected !== "boolean" || typeof p.needs_human_review !== "boolean") return null;
    if (typeof p.crop !== "string" || typeof p.disease !== "string" || typeof p.reasoning !== "string") return null;
    if (!PLANT_PARTS.includes(p.plant_part) || !SEVERITIES.includes(p.severity) || !CONFIDENCES.includes(p.confidence)) return null;
    if (!isStringArray(p.symptoms) || !isStringArray(p.recommended_actions)) return null;
    return p;
  }

  /**
   * The uncertainty routing rule.
   *
   * `needs_human_review` is deliberately NOT part of this test (see the earlier bugfix: it is also set
   * for clearly named, severe or regulated diseases, and using it as a routing signal hid real
   * diagnoses).
   *
   * BUGFIX 2: `confidence === "low"` is no longer part of it either. Gemini frequently names the
   * RIGHT disease but rates its own confidence "low". Sending those to the generic "more information is
   * needed" screen threw away the disease name, the severity, the severity %, the spread-chance % and
   * the epidemic-risk warning that the backend had already worked out — exactly the numbers the farmer
   * needs. A named, low-confidence diagnosis now renders as a full result, clearly labelled as a
   * low-confidence "possible match" with a human-review prompt (see renderDiagnosis), instead of being
   * hidden.
   *
   * Genuine "we don't know" is exactly: no plant detected, or no named disease. Those still get the
   * honest uncertain screen, and nothing is ever invented for them.
   */
  function needsUncertaintyExperience(result) {
    const disease = String(result.disease || "").trim().toLowerCase();
    return (
      result.plant_detected === false ||
      disease === "" ||
      disease === "unknown"
    );
  }

  function routeResult(result) {
    if (needsUncertaintyExperience(result)) renderUncertain(result);
    else renderDiagnosis(result);
  }

  function beginAnalysis() {
    if (!state.file) return;
    // Exactly one request per scan: ignore clicks while a request is already pending.
    if (state.status !== "preview" && state.status !== "error") return;
    if (el.analyzingImg && state.objectUrl) el.analyzingImg.src = state.objectUrl;
    setStatus("analyzing");
    announce("Analyzing crop.");
    requestAnalysis(state.file);
  }

  /** Marks any in-flight analysis stale and cancels its network request. */
  function abortAnalysis() {
    state.analysis.runId += 1;
    const controller = state.analysis.controller;
    state.analysis.controller = null;
    if (controller) {
      try { controller.abort(); } catch (e) { /* nothing to do */ }
    }
  }

  function cancelAnalysis() {
    abortAnalysis();
    setStatus("preview");
    announce("Analysis cancelled.");
  }

  async function decodeImage(file) {
    if (typeof createImageBitmap === "function") return createImageBitmap(file);
    return new Promise((resolve, reject) => {
      const img = new Image();
      const url = URL.createObjectURL(file);
      img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("decode failed")); };
      img.src = url;
    });
  }

  /**
   * Local, network-free preparation: downsizes large photos to a JPEG (which drops EXIF/GPS, since a canvas
   * re-encode carries no metadata). This is size control, not a quality gate — blurry or partial photos are
   * sent as they are. If anything about resizing fails, the original is sent (the server strips metadata too).
   */
  async function prepareUpload(file) {
    try {
      const bitmap = await decodeImage(file);
      const w = bitmap.naturalWidth || bitmap.width;
      const h = bitmap.naturalHeight || bitmap.height;
      const scale = Math.min(1, UPLOAD_MAX_EDGE / Math.max(w, h));
      if (w > 0 && h > 0 && (scale < 1 || file.size > RESIZE_ABOVE_BYTES)) {
        const tw = Math.max(1, Math.round(w * scale));
        const th = Math.max(1, Math.round(h * scale));
        const canvas = document.createElement("canvas");
        canvas.width = tw;
        canvas.height = th;
        const ctx = canvas.getContext("2d");
        ctx.fillStyle = "#ffffff"; // JPEG has no alpha: flatten transparent PNG/WEBP onto white, not black
        ctx.fillRect(0, 0, tw, th);
        ctx.drawImage(bitmap, 0, 0, tw, th);
        if (typeof bitmap.close === "function") bitmap.close();
        const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", UPLOAD_JPEG_QUALITY));
        if (blob && blob.size > 0 && blob.size <= SERVER_MAX_UPLOAD_BYTES) {
          return { blob, name: "folix-upload.jpg" };
        }
      } else if (typeof bitmap.close === "function") {
        bitmap.close();
      }
    } catch (err) {
      /* fall through to sending the original */
    }
    if (file.size > SERVER_MAX_UPLOAD_BYTES) throw new AnalysisFailure("IMAGE_TOO_LARGE");
    return { blob: file, name: file.name || "upload" };
  }

  /**
   * Sends the selected photo for analysis and renders the outcome.
   *
   *   POST /api/scan/analyze  (multipart, one field: "image")  ->  structured result
   *     -> renderDiagnosis(result) | renderUncertain(result)          (see needsUncertaintyExperience)
   *     -> error panel                                                (request failed; never a diagnosis)
   *
   * One request per scan, no automatic retry: "Try Again" is always a deliberate user action.
   * Cancelling (or leaving the image) marks this run stale, so a late response can never appear.
   *
   * @param {File} imageFile
   */
  async function requestAnalysis(imageFile) {
    abortAnalysis(); // defensive: retire any earlier run before starting this one
    const runId = state.analysis.runId;
    const controller = new AbortController();
    state.analysis.controller = controller;
    const isStale = () => runId !== state.analysis.runId || state.status !== "analyzing";

    let timedOut = false;
    let timer = null;
    let upload = null;
    try {
      upload = await prepareUpload(imageFile);
      state.lastScanTime = new Date();
      if (isStale()) return;

      // Offline queue + Gemini-on-reconnect: if we already know we're offline, don't bother
      // attempting the request at all — go straight to the same path a failed fetch would take.
      if (!navigator.onLine) {
        await handleOfflineScan(upload, isStale);
        return;
      }

      const form = new FormData();
      form.append("image", upload.blob, upload.name); // the ONLY thing sent: no location, no identity

      timer = setTimeout(() => { timedOut = true; controller.abort(); }, ANALYSIS_TIMEOUT_MS);
      let response;
      try {
        response = await fetch(ANALYZE_URL, {
          method: "POST",
          body: form,
          signal: controller.signal,
          credentials: "omit",
          cache: "no-store",
        });
      } catch (networkErr) {
        if (timedOut) throw new AnalysisFailure("ANALYSIS_TIMEOUT");
        throw new AnalysisFailure("NETWORK_ERROR");
      }

      let payload = null;
      try { payload = await response.json(); } catch (e) { payload = null; }
      if (isStale()) return;

      if (!response.ok) throw failureFromResponse(response.status, payload);

      const result = validateResult(payload);
      if (!result) throw new AnalysisFailure("ANALYSIS_FAILED");

      routeResult(result);
    } catch (err) {
      if (isStale()) return; // the person cancelled or moved on; show nothing
      // A NETWORK_ERROR here means the fetch itself never reached the server. Only treat this as
      // "offline" — and route to the local-model/queue path — when the browser is genuinely
      // offline. If we're online but the server itself is unreachable (down, DNS failure, etc.),
      // that's a real backend problem, not an offline scan, so it must show the honest error state
      // with a retry — silently queueing it would hide a live failure from the person.
      if (err instanceof AnalysisFailure && err.code === "NETWORK_ERROR" && upload && !navigator.onLine && typeof CropGuardOffline !== "undefined") {
        await handleOfflineScan(upload, isStale);
        return;
      }
      showAnalysisError(err instanceof AnalysisFailure ? err : new AnalysisFailure("ANALYSIS_FAILED"));
    } finally {
      if (timer !== null) clearTimeout(timer);
      if (state.analysis.runId === runId) state.analysis.controller = null;
    }
  }

  // ---------------------------------------------------------------------
  // Offline queue + Gemini-on-reconnect
  // ---------------------------------------------------------------------

  /** Best-effort GPS for the queued scan. Never blocks/fails the offline flow — resolves null on any problem. */
  function getBestEffortLocation(timeoutMs) {
    timeoutMs = timeoutMs || GEO_TIMEOUT_MS;
    if (!navigator.geolocation) return Promise.resolve(null);
    return new Promise((resolve) => {
      let settled = false;
      const finish = (v) => { if (!settled) { settled = true; resolve(v); } };
      const timer = setTimeout(() => finish(null), timeoutMs);
      try {
        navigator.geolocation.getCurrentPosition(
          (pos) => { clearTimeout(timer); finish({ latitude: pos.coords.latitude, longitude: pos.coords.longitude }); },
          () => { clearTimeout(timer); finish(null); },
          { enableHighAccuracy: false, timeout: timeoutMs, maximumAge: 5 * 60 * 1000 }
        );
      } catch (e) { clearTimeout(timer); finish(null); }
    });
  }

  /**
   * Best-effort on-device inference (Requirement 3). Returns null — meaning "show the honest
   * ANALYSIS_UNAVAILABLE state instead" — whenever: CropGuardOnnx isn't loaded, no model is
   * installed, inference itself throws, or the model's own confidence is below
   * ONNX_CONFIDENCE_THRESHOLD. Never throws.
   */
  async function tryOnDeviceInference(blob) {
    if (typeof CropGuardOnnx === "undefined") return null;
    try {
      const installed = await CropGuardOnnx.isModelInstalled();
      if (!installed) return null;
      const bitmap = await decodeImage(blob);
      try {
        const estimate = await CropGuardOnnx.classify(bitmap);
        if (!estimate || typeof estimate.confidencePercent !== "number") return null;
        if (estimate.confidencePercent < ONNX_CONFIDENCE_THRESHOLD * 100) return null;
        return estimate;
      } finally {
        if (bitmap && typeof bitmap.close === "function") bitmap.close();
      }
    } catch (e) {
      console.warn("FOLIX: on-device inference failed; falling back to the offline-unavailable state.", e);
      return null;
    }
  }

  /**
   * The mapping between CropGuardOnnx's/CropGuardOffline's result shape and the Gemini diagnosis
   * contract used by renderDiagnosis/renderUncertain (see Requirement 5 / the file header contract).
   * The two shapes don't reconcile perfectly — see the inline notes below for exactly where, rather
   * than papering over the gaps:
   *   - severity: the on-device model does classification only (no severity segmentation — see
   *     onnx-classifier.js), so there is no honest value to put here besides "unknown". A Gemini
   *     result always has a real severity; a provisional on-device result never does.
   *   - confidenceLevel: CropGuardOnnx uses "HIGH"/"MEDIUM"/"LOW" (note "MEDIUM", not "MODERATE"),
   *     mapped onto the contract's "high"/"moderate"/"low" below.
   *   - symptoms / recommended_actions: the on-device model has no equivalent output at all; both
   *     render as empty lists ("No symptoms listed." / "No recommendation provided yet.", which the
   *     result card already handles gracefully for a partial object).
   *   - plant_part: not classified on-device; always "unknown".
   * `_folixSyncStatus` is an additive marker (not part of the Gemini contract) that renderDiagnosis
   * reads to decide which existing banner styling to show; a real Gemini response never has it, so
   * this doesn't change how normal online/synced results render.
   */
  const ONNX_CONFIDENCE_LEVEL_MAP = { HIGH: "high", MEDIUM: "moderate", LOW: "low" };

  function mapOnnxEstimateToDiagnosis(estimate) {
    return {
      plant_detected: true,
      plant_part: "unknown",
      crop: estimate.cropName || "Crop",
      disease: estimate.diseaseName || "unknown",
      severity: "unknown", // see reconciliation note above — no honest value available on-device
      confidence: ONNX_CONFIDENCE_LEVEL_MAP[estimate.confidenceLevel] || "low",
      symptoms: [],
      reasoning: estimate.note || "Diagnosed on-device (real local ML inference) while offline. Not yet confirmed.",
      recommended_actions: [],
      needs_human_review: false, // distinct from "provisional" — this isn't Gemini flagging low confidence, it's simply unconfirmed
      _folixSyncStatus: "provisional",
    };
  }

  /** CropGuardOffline.offlineAnalysisUnavailable() -> the same contract shape, routed through renderUncertain. */
  function mapPendingToDiagnosis(estimate) {
    return {
      plant_detected: false,
      plant_part: "unknown",
      crop: "",
      disease: "unknown",
      severity: "unknown",
      confidence: "low",
      symptoms: [],
      reasoning: estimate.note,
      recommended_actions: [],
      needs_human_review: false,
      _folixSyncStatus: "pending",
    };
  }

  /**
   * Requirement 2/3: called once we know a scan can't reach Gemini right now (offline, or the
   * fetch itself failed to reach the network). Queues the photo via CropGuardOffline.queueScan
   * exactly as that function already supports, attempts a best-effort on-device estimate, and
   * renders the appropriate state through the SAME result-card components used for a normal
   * online result — never a new/different visual language.
   */
  async function handleOfflineScan(upload, isStale) {
    if (typeof CropGuardOffline === "undefined") {
      // Markup/script missing (stale cached HTML, or scanner-experience.js loaded standalone
      // without its offline companions) — fail back to the honest hard-error state rather than
      // silently losing the photo.
      showAnalysisError(new AnalysisFailure("NETWORK_ERROR"));
      return;
    }

    const [gps, onDeviceEstimate] = await Promise.all([
      getBestEffortLocation(),
      tryOnDeviceInference(upload.blob),
    ]);
    if (isStale()) return;

    let queued;
    try {
      queued = await CropGuardOffline.queueScan({
        blob: upload.blob,
        plantPart: "unknown",
        latitude: gps ? gps.latitude : null,
        longitude: gps ? gps.longitude : null,
        offlineEstimate: onDeviceEstimate || null,
      });
    } catch (queueErr) {
      if (isStale()) return;
      showAnalysisError(new AnalysisFailure(
        "NETWORK_ERROR",
        "You're offline and this photo couldn't be saved on this device either. Please try again.",
        true
      ));
      return;
    }
    if (isStale()) return;

    state.offline.queueId = queued.id;

    if (onDeviceEstimate) {
      renderDiagnosis(mapOnnxEstimateToDiagnosis(onDeviceEstimate));
    } else {
      renderUncertain(mapPendingToDiagnosis(CropGuardOffline.offlineAnalysisUnavailable()));
    }
  }

  /**
   * Requirement 4: on reconnect, re-submit every queued scan to the real /api/scan/analyze
   * endpoint (see offline-classifier.js). If the scan currently on screen is the one that just
   * got a confirmed result back, replace it via the normal routeResult path — same card, now a
   * normal confirmed result exactly like any online scan. Scans that fail to sync (still offline,
   * server error) are simply left queued; nothing on screen changes for them.
   */
  async function syncOfflineQueue() {
    if (typeof CropGuardOffline === "undefined" || !navigator.onLine) return;
    try {
      await CropGuardOffline.trySyncQueue(API_BASE_URL, (scan, result, err) => {
        if (!result) return; // sync failed for this scan (offline again, server error): leave it queued
        const confirmed = validateResult(result);
        if (!confirmed) {
          console.warn("FOLIX: a synced offline scan returned an unexpected response shape; leaving its panel as-is.");
          return;
        }
        if (state.offline.queueId === scan.id && (state.status === "result" || state.status === "uncertain")) {
          state.offline.queueId = null;
          routeResult(confirmed);
        }
      });
    } catch (e) {
      console.warn("FOLIX: offline queue sync failed; it will retry the next time connectivity returns.", e);
    }
  }

  /** Calm error state. Deliberately shows no crop/disease/severity fields. */
  function showAnalysisError(failure) {
    if (!el.panels.error) { // markup missing (stale cached HTML): fail back to the preview rather than break
      setStatus("preview");
      showFileError(failure.message);
      return;
    }
    if (el.errorMessage) el.errorMessage.textContent = failure.message;
    if (el.errorRetryBtn) {
      // `.btn` sets display:inline-flex, which overrides the [hidden] attribute — so hide explicitly as well.
      el.errorRetryBtn.hidden = !failure.retryable;
      el.errorRetryBtn.style.display = failure.retryable ? "" : "none";
    }
    setStatus("error");
    announce("FOLIX couldn't complete the analysis. " + failure.message);
    if (el.errorTitle) el.errorTitle.focus();
  }

  // ---------------------------------------------------------------------
  // Result rendering — the future integration contract
  // ---------------------------------------------------------------------

  function escapeHtml(str) {
    const div = document.createElement("div");
    div.textContent = str == null ? "" : String(str);
    return div.innerHTML;
  }

  function titleCase(str) {
    const s = String(str || "");
    return s.charAt(0).toUpperCase() + s.slice(1);
  }

  function severityClass(sev) {
    return "sev-" + (["mild", "moderate", "severe"].includes(sev) ? sev : "unknown");
  }

  function confidenceClass(conf) {
    return ["high", "moderate", "low"].includes(conf) ? "conf-" + conf : "conf-unspecified";
  }

  /** A finite 0-100 number, or null. Anything else (missing, string, NaN) is "not available", never 0. */
  function asPercent(v) {
    return typeof v === "number" && Number.isFinite(v) ? Math.max(0, Math.min(100, Math.round(v))) : null;
  }

  /** Meter tone: how alarming the value is. Purely presentational. */
  function meterTone(pct) {
    return pct >= 60 ? "high" : pct >= 30 ? "mid" : "low";
  }

  function meterHtml(label, pct, hint) {
    const p = asPercent(pct);
    if (p === null) {
      return `<div class="scn-meter scn-meter-na">
          <div class="scn-meter-head"><span class="scn-meter-label">${escapeHtml(label)}</span><span class="scn-meter-value">Not available</span></div>
        </div>`;
    }
    return `<div class="scn-meter scn-meter-${meterTone(p)}">
        <div class="scn-meter-head"><span class="scn-meter-label">${escapeHtml(label)}</span><span class="scn-meter-value">${p}%</span></div>
        <div class="scn-meter-track" role="progressbar" aria-label="${escapeHtml(label)}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${p}"><span class="scn-meter-fill" style="width:${p}%"></span></div>
        ${hint ? `<p class="scn-meter-hint">${escapeHtml(hint)}</p>` : ""}
      </div>`;
  }

  function confidenceLabel(conf) {
    return ["high", "moderate", "low"].includes(conf) ? titleCase(conf) : "Not provided";
  }

  /**
   * `diagnosis.remedy_plan` (see toContractMap on the backend) uses the same snake_case keys as the rest
   * of the live scan contract. `FolixReportRequest`'s `remedyPlan` deserializes straight into
   * `DiseaseInfo.RemedyPlan`/`OrganicRemedy`/`ChemicalRemedy`, which have no snake_case mapping of their
   * own, so the nested keys have to be renamed to camelCase here too — copied as-is otherwise; nothing is
   * invented, dropped, or altered. Returns null unchanged (no remedy data for this diagnosis).
   */
  function remedyPlanToReportShape(remedyPlan) {
    if (!remedyPlan || typeof remedyPlan !== "object") return null;
    const organic = Array.isArray(remedyPlan.organic_remedies) ? remedyPlan.organic_remedies : [];
    const chemical = Array.isArray(remedyPlan.chemical_remedies) ? remedyPlan.chemical_remedies : [];
    return {
      organicRemedies: organic.map((r) => ({
        name: r.name,
        instructions: r.instructions,
        applicationFrequency: r.application_frequency,
      })),
      chemicalRemedies: chemical.map((r) => ({
        name: r.name,
        instructions: r.instructions,
        activeIngredient: r.active_ingredient,
        dosage: r.dosage,
        preHarvestIntervalDays: r.pre_harvest_interval_days,
        safetyPrecautions: r.safety_precautions,
      })),
    };
  }

  /**
   * Stage 5B: maps the diagnosis contract object exactly as displayed
   * on screen (see the file header for the shape) onto the existing
   * `FolixReportRequest` contract expected by `POST /api/scan/reports`.
   * Field names change (snake_case -> camelCase) to match that record;
   * values are copied as-is — nothing is invented, dropped, or altered.
   * `plant_detected` is intentionally omitted: `FolixReportRequest`
   * mirrors `DiagnosisResult` minus that field.
   */
  function toReportRequestBody(diagnosis) {
    let lat = null;
    let lon = null;
    try {
      const storedLat = localStorage.getItem("folix_latitude");
      const storedLon = localStorage.getItem("folix_longitude");
      if (storedLat && storedLon && !isNaN(parseFloat(storedLat)) && !isNaN(parseFloat(storedLon))) {
        lat = parseFloat(storedLat);
        lon = parseFloat(storedLon);
      }
    } catch (_) {}
    if ((lat == null || lon == null) && window.FolixLocation && window.FolixLocation.currentPosition) {
      lat = window.FolixLocation.currentPosition.latitude;
      lon = window.FolixLocation.currentPosition.longitude;
    }

    return {
      crop: diagnosis.crop,
      plantPart: diagnosis.plant_part,
      disease: diagnosis.disease,
      severity: diagnosis.severity,
      confidence: diagnosis.confidence,
      symptoms: Array.isArray(diagnosis.symptoms) ? diagnosis.symptoms : [],
      reasoning: diagnosis.reasoning,
      recommendedActions: Array.isArray(diagnosis.recommended_actions) ? diagnosis.recommended_actions : [],
      needsHumanReview: !!diagnosis.needs_human_review,
      remedyPlan: remedyPlanToReportShape(diagnosis.remedy_plan),
      // Relayed as shown. Previously omitted, so every saved report lost its epidemic flag.
      epidemicRisk: diagnosis.epidemic_risk === true,
      severityPercent: asPercent(diagnosis.severity_percent),
      spreadChancePercent: asPercent(diagnosis.spread_chance_percent),
    };
  }

  /** Extracts a user-safe message from the FOLIX error envelope `{"error":{code,message,retryable}}`. */
  function reportErrorMessage(payload, fallback) {
    const err = payload && typeof payload === "object" && payload.error && typeof payload.error === "object" ? payload.error : null;
    return err && typeof err.message === "string" && err.message.length > 0 && err.message.length <= 300 ? err.message : fallback;
  }

  /**
   * Saves the diagnosis currently on screen via `POST /api/scan/reports`.
   * Never calls Gemini and never touches `diagnosis` — it is sent exactly
   * as displayed. One request per click; the button is disabled while a
   * save is in flight so a double-click can't send it twice.
   */
  // Saved report ids, keyed by the diagnosis object shown on screen (never mutated, never sent).
  // Lets Forward Case reuse an already-saved report instead of saving a duplicate.
  const savedReportIds = new WeakMap();

  /** Resolves to the saved report's id, or null if saving failed (the error is already shown in `note`). */
  async function saveReport(diagnosis, btn, note) {
    if (!diagnosis || !btn) return null;
    if (savedReportIds.has(diagnosis)) return savedReportIds.get(diagnosis);
    if (btn.disabled) return null;

    btn.disabled = true;
    btn.textContent = "Saving…";
    btn.classList.remove("scn-action-saved");
    if (note) {
      note.hidden = true;
      note.textContent = "";
      note.classList.remove("scn-note-error", "scn-note-success");
    }

    try {
      const storedLat = localStorage.getItem("folix_latitude");
      const storedLon = localStorage.getItem("folix_longitude");
      if (storedLat && storedLon && !isNaN(parseFloat(storedLat)) && !isNaN(parseFloat(storedLon))) {
        await fetch(API_BASE_URL + "/api/farmer/location", {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          credentials: "include",
          body: JSON.stringify({ consent: true, latitude: parseFloat(storedLat), longitude: parseFloat(storedLon) }),
        }).catch(() => {});
      }
    } catch (_) {}

    try {
      const response = await fetch(SAVE_REPORT_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(toReportRequestBody(diagnosis)),
        // Stage 6A: saving needs a logged-in farmer, so the HttpOnly session cookie must go along.
        // (Analysis, above, still sends no credentials: scanning works without an account.)
        credentials: "include",
        cache: "no-store",
      });

      let payload = null;
      try { payload = await response.json(); } catch (e) { payload = null; }

      if (response.status === 401) {
        const authErr = new Error("Log in to save reports. Your diagnosis stays on this page — once you're logged in, press Try Again.");
        authErr.authRequired = true;
        throw authErr;
      }
      if (!response.ok) {
        throw new Error(reportErrorMessage(payload, "FOLIX couldn't save this report. Please try again."));
      }

      const savedId = payload && typeof payload.id === "string" ? payload.id : null;
      if (savedId) {
        savedReportIds.set(diagnosis, savedId);
        const scanIdEl = qs("scnReportScanIdBadge");
        if (scanIdEl) {
          scanIdEl.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10 10-4.5 10-10S17.5 2 12 2z"/></svg> <span>Scan ID: ${escapeHtml(savedId)}</span>`;
          scanIdEl.hidden = false;
        }
      }
      btn.textContent = "Saved ✓";
      btn.classList.add("scn-action-saved");
      // Left disabled: this exact diagnosis is now saved: nothing further to send.
      if (note) {
        note.hidden = false;
        note.textContent = "Report saved.";
        note.classList.add("scn-note-success");
      }
      announce("Report saved.");
      return savedId;
    } catch (err) {
      btn.disabled = false;
      btn.textContent = "Try Again";
      if (note) {
        note.hidden = false;
        note.textContent = (err && err.message) || "FOLIX couldn't save this report. Please try again.";
        note.classList.add("scn-note-error");
        if (err && err.authRequired) {
          // Open login in a NEW tab so this diagnosis (and the Gemini call that produced it) is not lost.
          const link = document.createElement("a");
          link.href = "login.html?next=close";
          link.target = "_blank";
          link.rel = "noopener";
          link.textContent = " Log in";
          note.appendChild(link);
        }
      }
      announce("Saving the report failed.");
      return null;
    }
  }

  // ---------------------------------------------------------------------
  // Forward Case
  // ---------------------------------------------------------------------

  function setNote(note, text, kind) {
    if (!note) return;
    note.hidden = false;
    note.classList.remove("scn-note-error", "scn-note-success");
    if (kind) note.classList.add(kind === "error" ? "scn-note-error" : "scn-note-success");
    note.textContent = text;
  }

  function contactLabel(c) {
    return c.organization ? `${c.name} — ${c.organization}` : c.name;
  }

  /** JSON request with the session cookie; throws Error with `.status`/`.code`/`.authRequired` set. */
  async function forwardRequest(url, options) {
    let response;
    try {
      response = await fetch(url, Object.assign({ credentials: "include", cache: "no-store" }, options));
    } catch (e) {
      throw new Error("Could not reach the FOLIX server. Check your connection and try again.");
    }
    let payload = null;
    try { payload = await response.json(); } catch (e) { payload = null; }
    if (response.status === 401) {
      const authErr = new Error("Log in to forward a case.");
      authErr.authRequired = true;
      throw authErr;
    }
    if (!response.ok) {
      const err = payload && typeof payload === "object" && payload.error && typeof payload.error === "object" ? payload.error : null;
      const failure = new Error(reportErrorMessage(payload, "FOLIX couldn't forward this case. Please try again."));
      failure.code = err && typeof err.code === "string" ? err.code : "";
      throw failure;
    }
    return payload;
  }

  function appendLoginLink(note) {
    const link = document.createElement("a");
    link.href = "login.html?next=close";
    link.target = "_blank";
    link.rel = "noopener";
    link.textContent = " Log in";
    note.appendChild(link);
  }

  /**
   * Forward Case. Directly saves the report on demand, fetches the contact directory,
   * and immediately forwards to the primary Agricultural Network support contact without
   * showing an unnecessary dropdown menu.
   */
  async function forwardCase(diagnosis, row, forwardBtn, saveBtn, note) {
    if (forwardBtn.disabled) return;

    forwardBtn.disabled = true;
    const originalLabel = forwardBtn.textContent;
    forwardBtn.textContent = "Forwarding…";
    setNote(note, "Saving and directly forwarding this case to Agricultural Network…");

    let reportId;
    try {
      reportId = await saveReport(diagnosis, saveBtn, note);
    } catch (err) {
      forwardBtn.disabled = false;
      forwardBtn.textContent = originalLabel;
      return;
    }
    if (!reportId) {
      forwardBtn.disabled = false;
      forwardBtn.textContent = originalLabel;
      if (note && !note.classList.contains("scn-note-error")) {
        setNote(note, "This case couldn't be saved, so it can't be forwarded yet.", "error");
      }
      return;
    }

    let contacts = [];
    try {
      const list = await forwardRequest(DIRECTORY_URL, { method: "GET" });
      contacts = Array.isArray(list) ? list : [];
    } catch (err) {
      forwardBtn.disabled = false;
      forwardBtn.textContent = originalLabel;
      setNote(note, err.message, "error");
      if (err.authRequired) appendLoginLink(note);
      return;
    }

    if (contacts.length === 0) {
      forwardBtn.disabled = false;
      forwardBtn.textContent = originalLabel;
      setNote(note, "Report saved, but no support contacts are available in the directory.", "error");
      return;
    }

    // Direct forward: pick the primary contact directly without requiring a dropdown
    const contact = contacts[0];
    try {
      const result = await forwardRequest(FORWARD_URL(reportId), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ supportContactId: Number(contact.id) }),
      });
      const name = result && result.supportContact && result.supportContact.name
        ? result.supportContact.name
        : (contact.name || "support contact");
      forwardBtn.textContent = "Forwarded ✓";
      forwardBtn.disabled = true;
      forwardBtn.classList.add("scn-action-saved");
      note.hidden = false;
      note.classList.remove("scn-note-error");
      note.classList.add("scn-note-success");
      note.textContent = `Case directly forwarded to ${name}. Authorities can see it on the Authority dashboard.`;
      announce("Case forwarded.");
    } catch (err) {
      if (err.code === "ALREADY_FORWARDED") {
        forwardBtn.textContent = "Forwarded ✓";
        forwardBtn.disabled = true;
        forwardBtn.classList.add("scn-action-saved");
        note.hidden = false;
        note.classList.remove("scn-note-error");
        note.classList.add("scn-note-success");
        note.textContent = "This case was already forwarded earlier. Authorities can see it on the Authority dashboard.";
        return;
      }
      forwardBtn.disabled = false;
      forwardBtn.textContent = originalLabel;
      setNote(note, err.message || "Failed to forward case.", "error");
      if (err.authRequired) appendLoginLink(note);
    }
  }

  // ---- Alert Authority escalation URL ----
  const ESCALATE_URL = (reportId) => API_BASE_URL + "/api/farmer/alerts/escalate-to-authority/" + encodeURIComponent(reportId);

  /**
   * Escalates a saved report to the local agricultural authority when the farmer believes
   * the AI underestimated the disease spread risk. Requires a saved report (saves on demand).
   */
  async function escalateToAuthority(diagnosis, row, escalateBtn, saveBtn, note) {
    if (escalateBtn.disabled) return;

    escalateBtn.disabled = true;
    const originalLabel = escalateBtn.textContent;
    escalateBtn.textContent = "Preparing…";
    setNote(note, "Saving report so it can be escalated…");

    let reportId;
    try {
      reportId = await saveReport(diagnosis, saveBtn, note);
    } finally {
      if (!reportId) {
        escalateBtn.disabled = false;
        escalateBtn.textContent = originalLabel;
      }
    }
    if (!reportId) {
      if (note && !note.classList.contains("scn-note-error")) setNote(note, "This case couldn't be saved, so it can't be escalated yet.", "error");
      return;
    }

    // Ask for optional farmer note
    const farmerNote = window.prompt(
      "Tell the authority why you think the spread risk is higher than the AI estimates.\n\n(Optional — press OK with an empty message to send without a note.)"
    );
    if (farmerNote === null) {
      // Cancelled
      escalateBtn.disabled = false;
      escalateBtn.textContent = originalLabel;
      setNote(note, "Escalation cancelled — your report is still saved.");
      return;
    }

    escalateBtn.textContent = "Alerting authority…";
    let escLat = null;
    let escLon = null;
    try {
      const storedLat = localStorage.getItem("folix_latitude");
      const storedLon = localStorage.getItem("folix_longitude");
      if (storedLat && storedLon && !isNaN(parseFloat(storedLat)) && !isNaN(parseFloat(storedLon))) {
        escLat = parseFloat(storedLat);
        escLon = parseFloat(storedLon);
      }
    } catch (_) {}
    if ((escLat == null || escLon == null) && window.FolixLocation && window.FolixLocation.currentPosition) {
      escLat = window.FolixLocation.currentPosition.latitude;
      escLon = window.FolixLocation.currentPosition.longitude;
    }
    try {
      const result = await forwardRequest(ESCALATE_URL(reportId), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ note: farmerNote || null, latitude: escLat, longitude: escLon }),
      });

      escalateBtn.textContent = "Authority Alerted ✓";
      escalateBtn.classList.remove("btn-warning");
      escalateBtn.classList.add("scn-action-saved");
      // Keep disabled — already escalated
      if (note) {
        note.hidden = false;
        note.classList.remove("scn-note-error");
        note.classList.add("scn-note-success");
        note.textContent = result && result.message
          ? result.message
          : "Alert sent to the local agricultural authority. They can now broadcast it to nearby farmers.";
      }
      announce("Authority alerted about spread risk.");
    } catch (err) {
      escalateBtn.disabled = false;
      escalateBtn.textContent = originalLabel;
      setNote(note, (err && err.message) || "Could not reach the authority. Please try again.", "error");
      if (err && err.authRequired) appendLoginLink(note);
    }
  }

  function formatReportDate(d) {
    if (!d) return "Date unavailable";
    try {
      const date = d instanceof Date ? d : new Date(d);
      if (isNaN(date.getTime())) return "Date unavailable";
      const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
      const month = months[date.getMonth()];
      const day = date.getDate();
      const year = date.getFullYear();
      let hours = date.getHours();
      const minutes = String(date.getMinutes()).padStart(2, "0");
      const ampm = hours >= 12 ? "PM" : "AM";
      hours = hours % 12;
      hours = hours ? hours : 12;
      return `${month} ${day}, ${year} · ${hours}:${minutes} ${ampm}`;
    } catch (_) {
      return "Date unavailable";
    }
  }

  function actionIconSvg(actionText) {
    const t = String(actionText || "").toLowerCase();
    if (/(inspect|monitor|check|scout|look|eye|observe|examin)/.test(t)) {
      return `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>`;
    }
    if (/(air|circulat|spac|prun|ventil|canop|foliage|dens)/.test(t)) {
      return `<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M17 8C8 10 5.9 16.17 3.82 21.34l1.89.66.95-2.3c.48.17.98.3 1.34.3C19 20 22 3 22 3c-1 2-8 2.25-13 3.25S2 11.5 2 13.5s1.75 3.75 1.75 3.75C7 8 17 8 17 8z"/></svg>`;
    }
    if (/(remov|dispos|destroy|burn|trash|clear|cut)/.test(t)) {
      return `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>`;
    }
    if (/(consult|agronomist|officer|expert|extension|authorit|professional)/.test(t)) {
      return `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>`;
    }
    return `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`;
  }

  function renderGauge(title, pct, desc, toneType) {
    const p = asPercent(pct);
    if (p === null) {
      return `
        <div class="scn-gauge-card scn-gauge-na">
          <div class="scn-gauge-circle">
            <span class="scn-gauge-na-badge">N/A</span>
          </div>
          <div class="scn-gauge-meta">
            <span class="scn-gauge-title">${escapeHtml(title)}</span>
            <span class="scn-gauge-na-label">Not available</span>
            <p class="scn-gauge-desc">${escapeHtml(desc)}</p>
          </div>
        </div>`;
    }
    const tone = toneType === "spread"
      ? (p >= 60 ? "tone-high" : p >= 30 ? "tone-mid" : "tone-low")
      : (p >= 60 ? "tone-high" : p >= 30 ? "tone-mid" : "tone-low");
    const circ = 113.1;
    const offset = Math.max(0, circ - (circ * p / 100));
    return `
      <div class="scn-gauge-card">
        <div class="scn-gauge-circle">
          <svg viewBox="0 0 44 44" class="scn-gauge-svg">
            <circle class="scn-gauge-bg" cx="22" cy="22" r="18" fill="none" stroke="#E6ECE2" stroke-width="4"></circle>
            <circle class="scn-gauge-bar ${tone}" cx="22" cy="22" r="18" fill="none" stroke-width="4"
                    stroke-dasharray="113.1" stroke-dashoffset="${offset.toFixed(1)}"
                    stroke-linecap="round" transform="rotate(-90 22 22)"></circle>
          </svg>
          <span class="scn-gauge-val ${tone}">${p}%</span>
        </div>
        <div class="scn-gauge-meta">
          <span class="scn-gauge-title">${escapeHtml(title)}</span>
          <div class="scn-gauge-track" role="progressbar" aria-label="${escapeHtml(title)}" aria-valuenow="${p}" aria-valuemin="0" aria-valuemax="100">
            <div class="scn-gauge-fill ${tone}" style="width: ${p}%"></div>
          </div>
          <p class="scn-gauge-desc">${escapeHtml(desc)}</p>
        </div>
      </div>`;
  }

  function renderOrganicRemediesHtml(remedyPlan) {
    const organic = (remedyPlan && (remedyPlan.organic_remedies || remedyPlan.organicRemedies || remedyPlan.organic)) || [];
    if (!Array.isArray(organic) || organic.length === 0) {
      return `<p class="scn-empty-remedy">No specific organic remedies listed — consult a local agronomist.</p>`;
    }
    return organic.map((r) => `
      <div class="scn-remedy-card">
        <div class="scn-remedy-top">
          <div class="scn-remedy-icon">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M17 8C8 10 5.9 16.17 3.82 21.34l1.89.66.95-2.3c.48.17.98.3 1.34.3C19 20 22 3 22 3c-1 2-8 2.25-13 3.25S2 11.5 2 13.5s1.75 3.75 1.75 3.75C7 8 17 8 17 8z"/></svg>
          </div>
          <div class="scn-remedy-main">
            <h4 class="scn-remedy-name">${escapeHtml(r.name || "")}</h4>
            <p class="scn-remedy-desc">${escapeHtml(r.instructions || r.description || "")}</p>
          </div>
          ${(r.application_frequency || r.applicationFrequency || r.frequency) ? `
            <div class="scn-remedy-freq">
              <span class="scn-freq-label">Frequency</span>
              <span class="scn-freq-val">${escapeHtml(r.application_frequency || r.applicationFrequency || r.frequency)}</span>
            </div>
          ` : ""}
        </div>
      </div>
    `).join("");
  }

  function renderChemicalRemediesHtml(remedyPlan) {
    const chemical = (remedyPlan && (remedyPlan.chemical_remedies || remedyPlan.chemicalRemedies || remedyPlan.chemical)) || [];
    if (!Array.isArray(chemical) || chemical.length === 0) {
      return `<p class="scn-empty-remedy">No specific chemical remedies listed — consult a local agronomist.</p>`;
    }
    return chemical.map((c) => {
      const active = c.active_ingredient || c.activeIngredient;
      const dosage = c.dosage;
      const phi = c.pre_harvest_interval_days || c.preHarvestIntervalDays || c.pre_harvest_interval;
      const phiText = phi ? `${escapeHtml(phi)} ${isNaN(Number(phi)) ? "" : (Number(phi) === 1 ? "day" : "days")}` : "";
      const safety = c.safety_precautions || c.safetyPrecautions || c.safety;
      const desc = c.instructions || c.description;

      return `
        <div class="scn-remedy-card scn-chemical-card">
          <div class="scn-remedy-top">
            <div class="scn-remedy-icon icon-chemical">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M10 2v7.31M14 2v7.31M8.5 2h7M14 9.3l5.5 9.7A2 2 0 0 1 17.8 22H6.2a2 2 0 0 1-1.7-3L10 9.3"/></svg>
            </div>
            <div class="scn-remedy-main">
              <h4 class="scn-remedy-name">${escapeHtml(c.name || "")}</h4>
              ${desc ? `<p class="scn-remedy-desc">${escapeHtml(desc)}</p>` : ""}
            </div>
          </div>
          <table class="scn-chem-table">
            <tbody>
              ${active ? `<tr><th>Active Ingredient</th><td>${escapeHtml(active)}</td></tr>` : ""}
              ${dosage ? `<tr><th>Dosage</th><td>${escapeHtml(dosage)}</td></tr>` : ""}
              ${phi ? `<tr><th>Pre-harvest interval</th><td>${phiText}</td></tr>` : ""}
              ${safety ? `<tr><th>Safety</th><td>${escapeHtml(safety)}</td></tr>` : ""}
            </tbody>
          </table>
        </div>
      `;
    }).join("");
  }

  /**
   * @param {HTMLElement} container — el.resultContent or el.uncertainContent
   * @param {object} diagnosis — the exact object passed to renderDiagnosis/renderUncertain;
   *   "Save Report" sends this, unmodified, and nothing else.
   */
  function renderActionsRow(container, diagnosis) {
    // Determine if the "Alert Authority" button should appear:
    const hasDisease = diagnosis && diagnosis.disease &&
      !/^healthy$/i.test(String(diagnosis.disease).trim()) &&
      String(diagnosis.disease).trim().toLowerCase() !== "unknown";
    const spreadLow = hasDisease && (
      diagnosis.spread_chance_percent == null ||
      Number(diagnosis.spread_chance_percent) <= 40
    );
    const notAlreadyEpidemic = diagnosis && diagnosis.epidemic_risk !== true;
    const showAlertAuthority = spreadLow && notAlreadyEpidemic;

    const row = document.createElement("div");
    row.className = "scn-actions-row";
    row.innerHTML = `
      <button type="button" class="btn scn-action-btn scn-save-btn" data-scn-action="save">
        <svg class="scn-btn-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>
        Save Report
      </button>
      <div class="scn-secondary-actions-row">
        <button type="button" class="btn btn-secondary scn-action-btn" data-scn-action="forward">
          <svg class="scn-btn-icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
          Forward Case
        </button>
        <button type="button" class="btn btn-secondary scn-action-btn" data-scn-action="again">
          <svg class="scn-btn-icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 4v6h-6M1 20v-6h6"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
          Scan Another
        </button>
      </div>
      ${showAlertAuthority
        ? `<button type="button" class="btn btn-warning scn-action-btn scn-alert-authority-btn" data-scn-action="alert-authority" title="Disagree with the AI's spread estimate? Alert your local agricultural authority so they can dispatch warnings to nearby farmers.">⚠️ Alert Authority</button>`
        : ""}
      <p class="scn-inline-note" data-scn-action-note hidden></p>
    `;
    container.appendChild(row);

    // If the "Alert Authority" button is shown, add an explanatory callout above it
    if (showAlertAuthority) {
      const hint = document.createElement("div");
      hint.className = "scn-authority-hint";
      hint.innerHTML = `<p><strong>Think this spreads more than the AI says?</strong> Tap "⚠️ Alert Authority" to notify your local agricultural office. They can verify and broadcast a warning to nearby farmers.</p>`;
      container.insertBefore(hint, row);
    }

    row.querySelectorAll("[data-scn-action]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const action = btn.dataset.scnAction;
        if (action === "again") {
          removeImage();
          return;
        }
        const note = row.querySelector("[data-scn-action-note]");
        if (action === "save") {
          saveReport(diagnosis, btn, note);
          return;
        }
        if (action === "forward") {
          forwardCase(diagnosis, row, btn, row.querySelector('[data-scn-action="save"]'), note);
        }
        if (action === "alert-authority") {
          escalateToAuthority(diagnosis, row, btn, row.querySelector('[data-scn-action="save"]'), note);
        }
      });
    });
  }

  /**
   * Renders the full diagnosis result. Safe to call with a partial object;
   * missing fields degrade gracefully rather than inventing content.
   * @param {object} result — see the structured contract in the file header.
   */
  function renderDiagnosis(result) {
    result = result || {};
    const symptoms = Array.isArray(result.symptoms) ? result.symptoms : [];
    const actions = Array.isArray(result.recommended_actions) ? result.recommended_actions : [];
    const needsReview = !!result.needs_human_review;

    const isProvisional = result._folixSyncStatus === "provisional";
    const provisionalBannerHtml = isProvisional
      ? `<div class="scn-review-banner needs-review">
           <p><strong>Provisional result — not yet confirmed.</strong> This is an on-device estimate made while offline. FOLIX will replace it with a confirmed diagnosis automatically once you're back online.</p>
         </div>`
      : "";

    const epidemicRiskBannerHtml = result.epidemic_risk === true
      ? `<div class="scn-danger-banner">
           <p><strong>⚠️ Epidemic-risk disease.</strong> This disease spreads fast — consider warning nearby farmers.</p>
         </div>`
      : "";

    const isLowConfidence = result.confidence === "low" && !isProvisional;
    const lowConfidenceBannerHtml = isLowConfidence
      ? `<div class="scn-review-banner needs-review scn-lowconf-banner">
           <p><strong>Low confidence — treat this as a possible match.</strong> FOLIX thinks this is the most likely condition, but the photo alone isn't conclusive. The severity and spread figures below are estimates from what is visible; a closer, well-lit photo or an agronomist's check will firm them up.</p>
         </div>`
      : "";
    const hasNamedDisease = result.disease && String(result.disease).trim().toLowerCase() !== "unknown";

    const savedId = (result && (result.id || result.scan_id || result.scanId)) || (savedReportIds.has(result) ? savedReportIds.get(result) : null);
    const scanIdBadgeHtml = savedId
      ? `<div class="scn-header-badge" id="scnReportScanIdBadge">
           <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10 10-4.5 10-10S17.5 2 12 2z"/></svg>
           <span>Scan ID: ${escapeHtml(savedId)}</span>
         </div>`
      : `<div class="scn-header-badge" id="scnReportScanIdBadge" hidden></div>`;


    const photoUrl = state.objectUrl || (result && (result.imageUrl || result.image_url));
    const hasPhoto = !!photoUrl;
    const photoStyle = hasPhoto
      ? `background-image: url('${photoUrl}');`
      : `background-color: #EBF0E6; display: flex; align-items: center; justify-content: center;`;

    const photoInner = hasPhoto
      ? `<button type="button" class="scn-view-original-btn" id="scnViewOriginalBtn" data-scn-view-original aria-label="View original full resolution image">
           <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7"/></svg>
           View original
         </button>`
      : `<svg viewBox="0 0 32 32" width="48" height="48" style="color: #6C8A73; opacity: 0.5;"><path d="M16 2C9 8 6 14 6 19a10 10 0 0 0 20 0c0-5-3-11-10-17Z" fill="currentColor"/></svg>`;

    const actionsHtml = actions.length
      ? actions.map((a) => `
          <div class="scn-action-card">
            <div class="scn-action-icon-wrap">
              ${actionIconSvg(a)}
            </div>
            <p class="scn-action-text">${escapeHtml(a)}</p>
          </div>
        `).join("")
      : `<p class="scn-empty-msg">No recommendation provided yet.</p>`;

    const html = `
      <div class="scn-report-header">
        <div class="scn-report-header-left">
          <a href="#scan" class="scn-back-link" id="scnBackToScanBtn">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
            Back to scan
          </a>
          <div class="scn-report-title-row">
            <span class="scn-report-leaf-icon" aria-hidden="true">
              <svg viewBox="0 0 32 32" width="22" height="22"><path d="M16 2C9 8 6 14 6 19a10 10 0 0 0 20 0c0-5-3-11-10-17Z" fill="currentColor"/></svg>
            </span>
            <h1 class="scn-report-title">Disease Report</h1>
          </div>
          <p class="scn-report-subtitle">Detailed analysis of your crop health and recommended actions.</p>
        </div>
        <div class="scn-report-header-right">
          <div class="scn-report-header-leaves" aria-hidden="true">
            <svg viewBox="0 0 200 120" fill="none" stroke="#2D6A4F" stroke-width="1.2">
              <path d="M120 100 C140 70, 170 50, 190 20" />
              <path d="M150 75 C160 65, 175 60, 185 65 C185 75, 170 85, 150 75 Z" fill="#2D6A4F" fill-opacity="0.12" />
              <path d="M170 45 C180 35, 195 30, 205 35 C205 45, 190 55, 170 45 Z" fill="#2D6A4F" fill-opacity="0.12" />
              <path d="M135 88 C125 80, 115 82, 110 90 C115 98, 125 96, 135 88 Z" fill="#2D6A4F" fill-opacity="0.12" />
            </svg>
          </div>
          <div class="scn-header-badges">
            ${scanIdBadgeHtml}
          </div>
        </div>
      </div>

      ${provisionalBannerHtml}
      ${lowConfidenceBannerHtml}
      ${epidemicRiskBannerHtml}

      <div class="scn-report-grid">
        <!-- Left Column -->
        <aside class="scn-report-col-left">
          <div class="scn-card scn-photo-card">
            <div class="scn-crop-photo" style="${photoStyle}" role="img" aria-label="Selected crop photo">
              ${photoInner}
            </div>
          </div>

          <div class="scn-card scn-summary-card">
            <div class="scn-card-header">
              <span class="scn-sparkle-icon">✦</span>
              <h3 class="scn-card-title">Quick Summary</h3>
            </div>
            <div class="scn-summary-list">
              <div class="scn-summary-item">
                <div class="scn-summary-icon icon-sage">
                  <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M17 8C8 10 5.9 16.17 3.82 21.34l1.89.66.95-2.3c.48.17.98.3 1.34.3C19 20 22 3 22 3c-1 2-8 2.25-13 3.25S2 11.5 2 13.5s1.75 3.75 1.75 3.75C7 8 17 8 17 8z"/></svg>
                </div>
                <div class="scn-summary-meta">
                  <span class="scn-summary-value">${escapeHtml(result.crop || "Unknown")}</span>
                  <span class="scn-summary-label">Crop</span>
                </div>
              </div>

              <div class="scn-summary-item">
                <div class="scn-summary-icon icon-neutral">
                  <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="6"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/></svg>
                </div>
                <div class="scn-summary-meta">
                  <span class="scn-summary-value">${escapeHtml(result.disease || "Unknown")}</span>
                  <span class="scn-summary-label">Disease</span>
                </div>
              </div>

              <div class="scn-summary-item">
                <div class="scn-summary-icon icon-sev-${result.severity || 'unknown'}">
                  <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
                </div>
                <div class="scn-summary-meta">
                  <span class="scn-qs-badge sev-${result.severity}">${escapeHtml(titleCase(result.severity || "unknown"))}</span>
                  <span class="scn-summary-label">Severity</span>
                </div>
              </div>

              <div class="scn-summary-item">
                <div class="scn-summary-icon icon-conf-${result.confidence || 'unknown'}">
                  <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/></svg>
                </div>
                <div class="scn-summary-meta">
                  <span class="scn-qs-badge conf-${result.confidence}">${escapeHtml(confidenceLabel(result.confidence))}</span>
                  <span class="scn-summary-label">Confidence</span>
                </div>
              </div>
            </div>
          </div>
        </aside>

        <!-- Right Column -->
        <div class="scn-report-col-right">
          <!-- Disease Overview Card -->
          <div class="scn-card scn-overview-card">
            <div class="scn-overview-main">
              <span class="scn-crop-pill">${escapeHtml((result.crop || "Crop").toUpperCase())}</span>
              <h2 class="scn-disease-title">${escapeHtml(hasNamedDisease ? (isLowConfidence ? "Possibly: " + result.disease : result.disease) : "Condition pending")}</h2>
              <div class="scn-badges-row">
                <span class="scn-pill ${severityClass(result.severity)}">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
                  <span class="scn-pill-label">Severity:</span> ${escapeHtml(titleCase(result.severity || "unknown"))}
                </span>
                <span class="scn-pill ${confidenceClass(result.confidence)}">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
                  <span class="scn-pill-label">Confidence:</span> ${escapeHtml(confidenceLabel(result.confidence))}
                </span>
              </div>
            </div>

            <div class="scn-overview-gauges">
              ${renderGauge("Severity (visible damage)", result.severity_percent, "Share of the visible plant tissue that looks affected.", "sev")}
              ${renderGauge("Chance of spreading", result.spread_chance_percent, "Estimate from the disease type and visible severity — not a forecast.", "spread")}
            </div>
          </div>

          <!-- Row 2: Symptoms & Reasoning -->
          <div class="scn-grid-2col">
            <div class="scn-card scn-symptoms-card">
              <div class="scn-card-header">
                <svg class="scn-card-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10 10-4.5 10-10S17.5 2 12 2z"/><path d="M12 2v20M2 12h20"/></svg>
                <h3 class="scn-card-title">Visible symptoms</h3>
              </div>
              ${symptoms.length
                ? `<ul class="scn-chips-list">${symptoms.map((s) => `
                    <li class="scn-chip">
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 8C8 10 5.9 16.17 3.82 21.34l1.89.66.95-2.3c.48.17.98.3 1.34.3C19 20 22 3 22 3c-1 2-8 2.25-13 3.25S2 11.5 2 13.5s1.75 3.75 1.75 3.75C7 8 17 8 17 8z"/></svg>
                      <span>${escapeHtml(s)}</span>
                    </li>`).join("")}</ul>`
                : `<p class="scn-muted-text">No symptoms listed.</p>`}
            </div>

            <div class="scn-card scn-reasoning-card">
              <div class="scn-card-header">
                <svg class="scn-card-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18h6m-4 4h2M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z"/></svg>
                <h3 class="scn-card-title">Why FOLIX thinks this</h3>
              </div>
              <p class="scn-reasoning-text">${escapeHtml(result.reasoning || "No explanation provided yet.")}</p>
            </div>
          </div>

          <!-- Row 3: Recommended Action -->
          <div class="scn-card scn-actions-card">
            <div class="scn-card-header">
              <span class="scn-check-badge">✓</span>
              <h3 class="scn-card-title">Recommended action</h3>
            </div>
            <div class="scn-action-items-grid">
              ${actionsHtml}
            </div>
          </div>

          <!-- Row 4: 3 Columns -->
          <div class="scn-grid-3col">
            <div class="scn-card scn-remedies-col">
              <div class="scn-card-header">
                <svg class="scn-card-icon" viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M17 8C8 10 5.9 16.17 3.82 21.34l1.89.66.95-2.3c.48.17.98.3 1.34.3C19 20 22 3 22 3c-1 2-8 2.25-13 3.25S2 11.5 2 13.5s1.75 3.75 1.75 3.75C7 8 17 8 17 8z"/></svg>
                <h3 class="scn-card-title">Organic Remedies</h3>
              </div>
              <div class="scn-remedies-body">
                ${renderOrganicRemediesHtml(result.remedy_plan)}
              </div>
            </div>

            <div class="scn-card scn-remedies-col">
              <div class="scn-card-header">
                <svg class="scn-card-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M10 2v7.31M14 2v7.31M8.5 2h7M14 9.3l5.5 9.7A2 2 0 0 1 17.8 22H6.2a2 2 0 0 1-1.7-3L10 9.3"/></svg>
                <h3 class="scn-card-title">Chemical Remedies</h3>
              </div>
              <div class="scn-remedies-body">
                ${renderChemicalRemediesHtml(result.remedy_plan)}
              </div>
            </div>

            <div class="scn-card scn-review-col">
              <div class="scn-card-header">
                <svg class="scn-card-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
                <h3 class="scn-card-title">Review status</h3>
              </div>
              <div class="scn-review-box ${needsReview ? 'needs-review' : 'review-ok'}">
                <span class="scn-review-icon-badge">${needsReview ? '⚠️' : '✓'}</span>
                <div class="scn-review-text">
                  <strong>${needsReview ? 'Human review recommended.' : 'No human review flagged.'}</strong>
                  <p>${needsReview ? 'Consider having an agronomist confirm this assessment before acting on it.' : 'No human review flagged for this result, though you\'re always welcome to seek one.'}</p>
                </div>
              </div>
              <div class="scn-review-actions-wrap" id="scnReviewActionsWrap"></div>
            </div>
          </div>
        </div>
      </div>
    `;

    el.resultContent.innerHTML = html;
    const actionsWrap = el.resultContent.querySelector("#scnReviewActionsWrap") || el.resultContent;
    renderActionsRow(actionsWrap, result);

    const backBtn = el.resultContent.querySelector("#scnBackToScanBtn");
    if (backBtn) {
      backBtn.addEventListener("click", (e) => {
        e.preventDefault();
        removeImage();
      });
    }

    const viewOrigBtn = el.resultContent.querySelector("#scnViewOriginalBtn");
    if (viewOrigBtn) {
      viewOrigBtn.addEventListener("click", (e) => {
        e.preventDefault();
        if (photoUrl) openImageModal(photoUrl);
      });
    }

    setStatus("result");
    announce(isProvisional ? "Provisional on-device result ready, while offline. Awaiting confirmation." : "Assessment ready.");
  }

  /**
   * Renders the uncertain / human-review-needed state. Never invents a
   * disease — this is the honest "we can't tell" path.
   * @param {object} result — partial contract object; only reasoning,
   *   plant_part and needs_human_review are typically meaningful here.
   */
  function renderUncertain(result) {
    result = result || {};
    // Offline queue + Gemini-on-reconnect (Requirement 2b): additive marker set only by
    // mapPendingToDiagnosis (CropGuardOffline.offlineAnalysisUnavailable()) — never present on a
    // real Gemini response — so the default "more information is needed" copy below is unaffected.
    const isPending = result._folixSyncStatus === "pending";

    const html = isPending ? `
      <span class="scn-uncertain-icon" aria-hidden="true">
        <svg viewBox="0 0 32 32" width="30" height="30"><circle cx="16" cy="16" r="14.5" fill="none" stroke="currentColor" stroke-width="1.4"/><path d="M16 10.5v6.2M16 21v.4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
      </span>
      <h2 class="scn-uncertain-title">Result pending — you're offline</h2>
      <p class="scn-uncertain-lead">${escapeHtml(result.reasoning || "This photo has been queued and will be analyzed automatically once you're back online.")}</p>

      <div class="scn-section">
        <p class="scn-section-label">What happens next</p>
        <div class="scn-review-banner">
          <p>This photo is saved on this device. FOLIX will send it for a real diagnosis the moment your connection returns — no guess has been made in the meantime.</p>
        </div>
      </div>
    ` : `
      <span class="scn-uncertain-icon" aria-hidden="true">
        <svg viewBox="0 0 32 32" width="30" height="30"><circle cx="16" cy="16" r="14.5" fill="none" stroke="currentColor" stroke-width="1.4"/><path d="M16 10.5v6.2M16 21v.4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
      </span>
      <h2 class="scn-uncertain-title">More information is needed</h2>
      <p class="scn-uncertain-lead">The image does not provide enough visual evidence for a reliable assessment.</p>

      <div class="scn-section">
        <p class="scn-section-label">Why we're not certain</p>
        <p>${escapeHtml(result.reasoning || "The visible evidence in this image wasn't sufficient to identify a condition with confidence.")}</p>
      </div>

      <div class="scn-section">
        <p class="scn-section-label">What would help</p>
        <p>A closer, well-lit photo of the affected area — ideally filling more of the frame — would give FOLIX more to examine.</p>
      </div>

      <div class="scn-section">
        <p class="scn-section-label">Recommended step</p>
        <div class="scn-review-banner needs-review">
          <p><strong>Human review recommended.</strong> For anything urgent, please consult a local agronomist or plant protection authority directly.</p>
        </div>
      </div>
    `;

    el.uncertainContent.innerHTML = html;
    renderActionsRow(el.uncertainContent, result);
    // Stage 5B: "Save Report" is now wired here too (an uncertain result can
    // still be a valid FOLIX report — e.g. disease "unknown" with
    // needs_human_review true — so the same persistence path applies).
    setStatus("uncertain");
    announce(isPending ? "This photo is offline and queued. It will be analyzed once you're back online." : "More information is needed for a reliable assessment.");
  }

  // ---------------------------------------------------------------------
  // Wiring
  // ---------------------------------------------------------------------

  function init() {
    cacheEls();
    if (!el.root) return; // Scanner markup not present on this page.

    wireDropzone();

    if (el.fileInput) {
      el.fileInput.addEventListener("change", (e) => {
        const file = e.target.files && e.target.files[0];
        if (file) selectFile(file);
      });
    }

    if (el.useCameraBtn) el.useCameraBtn.addEventListener("click", (e) => { e.stopPropagation(); startCamera(); });
    if (el.cameraCancelBtn) el.cameraCancelBtn.addEventListener("click", cancelCamera);
    if (el.cameraCaptureBtn) el.cameraCaptureBtn.addEventListener("click", capturePhoto);

    if (el.changeBtn) el.changeBtn.addEventListener("click", changeImage);
    if (el.removeBtn) el.removeBtn.addEventListener("click", removeImage);
    if (el.analyzeBtn) el.analyzeBtn.addEventListener("click", beginAnalysis);
    if (el.cancelAnalysisBtn) el.cancelAnalysisBtn.addEventListener("click", cancelAnalysis);
    if (el.errorRetryBtn) el.errorRetryBtn.addEventListener("click", beginAnalysis);
    if (el.errorChooseBtn) el.errorChooseBtn.addEventListener("click", removeImage);

    if (el.modalCloseBtn) el.modalCloseBtn.addEventListener("click", closeImageModal);
    if (el.modalBackdrop) el.modalBackdrop.addEventListener("click", closeImageModal);
    window.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && el.imageModal && !el.imageModal.hidden) closeImageModal();
    });

    window.addEventListener("beforeunload", () => {
      abortAnalysis();
      revokeObjectUrl();
      stopCamera();
    });

    // Offline queue + Gemini-on-reconnect: sync any scans queued in a previous session the moment
    // we're back online, plus once now in case the page loaded already online with a leftover queue.
    window.addEventListener("online", syncOfflineQueue);
    syncOfflineQueue();

    if (navigator.geolocation && (!localStorage.getItem("folix_latitude") || !localStorage.getItem("folix_longitude"))) {
      navigator.geolocation.getCurrentPosition((pos) => {
        try {
          localStorage.setItem("folix_latitude", String(pos.coords.latitude));
          localStorage.setItem("folix_longitude", String(pos.coords.longitude));
          fetch(API_BASE_URL + "/api/farmer/location", {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            credentials: "include",
            body: JSON.stringify({ consent: true, latitude: pos.coords.latitude, longitude: pos.coords.longitude }),
          }).catch(() => {});
        } catch (_) {}
      }, () => {}, { timeout: 8000, enableHighAccuracy: false });
    }

    setStatus("empty");
  }

  document.addEventListener("DOMContentLoaded", init);

  // Exposed so the result panels can be driven directly (manual testing, or
  // other stages), per the project's `renderDiagnosis(result)` contract.
  window.FolixScanner = {
    renderDiagnosis,
    renderUncertain,
    showResult: routeResult, // applies the uncertainty routing rule; handy from the console
    reset: () => removeImage(),
  };
})();
