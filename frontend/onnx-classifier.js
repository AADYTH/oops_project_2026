"use strict";

/**
 * CropGuardOnnx — genuine on-device ML inference for offline mode.
 * ===================================================================
 * READ THIS BEFORE ASSUMING OFFLINE DIAGNOSIS WORKS OUT OF THE BOX
 * -------------------------------------------------------------------
 * CropGuard's architecture requires that offline mode perform REAL local
 * ML inference — no color heuristic, no hard-coded guessing (see README,
 * "Offline mode"). This file is real, working code for that: it loads an
 * ONNX Runtime Web session and runs actual tensor inference against
 * whatever model is at MODEL_PATH.
 *
 * What this file does NOT do is ship you a trained model. There are no
 * model weights in this repository — training and exporting one requires:
 *   1. Actually running scripts/benchmark_models.py (in inference-service)
 *      against real datasets to select ONE primary model, per README
 *      "Primary disease model" — not done in this pass; see that script's
 *      own docstring for exactly why (no dataset/Hub network access in the
 *      environment this change was written in).
 *   2. Running inference-service/scripts/export_onnx_model.py against
 *      that selected model to produce model.onnx + labels.json.
 *   3. Placing those two files at frontend/model/model.onnx and
 *      frontend/model/labels.json (or pointing MODEL_PATH/LABELS_PATH
 *      below elsewhere) and having the service worker (sw.js) cache them
 *      for true offline availability.
 *
 * Until that's done, isModelInstalled() below correctly reports false, and
 * the app falls back to the honest ANALYSIS_UNAVAILABLE offline result in
 * offline-classifier.js — never a fabricated guess. This mirrors the
 * project's existing pattern for the online panel models: wired for real,
 * inert without real weights, loud (not silent) about which state it's in.
 *
 * Loading onnxruntime-web
 * -------------------------
 * Add this to frontend/index.html (or your build's script list), pinned to
 * a specific version:
 *   <script src="https://cdn.jsdelivr.net/npm/onnxruntime-web@1.19.2/dist/ort.min.js"></script>
 * or `npm install onnxruntime-web` and bundle it if this project moves to
 * a build step. Nothing below assumes a build step; it works as a plain
 * <script> include the same way the rest of this frontend does.
 */
const CropGuardOnnx = (() => {
  const MODEL_PATH = "model/model.onnx";
  const LABELS_PATH = "model/labels.json";
  const INPUT_SIZE = 224; // matches the panel models' preprocessing (MobileNet/ViT/Swin all use 224x224)

  let session = null;
  let labels = null;
  let loadAttempted = false;
  let loadFailed = false;

  async function tryLoad() {
    if (loadAttempted) return;
    loadAttempted = true;

    if (typeof ort === "undefined") {
      console.info("CropGuardOnnx: onnxruntime-web ('ort') is not loaded — see this file's module docstring "
                   + "for the <script> tag to add. On-device offline diagnosis is unavailable until then.");
      loadFailed = true;
      return;
    }

    try {
      const [modelResponse, labelsResponse] = await Promise.all([fetch(MODEL_PATH), fetch(LABELS_PATH)]);
      if (!modelResponse.ok || !labelsResponse.ok) {
        console.info(`CropGuardOnnx: no exported model found at ${MODEL_PATH} — this is expected until you run `
                     + "inference-service/scripts/export_onnx_model.py and install its output here. "
                     + "Falling back to ANALYSIS_UNAVAILABLE while offline.");
        loadFailed = true;
        return;
      }
      const modelBytes = await modelResponse.arrayBuffer();
      labels = await labelsResponse.json();
      session = await ort.InferenceSession.create(modelBytes, { executionProviders: ["wasm"] });
      console.info(`CropGuardOnnx: loaded on-device model (${labels.length} classes). Genuine offline diagnosis is active.`);
    } catch (e) {
      console.warn("CropGuardOnnx: failed to load on-device model:", e);
      loadFailed = true;
    }
  }

  async function isModelInstalled() {
    await tryLoad();
    return session !== null && !loadFailed;
  }

  /** Standard ImageNet-style preprocessing: resize, normalize, CHW layout. */
  function preprocess(imgOrCanvas) {
    const canvas = document.createElement("canvas");
    canvas.width = INPUT_SIZE;
    canvas.height = INPUT_SIZE;
    const ctx = canvas.getContext("2d");
    ctx.drawImage(imgOrCanvas, 0, 0, INPUT_SIZE, INPUT_SIZE);
    const { data } = ctx.getImageData(0, 0, INPUT_SIZE, INPUT_SIZE);

    const mean = [0.485, 0.456, 0.406];
    const std = [0.229, 0.224, 0.225];
    const chw = new Float32Array(3 * INPUT_SIZE * INPUT_SIZE);
    const plane = INPUT_SIZE * INPUT_SIZE;
    for (let i = 0, p = 0; i < data.length; i += 4, p++) {
      chw[p] = (data[i] / 255 - mean[0]) / std[0];               // R
      chw[plane + p] = (data[i + 1] / 255 - mean[1]) / std[1];   // G
      chw[2 * plane + p] = (data[i + 2] / 255 - mean[2]) / std[2]; // B
    }
    return new ort.Tensor("float32", chw, [1, 3, INPUT_SIZE, INPUT_SIZE]);
  }

  function softmax(logits) {
    const max = Math.max(...logits);
    const exps = logits.map((v) => Math.exp(v - max));
    const sum = exps.reduce((a, b) => a + b, 0);
    return exps.map((v) => v / sum);
  }

  /**
   * Runs real on-device inference. Only call this after isModelInstalled()
   * returns true. Returns the same shape offline-classifier.js's
   * ANALYSIS_UNAVAILABLE result uses, so app.js's renderOfflineEstimate can
   * handle either without a third code path.
   */
  async function classify(imgOrCanvas) {
    if (!session) throw new Error("No on-device model loaded — call isModelInstalled() first.");

    const inputName = session.inputNames[0];
    const tensor = preprocess(imgOrCanvas);
    const output = await session.run({ [inputName]: tensor });
    const outputName = session.outputNames[0];
    const logits = Array.from(output[outputName].data);
    const probs = softmax(logits);

    let bestIdx = 0;
    for (let i = 1; i < probs.length; i++) {
      if (probs[i] > probs[bestIdx]) bestIdx = i;
    }
    const label = labels[bestIdx] || `class_${bestIdx}`;
    // labels.json (from export_onnx_model.py) stores "Crop___Disease" style
    // entries, same convention as the online panel's canonical labels.
    const [cropName, diseaseName] = label.includes("___") ? label.split("___") : [null, label];

    return {
      cropName: cropName || "Unknown",
      diseaseName: diseaseName.replace(/_/g, " "),
      confidencePercent: Math.round(probs[bestIdx] * 1000) / 10,
      confidenceLevel: probs[bestIdx] >= 0.75 ? "HIGH" : probs[bestIdx] >= 0.45 ? "MEDIUM" : "LOW",
      severityPercent: null, // on-device model does classification only, no severity segmentation
      isEpidemicRisk: false, // epidemic-risk keyword matching happens server-side (disease_labels.py); not duplicated here
      isOfflineEstimate: true,
      note: "Diagnosed on-device (genuine local ML inference, not a color heuristic) while offline. " +
            "Severity and epidemic-risk flagging need the full backend and will be added once you're back online.",
    };
  }

  return { isModelInstalled, classify };
})();
