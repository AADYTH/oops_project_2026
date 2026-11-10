"use strict";

/**
 * FOLIX frontend — talks to the real Spring Boot backend at API_BASE_URL.
 * No mock or fabricated results are ever rendered here: every value shown in
 * the results panel comes directly from the backend's JSON response.
 */
const API_BASE_URL = window.CROPGUARD_API_BASE_URL || "http://localhost:8080";

const state = {
  imageBlob: null,
  imageObjectUrl: null,
  latitude: null,
  longitude: null,
  cameraStream: null,
  history: [],
};

// ---------------------------------------------------------------------------
// Offline mode (Tier 2 upgrade #4): reflect connectivity in the UI and
// auto-sync any queued scans the moment we're back online.
// ---------------------------------------------------------------------------
const offlineBadge = document.getElementById("offlineBadge");

function updateOfflineBadge() {
  offlineBadge.hidden = navigator.onLine;
}

async function syncOfflineQueue() {
  if (!navigator.onLine) return;
  const { synced } = await CropGuardOffline.trySyncQueue(API_BASE_URL, (scan, result, err) => {
    if (result) {
      addToHistory(result, null);
    }
  });
  if (synced > 0) {
    console.info(`Synced ${synced} offline-queued scan(s) now that connectivity is back.`);
  }
}

window.addEventListener("online", () => { updateOfflineBadge(); syncOfflineQueue(); });
window.addEventListener("offline", updateOfflineBadge);
updateOfflineBadge();
syncOfflineQueue();

// ---------------------------------------------------------------------------
// Tabs (Upload vs Camera)
// ---------------------------------------------------------------------------
document.querySelectorAll(".tab").forEach((tabButton) => {
  tabButton.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((t) => { t.classList.remove("active"); t.setAttribute("aria-selected", "false"); });
    document.querySelectorAll(".tab-panel").forEach((p) => p.classList.remove("active"));
    tabButton.classList.add("active");
    tabButton.setAttribute("aria-selected", "true");
    document.querySelector(`.tab-panel[data-panel="${tabButton.dataset.tab}"]`).classList.add("active");
    if (tabButton.dataset.tab !== "camera") {
      stopCamera();
    }
  });
});

// ---------------------------------------------------------------------------
// File upload / drag & drop
// ---------------------------------------------------------------------------
const dropzone = document.getElementById("dropzone");
const fileInput = document.getElementById("fileInput");

fileInput.addEventListener("change", (e) => {
  if (e.target.files && e.target.files[0]) setImage(e.target.files[0]);
});

["dragenter", "dragover"].forEach((evt) =>
  dropzone.addEventListener(evt, (e) => { e.preventDefault(); dropzone.classList.add("dragover"); })
);
["dragleave", "drop"].forEach((evt) =>
  dropzone.addEventListener(evt, (e) => { e.preventDefault(); dropzone.classList.remove("dragover"); })
);
dropzone.addEventListener("drop", (e) => {
  const file = e.dataTransfer.files && e.dataTransfer.files[0];
  if (file) setImage(file);
});

document.getElementById("clearImageBtn").addEventListener("click", () => {
  state.imageBlob = null;
  if (state.imageObjectUrl) URL.revokeObjectURL(state.imageObjectUrl);
  document.getElementById("previewBlock").hidden = true;
  fileInput.value = "";
  updateSubmitEnabled();
});

function setImage(fileOrBlob) {
  state.imageBlob = fileOrBlob;
  if (state.imageObjectUrl) URL.revokeObjectURL(state.imageObjectUrl);
  state.imageObjectUrl = URL.createObjectURL(fileOrBlob);
  const previewImage = document.getElementById("previewImage");
  previewImage.src = state.imageObjectUrl;
  document.getElementById("previewBlock").hidden = false;
  updateSubmitEnabled();
}

// ---------------------------------------------------------------------------
// Camera capture
// ---------------------------------------------------------------------------
const video = document.getElementById("cameraVideo");
const canvas = document.getElementById("cameraCanvas");
const startCameraBtn = document.getElementById("startCameraBtn");
const captureBtn = document.getElementById("captureBtn");

startCameraBtn.addEventListener("click", async () => {
  try {
    state.cameraStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: "environment", width: { ideal: 1280 }, height: { ideal: 960 } },
    });
    video.srcObject = state.cameraStream;
    startCameraBtn.hidden = true;
    captureBtn.hidden = false;
  } catch (err) {
    alert("Could not access the camera: " + err.message + ". You can still use 'Upload a photo' instead.");
  }
});

captureBtn.addEventListener("click", () => {
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  canvas.getContext("2d").drawImage(video, 0, 0);
  canvas.toBlob((blob) => {
    if (blob) setImage(blob);
  }, "image/jpeg", 0.92);
});

function stopCamera() {
  if (state.cameraStream) {
    state.cameraStream.getTracks().forEach((t) => t.stop());
    state.cameraStream = null;
    startCameraBtn.hidden = false;
    captureBtn.hidden = true;
  }
}

// ---------------------------------------------------------------------------
// Geolocation
// ---------------------------------------------------------------------------
document.getElementById("shareLocationBtn").addEventListener("click", () => {
  const statusEl = document.getElementById("locationStatus");
  if (!navigator.geolocation) {
    statusEl.textContent = "Geolocation isn't supported by this browser.";
    return;
  }
  statusEl.textContent = "Requesting location permission...";
  navigator.geolocation.getCurrentPosition(
    (position) => {
      state.latitude = position.coords.latitude;
      state.longitude = position.coords.longitude;
      statusEl.textContent = `Location shared (±${Math.round(position.coords.accuracy)} m accuracy). We'll use this only if escalation is needed.`;
      loadWeatherRisk(state.latitude, state.longitude);
    },
    (err) => {
      statusEl.textContent = "Location permission denied or unavailable: " + err.message + ". Severe results still show, but automated authority alerts won't be possible.";
    },
    { enableHighAccuracy: true, timeout: 10000 }
  );
});

// ---------------------------------------------------------------------------
// Submit scan
// ---------------------------------------------------------------------------
const submitBtn = document.getElementById("submitScanBtn");

function updateSubmitEnabled() {
  submitBtn.disabled = !state.imageBlob;
}

submitBtn.addEventListener("click", () => performSubmit(false));

async function performSubmit(skipQualityCheck) {
  if (!state.imageBlob) return;

  const qualityWarning = document.getElementById("qualityWarning");
  qualityWarning.hidden = true;
  qualityWarning.innerHTML = "";

  // Client-side image quality pre-check (Tier 3 upgrade #11): don't spend an
  // upload/inference call on a photo that's obviously unusable. The backend
  // repeats this check server-side, so this is purely a fast, friendly,
  // bandwidth-saving first pass — not the only line of defense. It is a
  // HEURISTIC, so it can be wrong (e.g. on pale symptoms like powdery
  // mildew); "Diagnose anyway" lets the user override it rather than being
  // stuck.
  if (!skipQualityCheck) {
    try {
      const previewImage = document.getElementById("previewImage");
      const quality = CropGuardOffline.checkImageQuality(previewImage);
      console.info("Client-side image quality check:", quality);
      if (!quality.passed) {
        const reasons = {
          IMAGE_TOO_BLURRY: "This photo looks too blurry to diagnose reliably. Hold the camera steady and try again.",
          IMAGE_TOO_DARK: "This photo is too dark to diagnose reliably. Try again in better light.",
          IMAGE_OVEREXPOSED: "This photo is too bright/overexposed. Try again out of direct glare.",
        };
        qualityWarning.textContent =
          (reasons[quality.reason] || "This photo may be too low quality to diagnose reliably.") + " ";
        const proceedBtn = document.createElement("button");
        proceedBtn.className = "btn btn-ghost btn-small";
        proceedBtn.type = "button";
        proceedBtn.textContent = "Diagnose anyway";
        proceedBtn.addEventListener("click", () => performSubmit(true));
        qualityWarning.appendChild(proceedBtn);
        qualityWarning.hidden = false;
        return;
      }
    } catch (e) {
      // If the quality check itself fails for some reason, don't block the scan on it.
      console.warn("Client-side image quality check failed to run:", e);
    }
  }

  submitBtn.disabled = true;
  submitBtn.textContent = "Analyzing...";
  showResultsLoading();

  const plantPart = document.getElementById("plantPartSelect").value;

  // Offline mode (Tier 2 upgrade #4): queue the scan, and show either a REAL
  // on-device diagnosis (if a genuine exported model is installed — see
  // onnx-classifier.js) or an honest "can't diagnose right now" message.
  // Never a color-heuristic guess dressed up as a diagnosis — see
  // offline-classifier.js module docstring for why that was removed.
  if (!navigator.onLine) {
    try {
      const previewImage = document.getElementById("previewImage");
      const offlineEstimate = (window.CropGuardOnnx && await CropGuardOnnx.isModelInstalled())
        ? await CropGuardOnnx.classify(previewImage)
        : CropGuardOffline.offlineAnalysisUnavailable();
      await CropGuardOffline.queueScan({
        blob: state.imageBlob, plantPart, latitude: state.latitude, longitude: state.longitude, offlineEstimate,
      });
      renderOfflineEstimate(offlineEstimate);
    } catch (err) {
      showResultsError("You're offline and the on-device estimate failed: " + err.message);
    } finally {
      submitBtn.disabled = false;
      submitBtn.textContent = CropGuardI18n.t("scan.submit");
      updateSubmitEnabled();
    }
    return;
  }

  const formData = new FormData();
  formData.append("image", state.imageBlob, "scan.jpg");
  formData.append("plantPart", plantPart);
  formData.append("skipQualityCheck", String(skipQualityCheck));
  if (state.latitude != null && state.longitude != null) {
    formData.append("latitude", String(state.latitude));
    formData.append("longitude", String(state.longitude));
  }

  try {
    const response = await fetch(`${API_BASE_URL}/api/v1/scans`, { method: "POST", body: formData });
    if (!response.ok) {
      const errorBody = await response.json().catch(() => ({}));
      // Always log the full error payload (including blurScore/brightness for
      // image-quality rejections) so it's visible in the browser console for
      // debugging, even though the on-page message stays short and friendly.
      console.warn("Scan request failed:", response.status, errorBody);
      let message = errorBody.message || `Request failed with status ${response.status}`;
      if (errorBody.errorCode && errorBody.errorCode.startsWith("IMAGE_")) {
        message += ` (blur score: ${errorBody.blurScore?.toFixed?.(1) ?? "?"}, brightness: ${errorBody.brightness?.toFixed?.(1) ?? "?"} — see browser console for full detail)`;
        // Server-side rejection can also be overridden — surfaced in the results panel
        // itself since that's where showResultsError renders the message.
        setTimeout(() => {
          const empty = document.getElementById("resultsEmpty");
          if (empty.hidden) return;
          const proceedBtn = document.createElement("button");
          proceedBtn.className = "btn btn-secondary btn-small";
          proceedBtn.type = "button";
          proceedBtn.style.marginTop = "10px";
          proceedBtn.textContent = "Diagnose anyway";
          proceedBtn.addEventListener("click", () => performSubmit(true));
          empty.appendChild(proceedBtn);
        }, 0);
      }
      throw new Error(message);
    }
    const result = await response.json();
    renderResult(result);
    addToHistory(result, state.imageObjectUrl);
    loadReminders(result.scanId);
  } catch (err) {
    showResultsError(err.message);
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = CropGuardI18n.t("scan.submit");
    updateSubmitEnabled();
  }
}

// ---------------------------------------------------------------------------
// Voice input (Tier 2 upgrade #6): spoken symptom description, transcribed
// and shown on-screen, plus a read-back for accessibility.
// ---------------------------------------------------------------------------
document.getElementById("voiceInputBtn").addEventListener("click", async () => {
  const btn = document.getElementById("voiceInputBtn");
  const originalText = btn.textContent;
  btn.disabled = true;
  btn.textContent = "🎙 Listening...";
  try {
    const transcript = await CropGuardI18n.listenOnce();
    CropGuardI18n.speak(transcript);
    alert("Heard: " + transcript + "\n\n(This is noted for your reference — please still take a clear photo for diagnosis.)");
  } catch (err) {
    alert("Voice input didn't work: " + err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = originalText;
  }
});

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------
function showResultsLoading() {
  const empty = document.getElementById("resultsEmpty");
  const content = document.getElementById("resultsContent");
  content.hidden = true;
  empty.hidden = false;
  empty.innerHTML = "<p>Analyzing your photo…</p>";
}

function showResultsError(message) {
  const empty = document.getElementById("resultsEmpty");
  const content = document.getElementById("resultsContent");
  content.hidden = true;
  empty.hidden = false;
  empty.innerHTML = `<p><strong>Diagnosis failed.</strong><br>${escapeHtml(message)}</p>`;
}

function severityClass(pct) {
  if (pct > 50) return "severity-high";
  if (pct > 20) return "severity-medium";
  return "";
}

// ---------------------------------------------------------------------------
// Panel-of-models display. The backend runs the photo through several
// independent classifiers and reconciles their votes (see
// inference-service/ensemble.py) before this response is ever sent —
// this renders THAT reconciliation transparently instead of only showing
// the single winning answer, so a farmer/agronomist can see for themselves
// whether the models agreed or fought it out.
// ---------------------------------------------------------------------------
const CONSENSUS_COPY = {
  UNANIMOUS: { label: "All models agree", cls: "consensus-unanimous" },
  MAJORITY: { label: "Majority of models agree", cls: "consensus-majority" },
  SPLIT: { label: "Models disagree — treat with caution", cls: "consensus-split" },
  SINGLE_MODEL: { label: "Single model (no panel comparison)", cls: "consensus-single" },
};

function buildModelPanelHtml(result) {
  const panel = result.modelPanel || [];
  if (panel.length === 0) return "";

  const consensus = result.consensus || {
    level: "SINGLE_MODEL", agreeingModels: panel.length, totalModels: panel.length, agreeingModelNames: [],
  };
  const copy = CONSENSUS_COPY[consensus.level] || CONSENSUS_COPY.SINGLE_MODEL;
  const agreeingSet = new Set(consensus.agreeingModelNames || []);

  const rowsHtml = panel
    .slice()
    .sort((a, b) => b.confidencePercent - a.confidencePercent)
    .map((v) => {
      const agreed = agreeingSet.has(v.displayName);
      return `
        <li class="model-vote-row ${agreed ? "model-vote-agree" : "model-vote-disagree"}">
          <div class="model-vote-name">
            <span>${escapeHtml(v.displayName)}</span>
            <span class="model-vote-arch">${escapeHtml(v.architecture)}</span>
          </div>
          <div class="model-vote-verdict">${escapeHtml(v.diseaseName)} <span class="model-vote-crop">on ${escapeHtml(v.cropName)}</span></div>
          <div class="model-vote-conf">${v.confidencePercent.toFixed(1)}%</div>
        </li>`;
    })
    .join("");

  const countLabel = consensus.level === "SINGLE_MODEL"
    ? "1 model"
    : `${consensus.agreeingModels} of ${consensus.totalModels} models agreed`;

  return `
    <div class="model-panel">
      <button class="model-panel-toggle" type="button" aria-expanded="false">
        <span class="model-panel-title">Panel of ${panel.length} model${panel.length === 1 ? "" : "s"}</span>
        <span class="consensus-badge ${copy.cls}">${escapeHtml(copy.label)}</span>
        <span class="model-panel-caret" aria-hidden="true">▾</span>
      </button>
      <p class="model-panel-summary">${escapeHtml(countLabel)} on the final answer shown above.</p>
      <ul class="model-panel-list" hidden>${rowsHtml}</ul>
    </div>`;
}

function renderResult(result) {
  document.getElementById("resultsEmpty").hidden = true;
  const content = document.getElementById("resultsContent");
  content.hidden = false;

  const epidemicBadge = result.isEpidemicRisk
    ? `<span class="badge badge-epidemic">Fast-spreading risk</span>` : "";
  const healthyBadge = result.diseaseName.toLowerCase().includes("healthy")
    ? `<span class="badge badge-healthy">Healthy</span>` : "";

  const organicHtml = (result.remedyPlan.organicRemedies || []).map((r) => `
    <div class="remedy-card">
      <h5>${escapeHtml(r.name)}</h5>
      <p>${escapeHtml(r.instructions)}</p>
      <div class="remedy-meta"><span>Frequency: ${escapeHtml(r.applicationFrequency)}</span></div>
    </div>`).join("") || "<p>No organic remedy is recorded for this diagnosis.</p>";

  const chemicalHtml = (result.remedyPlan.chemicalRemedies || []).map((r) => `
    <div class="remedy-card">
      <h5>${escapeHtml(r.name)} <span style="font-weight:400;color:var(--ink-soft)">(${escapeHtml(r.activeIngredient)})</span></h5>
      <p>${escapeHtml(r.instructions)}</p>
      <div class="remedy-meta">
        <span>Dosage: ${escapeHtml(r.dosage)}</span>
        <span>Pre-harvest interval: ${escapeHtml(r.preHarvestIntervalDays)}</span>
      </div>
      <p style="margin-top:8px;font-size:0.82rem;color:var(--clay)">${escapeHtml(r.safetyPrecautions)}</p>
    </div>`).join("") || "<p>No chemical control is recommended — see the organic guidance above.</p>";

  // Confidence gating (Tier 1 upgrade #1): a low-confidence guess must never
  // look identical to a high-confidence one. Below the LOW band we lead with
  // an uncertainty banner and the runner-up guesses instead of presenting
  // the top guess as settled fact.
  // Part-support gating: this photo's plant part (stem/fruit/root/flower/
  // whole plant) is outside what the classifier was ever trained on, so its
  // crop/disease guess has no real ground truth behind it. This is shown
  // ahead of, and distinctly from, the generic low-confidence banner below,
  // because the fix here is "photograph a leaf instead" rather than "treat
  // this as one of several possibilities."
  let partWarningHtml = "";
  if (result.partSupported === false && result.partWarning) {
    partWarningHtml = `
      <div class="uncertainty-banner">
        <h4>${escapeHtml(CropGuardI18n.t("result.partUnsupported"))}</h4>
        <p>${escapeHtml(result.partWarning)}</p>
      </div>`;
  }

  let confidenceBannerHtml = "";
  if (result.lowConfidence && result.partSupported !== false) {
    const altHtml = (result.alternatives || []).map((a) => `
      <li>${escapeHtml(a.diseaseName)} on ${escapeHtml(a.cropName)} — ${a.confidencePercent.toFixed(1)}%</li>
    `).join("");
    confidenceBannerHtml = `
      <div class="uncertainty-banner">
        <h4>${escapeHtml(CropGuardI18n.t("result.uncertain"))}</h4>
        <p>${escapeHtml(CropGuardI18n.t("result.uncertainBody"))}</p>
        ${altHtml ? `<p style="margin-top:8px;font-weight:600;">${escapeHtml(CropGuardI18n.t("result.alternatives"))}</p><ul>${altHtml}</ul>` : ""}
      </div>`;
  }

  const panelHtml = buildModelPanelHtml(result);

  let escalationHtml = "";
  if (result.severe) {
    if (result.escalationStatus === "AUTHORITY_NOTIFIED") {
      escalationHtml = `
        <div class="emergency-banner">
          <h4>Nearby authority notified</h4>
          <p><strong>${escapeHtml(result.notifiedAuthorityName)}</strong> (${result.notifiedAuthorityDistanceKm != null ? result.notifiedAuthorityDistanceKm.toFixed(1) + " km away" : ""}) has been sent an automated alert.
          ${result.notifiedAuthorityPhone ? " Phone: " + escapeHtml(result.notifiedAuthorityPhone) : ""}
          ${result.notifiedAuthorityEmail ? " Email: " + escapeHtml(result.notifiedAuthorityEmail) : ""}</p>
        </div>`;
    } else {
      escalationHtml = `
        <div class="emergency-banner">
          <h4>This result is severe — please act</h4>
          <p>We could not automatically confirm an authority notification (${escapeHtml(result.escalationStatus)}). Please contact your nearest Krishi Vigyan Kendra or district agriculture extension office directly and describe this diagnosis.</p>
        </div>`;
    }
  }

  content.innerHTML = `
    <div class="result-header">
      <div>
        <p class="result-crop">${escapeHtml(result.cropName)} · ${escapeHtml(result.affectedPlantPart.toLowerCase())}</p>
        <h3 class="result-disease">${escapeHtml(result.diseaseName)}</h3>
      </div>
      <div style="display:flex;gap:8px;">${epidemicBadge}${healthyBadge}</div>
    </div>

    <div class="meter-block">
      <div class="meter-label"><span>Model confidence</span><span>${result.confidencePercent.toFixed(1)}%</span></div>
      <div class="meter-track"><div class="meter-fill" style="width:${result.confidencePercent}%"></div></div>
    </div>

    <div class="meter-block">
      <div class="meter-label"><span>Estimated severity</span><span>${result.severityPercent.toFixed(1)}%</span></div>
      <div class="meter-track"><div class="meter-fill ${severityClass(result.severityPercent)}" style="width:${result.severityPercent}%"></div></div>
    </div>

    ${panelHtml}
    ${partWarningHtml}
    ${confidenceBannerHtml}
    ${escalationHtml}

    <div class="remedy-tabs" role="tablist">
      <button class="tab active" data-remedy="organic">Organic remedies</button>
      <button class="tab" data-remedy="chemical">Chemical options</button>
    </div>
    <div class="remedy-panel active" data-remedy-panel="organic">${organicHtml}</div>
    <div class="remedy-panel" data-remedy-panel="chemical">${chemicalHtml}</div>

    <button class="btn btn-ghost btn-small" id="readAloudBtn" type="button">🔊 Read result aloud</button>

    <div class="feedback-block" id="feedbackBlock">
      <p class="feedback-prompt">${escapeHtml(CropGuardI18n.t("feedback.prompt"))}</p>
      <div class="feedback-buttons">
        <button class="btn btn-secondary btn-small" data-feedback="correct">${escapeHtml(CropGuardI18n.t("feedback.yes"))}</button>
        <button class="btn btn-secondary btn-small" data-feedback="incorrect">${escapeHtml(CropGuardI18n.t("feedback.no"))}</button>
      </div>
      <div class="feedback-correction" id="feedbackCorrection" hidden>
        <label for="correctedDiseaseInput">What is it actually? (optional)</label>
        <input type="text" id="correctedDiseaseInput" placeholder="e.g. Early blight" />
        <button class="btn btn-primary btn-small" id="submitCorrectionBtn" type="button">Submit correction</button>
      </div>
      <p class="feedback-thanks" id="feedbackThanks" hidden>${escapeHtml(CropGuardI18n.t("feedback.thanks"))}</p>
    </div>
  `;

  const panelToggle = content.querySelector(".model-panel-toggle");
  if (panelToggle) {
    panelToggle.addEventListener("click", () => {
      const list = content.querySelector(".model-panel-list");
      const isOpen = panelToggle.getAttribute("aria-expanded") === "true";
      panelToggle.setAttribute("aria-expanded", String(!isOpen));
      list.hidden = isOpen;
    });
  }

  content.querySelectorAll("[data-remedy]").forEach((btn) => {
    btn.addEventListener("click", () => {
      content.querySelectorAll("[data-remedy]").forEach((b) => b.classList.remove("active"));
      content.querySelectorAll("[data-remedy-panel]").forEach((p) => p.classList.remove("active"));
      btn.classList.add("active");
      content.querySelector(`[data-remedy-panel="${btn.dataset.remedy}"]`).classList.add("active");
    });
  });

  document.getElementById("readAloudBtn").addEventListener("click", () => {
    const summary = `${result.diseaseName} on ${result.cropName}. Confidence ${Math.round(result.confidencePercent)} percent. Severity ${Math.round(result.severityPercent)} percent.`;
    CropGuardI18n.speak(summary);
  });

  wireFeedbackForm(content, result);
}

// ---------------------------------------------------------------------------
// Feedback / correction loop (Tier 1 upgrade #2)
// ---------------------------------------------------------------------------
function wireFeedbackForm(content, result) {
  const correctionBlock = document.getElementById("feedbackCorrection");
  const thanks = document.getElementById("feedbackThanks");
  const feedbackButtons = document.getElementById("feedbackBlock").querySelectorAll("[data-feedback]");

  async function submitFeedback(wasCorrect, correctedDiseaseName) {
    try {
      await fetch(`${API_BASE_URL}/api/v1/feedback`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          scanId: result.scanId,
          originalCropName: result.cropName,
          originalDiseaseName: result.diseaseName,
          originalConfidencePercent: result.confidencePercent,
          wasCorrect,
          correctedDiseaseName: correctedDiseaseName || null,
        }),
      });
    } catch (err) {
      console.warn("Could not submit feedback (will not block the UI):", err);
    }
    feedbackButtons.forEach((b) => (b.hidden = true));
    correctionBlock.hidden = true;
    thanks.hidden = false;
  }

  feedbackButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      if (btn.dataset.feedback === "correct") {
        submitFeedback(true, null);
      } else {
        correctionBlock.hidden = false;
      }
    });
  });

  document.getElementById("submitCorrectionBtn").addEventListener("click", () => {
    const corrected = document.getElementById("correctedDiseaseInput").value.trim();
    submitFeedback(false, corrected || null);
  });
}

// ---------------------------------------------------------------------------
// Offline estimate rendering (Tier 2 upgrade #4)
// ---------------------------------------------------------------------------
function renderOfflineEstimate(estimate) {
  document.getElementById("resultsEmpty").hidden = true;
  const content = document.getElementById("resultsContent");
  content.hidden = false;

  // Honest "no diagnosis available" path — no on-device model installed.
  // confidenceLevel/cropName/diseaseName are null in this case; do not
  // render a crop/disease header with nothing behind it.
  if (estimate.confidenceLevel === "ANALYSIS_UNAVAILABLE") {
    content.innerHTML = `
      <div class="uncertainty-banner">
        <h4>Offline — analysis unavailable</h4>
        <p>${escapeHtml(estimate.note)}</p>
      </div>
      <p style="margin-top:12px;">This scan is saved and will be sent for a real diagnosis automatically once you're back online.</p>
    `;
    return;
  }

  // A genuine on-device model result (see onnx-classifier.js) — real
  // inference, just running locally instead of against the backend panel.
  content.innerHTML = `
    <div class="uncertainty-banner">
      <h4>Offline — on-device diagnosis</h4>
      <p>${escapeHtml(estimate.note || "Diagnosed on-device while offline. This will be double-checked against the full model panel once you're back online.")}</p>
    </div>
    <div class="result-header">
      <div>
        <p class="result-crop">${escapeHtml(estimate.cropName)}</p>
        <h3 class="result-disease">${escapeHtml(estimate.diseaseName)}</h3>
      </div>
    </div>
    <div class="meter-block">
      <div class="meter-label"><span>On-device confidence</span><span>${estimate.confidencePercent.toFixed(1)}%</span></div>
      <div class="meter-track"><div class="meter-fill ${severityClass(estimate.confidencePercent)}" style="width:${estimate.confidencePercent}%"></div></div>
    </div>
    <p style="margin-top:12px;">This scan is saved and will be sent for a full diagnosis automatically once you're back online.</p>
  `;
}

// ---------------------------------------------------------------------------
// Weather-integrated risk alerts (Tier 3 upgrade #8)
// ---------------------------------------------------------------------------
async function loadWeatherRisk(latitude, longitude) {
  const widget = document.getElementById("weatherWidget");
  // The Home page no longer renders a weather section (FOLIX Home is hero /
  // how-it-works / scanner only). Skip quietly when the container is absent so
  // a later stage can re-home this without touching the scan flow.
  if (!widget) return;
  widget.innerHTML = "<p>Loading forecast…</p>";
  try {
    const response = await fetch(`${API_BASE_URL}/api/v1/weather-risk?latitude=${latitude}&longitude=${longitude}`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const days = await response.json();
    widget.innerHTML = days.map((d) => `
      <div class="weather-day weather-${d.riskLevel.toLowerCase()}">
        <h5>${escapeHtml(d.date)}</h5>
        <p>${d.avgTemperatureCelsius}°C · ${d.avgHumidityPercent}% humidity · ${d.totalPrecipitationMm}mm rain</p>
        <p class="weather-level">${escapeHtml(d.riskLevel)} risk</p>
        <p class="weather-explanation">${escapeHtml(d.explanation)}</p>
      </div>`).join("");
  } catch (err) {
    widget.innerHTML = `<p class="weather-empty">Couldn't load the weather forecast right now (${escapeHtml(err.message)}).</p>`;
  }
}

// ---------------------------------------------------------------------------
// Treatment reminders (Tier 3 upgrade #10)
// ---------------------------------------------------------------------------
async function loadReminders(scanId) {
  const list = document.getElementById("remindersList");
  if (!list) return; // No reminders container on the current page (see loadWeatherRisk note).
  try {
    const response = await fetch(`${API_BASE_URL}/api/v1/scans/${scanId}/reminders`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const reminders = await response.json();
    if (reminders.length === 0) return;
    const remindersEmpty = document.getElementById("remindersEmpty");
    if (remindersEmpty) remindersEmpty.hidden = true;
    reminders.forEach((r) => {
      const due = new Date(r.dueAt);
      const card = document.createElement("div");
      card.className = "reminder-card" + (r.dueNow ? " reminder-due" : "");
      card.innerHTML = `
        <div>
          <h5>${escapeHtml(r.remedyName)}</h5>
          <p>${escapeHtml(r.type.replace(/_/g, " "))} · due ${due.toLocaleDateString()}</p>
        </div>
        <button class="btn btn-ghost btn-small" data-complete="${r.id}">Mark done</button>`;
      list.appendChild(card);
      card.querySelector("[data-complete]").addEventListener("click", async (e) => {
        e.target.disabled = true;
        try {
          await fetch(`${API_BASE_URL}/api/v1/reminders/${r.id}/complete`, { method: "PATCH" });
          card.style.opacity = "0.5";
          e.target.textContent = "Done";
        } catch {
          e.target.disabled = false;
        }
      });
    });
  } catch (err) {
    console.warn("Could not load reminders:", err);
  }
}

function addToHistory(result, imageUrl) {
  state.history.unshift({ result, imageUrl });
  const historyList = document.getElementById("historyList");
  if (!historyList) return; // No history container on the current page; state.history still records the scan.
  const historyEmpty = document.getElementById("historyEmpty");
  if (historyEmpty) historyEmpty.hidden = true;

  const card = document.createElement("div");
  card.className = "history-card";
  card.innerHTML = `
    ${imageUrl ? `<img src="${imageUrl}" alt="${escapeHtml(result.diseaseName)}" />` : ""}
    <div class="history-card-body">
      <h4>${escapeHtml(result.diseaseName)}</h4>
      <p>${escapeHtml(result.cropName)} · severity ${result.severityPercent.toFixed(0)}% ${result.severe ? "· escalated" : ""}</p>
    </div>`;
  historyList.prepend(card);
}

function escapeHtml(str) {
  const div = document.createElement("div");
  div.textContent = str == null ? "" : String(str);
  return div.innerHTML;
}
