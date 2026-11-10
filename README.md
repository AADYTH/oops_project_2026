# CropGuard — Crop Disease Scanner, Diagnosis & Emergency Escalation

A full-stack application that diagnoses plant diseases from a photo of **any
plant part** (leaf, stem, fruit, flower, root), recommends organic and
chemical remedies, and automatically alerts the nearest agricultural
authority when a result is severe.

```
cropscanner/
├── backend/              Spring Boot (Java 21) REST API — orchestration, remedies, authority lookup, webhook alerts
├── inference-service/    Python FastAPI — real image classification + real OpenCV severity scoring
├── frontend/             Plain HTML/CSS/JS web app — upload, camera capture, GPS, results UI
└── docker-compose.yml    Wires everything together with Postgres
```

## Running it

```bash
docker compose up --build
```

- Frontend: http://localhost:5173
- Backend API: http://localhost:8080 (health check: `/api/v1/scans/health`)
- Inference service: http://localhost:8000 (health check: `/health`, interactive docs at `/docs`)

First run will download the pretrained model (a few hundred MB) inside the
`inference-service` container — this can take a few minutes.

For local development without Docker: run `mvn spring-boot:run` in
`backend/` (uses an in-memory H2 database by default, zero setup), `uvicorn
main:app --reload` in `inference-service/`, and open `frontend/index.html`
directly or serve it with any static file server.

---

## Read this before you tell anyone it's "production-ready"

You asked for zero mock data and 80-90% accuracy. Here's the honest state of
each piece, because shipping this to real farmers with the wrong
expectations is worse than not shipping it at all.

### 1. Disease classification — real, but not yet validated at 80-90% for your crops

The inference service loads a genuine pretrained Hugging Face image
classifier (not a stub) and returns a real prediction with a real confidence
score. What it does **not** do is guarantee 80-90% real-world accuracy out
of the box, because:

- Public pretrained plant-disease models are mostly trained on datasets like
  **PlantVillage**: clean, single-leaf, plain-background lab photos. A
  farmer's phone photo of a stem or fruit in a cluttered field, at an angle,
  in harsh sunlight, looks nothing like that training data. Accuracy drops
  significantly on "field" images versus "lab" images — this is a
  well-documented gap in the plant-pathology ML literature, not a bug in
  this code. The default checkpoint (`linkanjarad/mobilenet_v2_1.0_224-
  plant-disease-identification`) reports only **~78.6% accuracy on its own
  held-out PlantVillage leaf images** — before any field-condition gap is
  even factored in. If you go shopping for a "better" model, check what
  test set any advertised accuracy came from first: several public repos
  report 97-99%+ on a validation split of the *same* clean dataset they
  trained on, which says nothing about a real garden photo. Use
  `inference-service/scripts/check_model_compat.py` to confirm a candidate
  MODEL_ID actually loads via `transformers.pipeline(...)` before switching
  — some Hub repos with impressive READMEs are raw PyTorch `.pth`
  checkpoints that are **not** drop-in compatible with the `pipeline()` call
  this service uses and need custom loading code instead.
- **Root, stem, fruit, and flower/whole-plant photos are outside what this
  classifier was trained on at all** — PlantVillage is leaf-only, so there
  is zero ground truth behind a prediction on any other plant part. The UI
  lets a user select one of those, and until this pass the same leaf
  classifier ran on it anyway and returned a confident-looking crop/disease
  guess with nothing real behind it — reported as "crop detection isn't
  happening for parts." As of this pass, the inference service flags this
  explicitly: a `plant_part_hint` other than `LEAF`/`UNKNOWN` still gets a
  best-effort guess (so the flow doesn't dead-end for a user who only has
  that photo), but `confidence_level` is forced to `LOW` and a
  `part_warning` message is returned regardless of the model's raw score;
  the frontend shows a dedicated "this photo type isn't reliably supported
  yet" banner instead of presenting it like a normal diagnosis. That's a
  real fix for the *silently-wrong* failure mode — it is **not** a fix for
  part-level accuracy itself, which still needs a model actually trained on
  stem/fruit/root images (PlantVillage doesn't have any).
- "80-90% accuracy" is meaningless without saying accuracy *on what test
  set*. You must define your target crops, collect a real held-out set of
  photos representative of what your users will actually submit, and
  measure accuracy yourself before making that claim to users.

**What to do about it:** fine-tune a classifier (start from a ViT or
EfficientNet backbone) on your target crops using PlantVillage/PlantDoc as a
base and — this part matters most — your own field-condition photos on top.
Swap the model via the `MODEL_ID` environment variable on the
`inference-service`; no other code changes needed.

### 2. Severity scoring — real computation, heuristic-level accuracy

Severity is computed from actual pixel analysis (HSV color thresholding to
estimate necrotic vs. healthy tissue area), not a random or fabricated
number. It is a classic, explainable technique used in plant-pathology
image tools — but it's a color heuristic, not a trained segmentation model.
It will misjudge severity on: naturally red/purple/variegated leaves,
very dark or shadowed photos, and backgrounds that are green (e.g. a grassy
field behind the plant). For materially better severity accuracy, replace
`estimate_severity_percent()` with a U-Net (or similar) semantic-segmentation
model trained on pixel-labelled lesion masks.

### 3. Authority directory — real source, incomplete seed data

The seed data in `backend/src/main/resources/data/kvk_directory_seed.csv` is
a real subset (~20 rows) of ICAR's published Krishi Vigyan Kendra directory
(source: icar.org.in), not invented data. Two important gaps to close before
relying on this for real alerts:

- **No official machine-readable directory exists.** ICAR publishes the KVK
  list as static web pages, not an API. You (or a script) need to transcribe
  the full official list — several hundred entries — into the same CSV
  format to get national coverage. The importer geocodes addresses via
  OpenStreetMap Nominatim automatically on startup, so you only need to
  supply the addresses, not coordinates.
- **No government office in this space runs a public webhook receiver.**
  The `webhookUrl` field is empty for every seeded row because that's the
  truth — these offices don't have one. `WebhookNotificationService` is
  real, functional code that will fire correctly the moment you configure a
  URL (a ticketing system's inbound webhook, a Slack/Teams channel, or an
  intake API you build for the authority to opt into), but until an office
  actually has such an endpoint, escalation for that office will correctly
  report `NOTIFICATION_FAILED` / "contact them directly by phone or email"
  rather than silently pretending it succeeded.

### 4. Everything else in this repo is fully functional, no placeholders

- The REST API, database persistence, GPS-based nearest-authority lookup
  (Haversine distance over a real bounding-box query), the remedy knowledge
  base (curated from standard agricultural-extension guidance, not
  generated per-request), the async webhook dispatch via
  `java.net.http.HttpClient`, and the entire frontend (upload, live camera
  capture, geolocation, session history) all run for real against each
  other with no mocked responses anywhere in the request path.

---

## Downsides, limitations & risks

- **Accuracy is bounded by dataset match, not by this codebase.** See
  section 1 above — this is the single biggest risk to calling the product
  "done."
- **No confidence gating in the UI yet.** A low-confidence prediction (say,
  40%) is displayed with the same visual weight as a 95% one; a user could
  act on a shaky diagnosis. Add a "low confidence — get a second opinion"
  state before this reaches real farmers.
- **Non-leaf photos are gated, but still unsupported.** The part-warning
  banner (see section 1) stops a stem/fruit/root/flower/whole-plant photo
  from looking like a confirmed diagnosis, but it doesn't make the
  underlying guess any more accurate — there's still no model in this
  pipeline that was ever trained on those plant parts. Real support for them
  requires collecting and fine-tuning on non-leaf field photos, not a
  bigger/better leaf classifier.
- **False negatives on severity are the dangerous failure mode.** If the
  color-heuristic severity scorer under-counts a real outbreak (e.g. a
  disease that doesn't brown/yellow the tissue visibly), a genuinely severe
  case could fail to escalate. Combine the model's own epidemic-risk flag
  (independent of the severity number) with periodic manual QA of the
  severity heuristic on real photos.
- **Escalation coverage is only as good as the imported directory.**
  Outside the ~20 seeded districts, `NoAuthorityFound` is the honest result
  — there is no invented fallback authority.
- **No authentication, rate limiting, or abuse protection yet.** Anyone can
  hit the scan endpoint; add auth (even lightweight API keys per client) and
  request throttling before public deployment, especially since each scan
  costs real GPU/CPU inference time.
- **No image storage/audit trail.** Scans aren't persisted with their source
  image today, so there's no way to review a past diagnosis against the
  original photo, dispute a result, or build a retraining dataset from real
  user submissions (with consent) — a missed opportunity for improving
  accuracy over time.
- **Single-language, single-country directory.** UI text and the authority
  directory both assume English/India today.
- **Chemical remedy advice is general label-rate guidance, not a
  prescription.** Real dosing must always be confirmed against the specific
  product label and local regulations — this is flagged in the UI copy but
  worth repeating operationally.
- **Nominatim geocoding is rate-limited to ~1 request/second** and is meant
  for light use; a national-scale directory import needs a paid geocoding
  provider or a self-hosted Nominatim instance.
- **No offline mode.** Farmers in low-connectivity areas can't use this at
  all today; the whole pipeline requires a live connection to both the
  backend and the inference service.

## Suggested upgrades (roughly in priority order)

1. **Confidence-gated UI**: below a configurable threshold, show "uncertain
   — here are your 3 closest matches" instead of a single confident-looking
   answer.
2. **Field-condition fine-tuning pipeline**: a labelled-upload flow so
   agronomists can correct wrong diagnoses, feeding a growing, consented
   dataset for periodic model retraining.
3. **Offline-first PWA mode**: cache a lightweight on-device model
   (TensorFlow Lite / ONNX Runtime Web) for a "best guess offline, full
   diagnosis when back online" experience — critical for rural connectivity.
4. **SMS/USSD fallback channel** for both farmers (no smartphone) and
   authorities (no webhook) — this is probably the single highest-impact
   feature for real-world adoption in the regions this app targets.
5. **Treatment tracking & reminders**: push/SMS reminders for reapplication
   schedules and pre-harvest-interval countdowns pulled straight from the
   remedy plan already in the data model.
6. **Outbreak heat map**: aggregate anonymized, geotagged severe scans on a
   map so authorities can see clustering, not just one-off farmer alerts —
   turns individual scans into regional early-warning data.
7. **Weather-based risk alerts**: cross-reference forecast humidity/rainfall
   with known disease-favorable conditions (e.g. late blight thrives in
   cool, wet weather) to proactively warn farmers before symptoms appear.
8. **Multi-language & voice input/output** for accessibility in low-literacy
   contexts — arguably more impactful than any model-accuracy improvement.
9. **Authority-side dashboard**: a simple web view for KVK staff to see
   incoming alerts, mark them acknowledged/resolved, and register their own
   webhook/contact details themselves rather than needing a CSV update.
10. **Image quality pre-check**: reject/flag blurry, too-dark, or too-far
    photos client-side before spending an inference call on an image the
    model can't use well.

---

## Implementation status of the upgrades above (as of this pass)

Everything below was actually implemented in this codebase — not just described — with one hard constraint: this environment has no network access, so **none of it has been compiled, run, or tested**. Run `mvn clean install` and start both services before trusting any of it in front of a real user.

### Done, and wired end-to-end
- **Plant-part gating (bugfix, later superseded)** — non-leaf `plant_part_hint` values
  originally forced `confidence_level` to `LOW` and returned a `part_warning`
  rather than fixing the underlying problem. The "Free-tier multi-key AI
  architecture pass" section further down replaced this with real
  pixel-evidence leaf detection that rejects non-leaf photos outright
  instead of guessing at them — see that section for the current behavior.
- **Confidence gating** (#1) — inference service returns `confidence_level` + top-2 alternatives; backend/DTOs/frontend all show an "uncertain" banner with runner-up guesses below the LOW threshold.
- **Feedback/correction loop** (#2, partial) — `POST /api/v1/feedback` persists every correction to `scan_feedback`. This collects a labelled dataset; it does **not** automatically retrain the model — that remains a manual, periodic export/fine-tune step you run yourselves.
- **API auth + rate limiting** (serious fix) — per-IP rate limiting always on; optional API-key requirement, off by default so the public client keeps working.
- **SMS fallback** (#4/#5, renumbered from the plan) — `SmsGatewayService` with a log-only default (works with zero setup, fully auditable) and a real Twilio implementation gated behind three env vars.
- **Weather risk alerts** (#7) — live against the free Open-Meteo API, no key needed. Rules are simplified agronomy heuristics, not a calibrated forecasting model — see the Javadoc caveat in `WeatherRiskService`.
- **Outbreak heat map** (#6) — k-anonymity grid aggregation; cells with fewer than 3 scans are suppressed entirely rather than shown with an exact, individually-identifiable count.
- **Authority dashboard & self-registration** (#9) — offices can register themselves, get an API key, and acknowledge/resolve their own alerts instead of you hand-maintaining the CSV forever.
- **Treatment reminders** (#5, renumbered) — reapplication and pre-harvest-interval dates parsed out of the existing remedy text and turned into due-dated reminders.
- **Image quality pre-check** (#10) — implemented **twice**: a fast client-side canvas check (so a bad photo never even gets uploaded) and a server-side OpenCV check in the Python service (so a modified client can't skip it).
- **Offline-first PWA shell** (#3, partial) — service worker caches the app shell so the UI opens with zero connectivity; an IndexedDB queue holds scans taken offline and auto-resubmits them the moment connectivity returns.
- **Multi-language + voice** (#8) — English/Hindi/Malayalam for interactive UI strings (not the long-form marketing copy — that needs a human translator, not a bot, before it reaches real farmers); voice input/output via the browser's Web Speech API.

### Explicitly NOT done, and why
- **Real classifier accuracy on your specific crops.** No code change fixes this. It requires real, labeled field photos — the feedback loop above is the mechanism for *collecting* that data, not a substitute for it.
- **USSD (feature-phone) delivery.** The SMS path is real; USSD needs a telco/aggregator contract (e.g. Africa's Talking) that's a business relationship, not a code change — the `SmsGatewayService` interface is deliberately generic so a USSD gateway can implement it the same way Twilio does.
- **Production-grade rate limiting at scale.** The in-memory limiter is per-instance; a multi-instance deployment needs a shared store (Redis + Bucket4j), not implemented here to avoid pulling in a dependency this offline environment couldn't fetch or verify.

---

## Free-tier multi-key AI architecture pass (Gemini second opinion, leaf-only gating, offline honesty)

A second pass rebuilt several pieces to match a stricter architecture spec: one dedicated local model, Gemini as an optional free-tier second opinion with multi-key failover, a genuinely leaf-only scanner, and no fabricated diagnosis anywhere (online or offline). As with the pass above, **this environment has no network access to Maven Central, Hugging Face, or Google's Gemini API** — none of the Java changes have been compiled, and neither the benchmarking nor the Gemini call has actually been executed. Compile (`mvn clean install`) and smoke-test all of this yourself before trusting it.

### 1. Final selected ML model
**Not re-selected — still `linkanjarad/mobilenet_v2_1.0_224-plant-disease-identification`, unchanged from before.** The architecture spec requires picking one primary model via real benchmarking (accuracy, macro-F1, per-class, confusion matrix, real-world performance, latency, size — see `inference-service/scripts/benchmark_models.py`, which is real, runnable code). That benchmark was **not run**, because this sandbox's network allow-list covers only pypi/npm/github-style package registries, not huggingface.co or dataset mirrors — there was no way to download either a candidate checkpoint or a labelled PlantVillage/PlantDoc test split to score it against. Run `benchmark_models.py` yourself with real datasets before treating any specific model as "the winner"; don't take this default as a benchmarked choice.

### 2. "LeafVision" investigation
A web search turned up **no single, canonical, verifiable "LeafVision" plant-disease model or paper** — only a scatter of small, unrelated, similarly-named community projects (a few hundred-image Roboflow "leaf disease detection" datasets, etc.), none authoritative enough to responsibly add as a benchmark candidate. Nothing in this codebase is described as "LeafVision." If you have a specific repo/paper/model ID in mind, add it to `CANDIDATE_MODELS`/`DEFAULT_MODEL_SPECS` in `ensemble.py` and `benchmark_models.py` will score it like any other candidate.

### 3. Ensemble default flipped to OFF
`ENSEMBLE_ENABLED` now defaults to `false` (was `true`). The architecture spec's rule is "one model unless benchmarking shows a real, repeatable improvement" — and since no benchmarking was actually run in this pass (see #1), the honest default is one model, not four. The 4-model panel code is untouched and still available behind `ENSEMBLE_ENABLED=true` for anyone who runs the benchmark and finds it genuinely helps.

### 4. Leaf-only gating — now real, not a warning label
Previously, a non-leaf photo (stem/fruit/root/flower/whole-plant/random object) was still run through the classifier and returned a guess, just forced to `LOW` confidence with a warning attached. That's gone. `inference-service/leaf_validator.py` does real pixel-evidence analysis (HSV plant-color coverage + contour shape/fill-ratio) **before** the classifier ever runs; a photo that doesn't clear it gets `NO_LEAF_DETECTED` (HTTP 422) and **no disease is fabricated at all** — wired through a new `NoLeafDetectedException` in the Java backend, distinct from the existing image-quality rejection path. This is a classical CV heuristic, not a trained leaf detector — see that file's own docstring for exactly what it can and can't reliably tell apart, and it's deliberately tuned lenient (per the "real-world image handling" section below) so it doesn't over-reject genuine blurry/shadowed/damaged farmer photos.

### 5. Gemini free-tier second opinion, with multi-key failover
Implemented in the Java backend (keys never touch the frontend):
- `GeminiKeyPool` — reads keys from `GEMINI_API_KEYS` (comma-separated) or `GEMINI_KEY_1..GEMINI_KEY_20`, environment variables only. Round-robins across keys that aren't in cooldown; a 429/403/401/5xx response puts a key into exponential backoff (30s base, capped at 15 min by default) rather than retrying it in a loop. Logs are masked ("Gemini key 2 → temporarily unavailable") and never print a credential.
- `RestGeminiVisionService` — one real HTTPS call per attempt to Gemini's `generateContent` endpoint (`gemini-3.1-flash-lite` by default — **verify current free-tier eligibility at ai.google.dev before relying on this**, Google's lineup changes and the model wasn't network-reachable to verify from here), asking it to independently identify the crop/disease and describe visible symptoms. One attempt per configured key per scan, never more; if every key is exhausted, returns "unavailable" rather than throwing, upgrading to a paid model, or creating a new key/account.
- `EvidenceConfidenceService` — the only place Gemini's answer touches the final result: agreement with the local model can bump the confidence tier up one level (capped at HIGH), disagreement drops it one level (floored at LOW), and "unavailable" leaves the local confidence untouched. Gemini **never** changes `cropName`/`diseaseName`/the remedy plan.
- Off by default (`cropscanner.gemini.enabled=false`), matching "no paid dependency, must work with zero Gemini access." Enabling it with zero keys configured just logs a warning and runs on the local model — it never auto-creates credentials or falls back to billing.
- **Not actually called against the real Gemini API from this environment** — no network path to generativelanguage.googleapis.com here. The HTTP/JSON handling follows Gemini's documented REST contract but hasn't been exercised against a live response; test it against your own key before depending on it.

### 6. Offline mode — the color heuristic is gone
The previous offline fallback classified "green vs. yellow-brown pixel ratio" as a disease guess, labelled "rough estimate." That was exactly the color-heuristic-as-diagnosis pattern this architecture explicitly forbids, so it's been removed. Offline mode now either runs a **genuine** on-device ONNX model (if you've exported and installed one — see `onnx-classifier.js` and `inference-service/scripts/export_onnx_model.py`, both real, working code) or honestly reports "analysis unavailable, queued for when you're back online" — never a fabricated guess. No trained on-device model ships with this repo; exporting one is a you-run-this step requiring the network access this sandbox doesn't have (see #1).

### 7. Known limitations after this pass
- Primary model selection, LeafVision, and the offline ONNX export are all real, runnable tooling with no actual run behind them yet (see #1, #2, #6) — don't repeat a "final model selected"/"offline diagnosis works" claim without running them first.
- The Gemini integration is unexercised against the live API (see #5).
- The leaf validator is a color/shape heuristic, not a trained detector — it can be fooled by, e.g., a large patch of green fabric, and may occasionally reject a genuine leaf photographed at a very unusual angle or extreme close-up crop. Tune its thresholds (top of `leaf_validator.py`) against real submitted photos over time.
- None of the Java changes in this pass have been compiled (`mvn`) — this sandbox has no route to Maven Central. Build and run the test suite before deploying.


---

## FOLIX Scanner — Stage 4: Gemini Vision analysis (+ Pl@ntNet online fallback, + offline on-device queue)

The new Scanner (`frontend/scanner.html`) diagnoses through **Gemini Vision** as the primary model. There is still no OpenCV scoring and the backend never fabricates a diagnosis, but there are now two honest fallbacks, each clearly labelled to the farmer so nothing is ever passed off as a full Gemini diagnosis it isn't:

- **Online, backend-side:** if the single Gemini request itself fails (rate limit, invalid key, timeout, 5xx) *and* the backend is reachable, `FolixScanService` makes exactly one follow-up request to Pl@ntNet's disease-identification API and maps its response into the same result shape Gemini produces (see "Pl@ntNet online fallback" below).
- **Offline, browser-side:** if the device itself has no network, `scanner-experience.js` queues the photo locally (IndexedDB) and, if a small ONNX model happens to be installed, shows an on-device guess clearly labelled "Provisional result — not yet confirmed"; otherwise it shows an honest "queued, will confirm once you're back online" card. Either way, the queued photo is resent to `/api/scan/analyze` automatically once the browser comes back online, and any provisional card is silently replaced with the real, confirmed result. This path never touches the backend's Pl@ntNet fallback and is entirely separate from it.

```
browser (downsizes photo, strips EXIF) ──POST /api/scan/analyze──▶ backend
   backend: validate + strip metadata ▶ ONE Gemini request ▶ validate/normalize ▶ FOLIX result
                                              │
                                              └─ Gemini itself fails ▶ ONE Pl@ntNet request ▶ map ▶ FOLIX result
browser: renderDiagnosis(result)  or  renderUncertain(result)  or  error panel
   (offline: queue locally ▶ optional on-device provisional guess ▶ auto-sync + reconcile on reconnect)
```

Code lives in its own package, `backend/src/main/java/com/cropscanner/folix/`. The legacy CropGuard pipeline (`/api/v1/scans`, `inference-service/`, `RemoteMlInferenceService`, `EvidenceConfidenceService`, `RestGeminiVisionService`) is untouched and unused by the Scanner; an architecture test fails the build if a Scanner class imports it.

### Configuration

| What | How |
|---|---|
| API key | **Environment only:** `GEMINI_API_KEYS` (comma-separated) or `GEMINI_KEY_1…20`. Never in the frontend, `application.yml`, or git. |
| Which key | The **first** configured key, always. No rotation, no failover, no retry — Google applies quotas **per project**, so extra keys add no capacity and switching keys on a 429 would be quota circumvention. |
| Model | `FOLIX_GEMINI_MODEL` (default `gemini-3.5-flash-lite`, GA since 2026‑07‑21). For more visual accuracy on a paid project try `gemini-3.6-flash` / `gemini-3.8-flash`. |
| Thinking | `FOLIX_GEMINI_THINKING_LEVEL` — blank by default (model default). Note `minimal` is rejected by 3.7/3.8 Flash. |
| Other | `folix.gemini.*` and `folix.scanner.max-image-bytes` in `application.yml`. |

The Scanner does **not** depend on the legacy `cropscanner.gemini.enabled` switch; it is available exactly when a key is present.

### API

`POST /api/scan/analyze` — multipart, one part named `image` (JPEG/PNG/WebP, ≤ 10 MB). Nothing else is read (no identity, no location).

Success (`200`, never cached):

```json
{ "plant_detected": true, "plant_part": "leaf", "crop": "Tomato", "disease": "Early blight",
  "severity": "moderate", "confidence": "high", "symptoms": ["…"], "reasoning": "…",
  "recommended_actions": ["…"], "needs_human_review": false }
```

Failure: `{"error": {"code": "…", "message": "…", "retryable": true|false}}`, with `code` one of `INVALID_IMAGE` (400), `UNSUPPORTED_IMAGE_TYPE` (415), `IMAGE_TOO_LARGE` (413), `RATE_LIMITED` (429, with `Retry-After` when known), `ANALYSIS_TIMEOUT` (504), `ANALYSIS_UNAVAILABLE` (503: not configured / key rejected / billing / model gone), `ANALYSIS_FAILED` (502: Google 5xx, network, blocked or malformed output). Messages are fixed user-safe sentences: no keys, stack traces, or raw Gemini traffic. This is the error the caller sees when **both** Gemini and (if configured) the Pl@ntNet fallback have failed — see below.

### Pl@ntNet online fallback

Tried only when the Gemini request itself throws (this backend is reachable, Gemini isn't answering) — never as a first choice, and never for the browser's offline case above.

| What | How |
|---|---|
| API key | **Environment only:** `PLANTNET_API_KEY`. Free tier: 500 requests/day. Unset ⇒ the fallback bean is never created and a failed Gemini call surfaces exactly as it did before this fallback existed. |
| Other | `folix.plantnet.*` in `application.yml` (base URL, timeouts, `organs`, `lang`). |

Pl@ntNet's response shape has nothing in common with Gemini's — it returns a bare species/disease label and a numeric match score, no reasoning, no severity, no recommended actions. `PlantNetDiagnosisMapper` converts that into the exact same `DiagnosisResult` contract Gemini's output goes through, so the frontend never has to know which model actually answered — but it does so conservatively in every direction `AssessmentParser` is conservative in: confidence is bucketed from the score but capped at `moderate` (there's no reasoning behind the number, so it's never reported as `high`), `symptoms`/`recommended_actions` are empty, `crop` is `unknown`, `needs_human_review` is always `true`, `reasoning` is a fixed sentence stating plainly that the result came from the Pl@ntNet fallback rather than Gemini, and an empty results list is treated as a failure (never a fabricated answer). If Pl@ntNet also fails or isn't configured, the **original** Gemini error is what the caller sees — never a confusing "the fallback failed" error.

In `docker-compose.yml`, forward the key the same way the Gemini keys are forwarded: `PLANTNET_API_KEY: ${PLANTNET_API_KEY:-}` under the `backend` service's `environment:` block (already set up in this repo — just export `PLANTNET_API_KEY` in your shell or an untracked `.env` file).

### How uncertainty is preserved

The model is told that `unknown` and "ask a human" are correct answers, and the schema makes it write symptoms and reasoning *before* the verdict. Then the server does not trust it (`AssessmentParser`): missing/wrong-typed fields are an error, not a guess, and normalization can only move **toward** uncertainty — no plant ⇒ everything forced to unknown/low/needs-review; unknown disease ⇒ confidence low + review; low confidence ⇒ review; an out-of-set `confidence` is an error. Recommended actions containing doses, mixing ratios, spray schedules or "legally required" claims are dropped, product mentions are labelled as general AI guidance, and a "confirm with local agricultural guidance" action is always present (`ActionSafetyPolicy`).

### Privacy — read this before going live

- Photos are held in memory only, never stored or logged; EXIF/GPS/XMP/comments are stripped in the browser **and** again on the server (`ImageSanitizer`).
- **On Google's unpaid quota, Google may use submitted content (including images) to improve its products and human reviewers may read it** (Gemini API Additional Terms). Paid-quota requests are not used for that. Use a billing-enabled project for real farmer photos.

### Tests

```bash
cd backend && mvn test                         # JUnit 5 (spring-boot-starter-test, no new deps)
npm i --no-save playwright && npx playwright install chromium
node --test tests/scanner-frontend/scanner.test.js   # real Chromium + mock backend
```

### Honest limitations of this pass

- **No live Gemini call was made** (no network from the build environment). The request/response wire format follows Google's current `generateContent` docs, but run one real scan with your key before relying on it. If Google retires `generateContent`, `GeminiRestVisionClient` is the single class to change.
- The backend was not built with Maven here; the Java sources and tests were compiled and run against small API-compatible stand-ins for Jackson/Spring (77 tests). `FolixScanControllerTest` (Spring MockMvc) needs the real Spring test jars and has **not** been run — run `mvn test`.
- Accuracy of Gemini's crop diagnoses has not been measured against labelled data; treat results as an aid, not a lab diagnosis.


## FOLIX farmer accounts — Stage 6A: authentication foundation

Farmers can register, log in and log out; the server always knows *which farmer* is making a request.
This stage is the foundation only — saved reports were tied to a farmer in Stage 6B (below), and the legacy
Authority Dashboard is a separate portal that this change does not touch.

**How it works.** Login creates a random 256-bit token, returned only in an `HttpOnly` cookie (`FOLIX_SESSION`,
`Path=/api`, `SameSite=Lax`, `Secure` over HTTPS). The database stores only the token's SHA-256 hash
(`folix_farmer_session`), so logout is real revocation and a leaked table cannot be replayed. Passwords are
BCrypt-hashed (`folix_farmer.password_hash`); no API response ever contains a password, hash or token. The browser
never sends a user id — the server derives identity from the cookie (`AuthenticatedFarmer`). Nothing is kept in
`localStorage`. State-changing requests are additionally checked against the configured frontend origin (CSRF).

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /api/auth/register` | public | 201 profile · 400 field errors · 409 duplicate email. Does not log in. |
| `POST /api/auth/login` | public | 200 profile + `Set-Cookie` · 401 generic "Incorrect email or password." |
| `POST /api/auth/logout` | public | 204, revokes the session, clears the cookie. Idempotent. |
| `GET /api/auth/session` | public | Always 200: `{authenticated, farmer}` (used by the header). |
| `GET /api/farmer/profile` | **farmer** | id, name, email, createdAt. |
| `GET/POST /api/scan/reports`, `/summary`, `/{id}` | **farmer** | The Dashboard and Save Report. **Behaviour change:** saving/reading reports now requires login. |
| `POST /api/scan/analyze`, `/api/v1/**`, authority endpoints | unchanged | Scanning still works without an account. |

**Configuration** (`folix.auth.*` in `application.yml`): `session-ttl-hours` (168), `cookie-secure`,
`cookie-same-site` (`Lax`; use `None` + `cookie-secure=true` only if the frontend and API are on different
registrable domains), `bcrypt-strength` (12). The allowed browser origin is `cropscanner.cors.allowed-origin`.
The `prod` profile turns `Secure` on; `docker-compose.yml` turns it off for plain-http local use
(`FOLIX_AUTH_COOKIE_SECURE`). **Production runs `ddl-auto: validate`: run `backend/db/stage6a-farmer-auth.sql` first.**

**Frontend.** `login.html`, `register.html`, `profile.html`; `auth.js` adds a Log in / name + Log out control to every
farmer page (not the Authority Dashboard). The Dashboard shows a "log in" state when logged out or expired; in the
Scanner, "Save Report" explains that login is needed and opens login in a new tab so the diagnosis isn't lost.

**Tests.** Backend JUnit: `backend/src/test/java/com/cropscanner/folix/auth/` (`mvn test`). Frontend (real Chromium):
`node --test tests/auth-frontend/auth.test.js` (plus the updated scanner/dashboard suites).

**Known limits.** No email verification, password reset or "log out everywhere" yet. Login attempts are limited only by
the existing per-IP limiter (`ApiProtectionFilter`), not per account. The header/login strings are English-only
(not wired into `i18n.js`).


## FOLIX farmer accounts — Stage 6B: user-owned scan history

Saved scan reports now **belong to the farmer who saved them**, and a farmer can only ever see their own history.

**How ownership works.** `folix_scan_report.farmer_id` is a foreign key to `folix_farmer` (`FolixScanReportEntity.farmer`,
a `@ManyToOne`). On `POST /api/scan/reports` the owner is the `AuthenticatedFarmer` that `AuthenticatedFarmerArgumentResolver`
builds from the server-side session cookie — the request body (`FolixReportRequest`) has no owner field, and any
`farmerId`/`owner`/header/query value a client adds is ignored. The owner is set once and cannot be updated. It is never
returned by the API: `FolixReportResponse`, `FolixReportPage` and `FolixReportStats` are unchanged.

| Endpoint | Behaviour now |
|---|---|
| `POST /api/scan/reports` | Saves under the logged-in farmer. No session → 401, nothing is created (no anonymous reports). |
| `GET /api/scan/reports` | Only the caller's reports, newest first; `page`/`size` unchanged and scoped. |
| `GET /api/scan/reports/summary` | Counts calculated only from the caller's reports. |
| `GET /api/scan/reports/{id}` | 404 unless the caller owns it. Someone else's report gives the *same* 404 as a non-existent id, so its existence is not revealed. |

The unscoped Stage 5 repository queries were removed; the service only uses owner-scoped queries
(`findAllByFarmer_IdOrderByCreatedAtDesc`, `findByIdAndFarmer_Id`, `countByFarmer_Id`, `countByFarmer_IdAndNeedsHumanReviewTrue`),
and `FolixReportOwnershipGuardTest` fails the build if that changes. The legacy CropGuard/authority code does not use the
FOLIX report classes, so the Authority Dashboard is unaffected.

**Database.** Dev (H2 in memory, `ddl-auto: update`) rebuilds itself, so nothing to do. **Production (`ddl-auto: validate`):
run `backend/db/stage6b-report-ownership.sql` after `stage6a-farmer-auth.sql`, before deploying.** Reports saved in Stage 5
have no owner and there is no trustworthy way to infer one, so the script does **not** guess and does **not** delete them:
they keep `farmer_id = NULL`, stay in the table, and are invisible to every farmer. `farmer_id` is made `NOT NULL` only when
no ownerless rows remain; the script's header shows how to count, assign (if you know the owner) or delete them.

**Frontend.** No change: the Scanner already sent only the diagnosis (with the session cookie) and the Dashboard already
handled 401 and failed detail loads. **Tests.** `FolixReportOwnershipHttpTest` (two real accounts, end to end over MockMvc),
`FolixReportServiceTest`, `FolixReportControllerTest`, `FolixScanReportRepositoryTest` (H2), `FolixReportOwnershipGuardTest`.


## FOLIX Disease Map — Stage 6C: explicit location sharing + privacy-preserving regional map

Farmers can **optionally** let their saved reports contribute to a regional Disease Map. The chain is:

`explicit button click → browser permission → coarse region (never exact coordinates) → aggregated, thresholded disease data → Disease Map`

**Explicit permission.** Location is requested in exactly one place: the click handler of *Enable Location for Regional
Disease Insights* (or *Update my area*) in `frontend/location.js`, on the Disease Map page. Nothing asks on page load, on a
timer, from the Scanner or the Dashboard; there is no `watchPosition`/background tracking and no IP-based fallback. Deny (or
a browser without geolocation) leaves everything working: scanning, Save Report, Dashboard. A denial is never retried
automatically; if the browser already reports the permission as blocked, the button is disabled and no request is made.

**What is stored.** The browser rounds the position to 2 decimals and asks at low accuracy; `FolixRegionGrid` on the server
reduces it to the *centre of a grid cell* (default 0.1° ≈ 11 km) in integer millidegrees. Only that cell is stored — in
`folix_farmer_location` (one row per opted-in farmer; no row = "location unavailable") and copied onto reports saved *after*
opt-in (`folix_scan_report.region_lat_mdeg/region_lng_mdeg`, nullable). There is **no exact latitude/longitude column
anywhere**, and the coordinates are never logged. Stage 5/6B reports keep `NULL` and are never back-filled; a report saved
before opting in never gains a location. Withdrawing (*Stop sharing*, `DELETE /api/farmer/location`) deletes the farmer's
region and clears it from their existing reports (the reports themselves are kept), so they leave the map.

**Ownership.** `GET/PUT/DELETE /api/farmer/location` sit under `/api/farmer/**`, already session-protected (401) with the CSRF
Origin check. Whose location it is comes only from the `AuthenticatedFarmer` resolved from the Stage 6A session cookie; the
body (`consent`, `latitude`, `longitude`) has no id field and any extra property is ignored. No second auth mechanism.

**The map API.** `GET /api/folix/disease-map` (public, no parameters, `no-store`) returns only `{lookbackDays, minimumReports,
areaSizeDegrees, regions:[{centerLatitude, centerLongitude, totalReports, diseases:[{crop, disease, reportCount,
severity:{mild,moderate,severe,unknown}}]}]}`. `centerLatitude/Longitude` are the cell centre. No farmer id/name/email, session,
report id or exact coordinate. The Disease Map page now uses this instead of the legacy `/api/v1/outbreaks/heatmap`, which
reads the legacy CropGuard scan table (not FOLIX reports) and is left untouched.

**Privacy rules** (`FolixDiseaseMapAggregator`, a pure function): groups are (region, crop, disease). A group is shown only with
**≥ `min-cases` reports (default 3) from ≥ `min-farmers` different farmers (default 3)** — so one farmer, however many reports,
can never appear alone. Sub-threshold groups are dropped entirely and not counted in region totals. Healthy/unknown and
LOW-confidence diagnoses are excluded. Time basis: only the last `lookback-days` (default 30). `FolixMapSettings` clamps
configuration (`cell-size-millidegrees` 50–1000, `min-cases` ≥ 2, `min-farmers` ≥ 2) so a setting cannot make the map precise
or single-farmer. Configure in `application.yml` under `folix.map` (env: `FOLIX_MAP_MIN_CASES`, `FOLIX_MAP_MIN_FARMERS`,
`FOLIX_MAP_LOOKBACK_DAYS`, `FOLIX_MAP_CELL_SIZE_MILLIDEGREES`).

**Database.** Dev (H2) rebuilds itself. **Production (`ddl-auto: validate`): run `backend/db/stage6c-location-disease-map.sql`
after the 6A and 6B scripts, before deploying.** It only creates `folix_farmer_location`, adds two nullable columns and an
index; it deletes and rewrites nothing and is safe to re-run.

**Tests.** Backend: `FolixRegionGridTest`, `FolixDiseaseMapAggregatorTest`, `FolixDiseaseMapServiceTest`,
`FolixLocationServiceTest`, `FolixLocationHttpTest` (three real accounts over MockMvc), `FolixDiseaseMapRepositoryTest` (H2),
`FolixLocationPrivacyGuardTest`. Frontend (real Chromium): `node --test tests/location-frontend/location.test.js`.
