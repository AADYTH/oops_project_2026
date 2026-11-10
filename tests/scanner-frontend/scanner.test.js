"use strict";
/**
 * Stage 4 Scanner frontend tests — run in a REAL Chromium against the REAL frontend files, with the backend
 * replaced by a local mock (mock-backend.js). Nothing here talks to Gemini.
 *
 *   npm i --no-save playwright          # dev tool only; the app itself has no npm dependencies
 *   npx playwright install chromium     # or set CHROME_PATH to an existing Chrome/Chromium binary
 *   node --test tests/scanner-frontend/scanner.test.js
 */
const { test, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { chromium } = require("playwright");
const { startStatic, startMock, jpegSize, DIAGNOSIS, NON_PLANT, UNKNOWN_DISEASE, SAVED_REPORT } = require("./mock-backend");

const FRONTEND = path.resolve(__dirname, "..", "..", "frontend");

let browser, staticSrv, mock;

before(async () => {
  browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH, args: ["--no-sandbox"] } : { args: ["--no-sandbox"] });
  staticSrv = await startStatic(FRONTEND);
  mock = await startMock();
});
after(async () => {
  await browser?.close();
  await staticSrv?.close();
  await mock?.close();
});
beforeEach(() => mock.reset());

// ------------------------------------------------------------------ helpers

/** Opens the real scanner page wired to the mock backend. Service workers are blocked so nothing is cached. */
async function openScanner(opts = {}) {
  const context = await browser.newContext({ serviceWorkers: "block" });
  const page = await context.newPage();
  const requested = [];
  page.on("request", (r) => requested.push(r.url()));
  const consoleErrors = [];
  page.on("pageerror", (e) => consoleErrors.push(String(e)));
  const base = opts.apiBase || mock.url;
  await page.addInitScript(({ base, timeout }) => {
    window.CROPGUARD_API_BASE_URL = base;
    if (timeout) window.FOLIX_ANALYSIS_TIMEOUT_MS = timeout;
  }, { base, timeout: opts.timeoutMs });
  await page.goto(`${staticSrv.url}/scanner.html`);
  await page.waitForSelector("#scnRoot");
  return { page, context, requested, consoleErrors };
}

const state = (page) => page.getAttribute("#scnRoot", "data-state");
const waitState = (page, s, timeout = 8000) =>
  page.waitForFunction((want) => document.getElementById("scnRoot").dataset.state === want, s, { timeout });

/** Generates a JPEG (no EXIF) of the given size inside the browser. Noise keeps it from compressing to nothing. */
async function makeJpeg(width, height, quality = 0.95) {
  const page = await browser.newPage();
  const b64 = await page.evaluate(({ width, height, quality }) => {
    const c = document.createElement("canvas");
    c.width = width; c.height = height;
    const ctx = c.getContext("2d");
    const g = ctx.createLinearGradient(0, 0, width, height);
    g.addColorStop(0, "#2f6b2f"); g.addColorStop(1, "#c8a13b");
    ctx.fillStyle = g; ctx.fillRect(0, 0, width, height);
    for (let i = 0; i < 4000; i++) { ctx.fillStyle = `rgb(${(i * 37) % 255},${(i * 91) % 255},${(i * 13) % 255})`; ctx.fillRect((i * 7919) % width, (i * 104729) % height, 6, 6); }
    return c.toDataURL("image/jpeg", quality).split(",")[1];
  }, { width, height, quality });
  await page.close();
  return Buffer.from(b64, "base64");
}

/** Splices an EXIF-like APP1 segment (with a recognizable GPS marker) right after the JPEG SOI. */
function withExif(jpeg, marker = "SECRET-GPS-MARKER-12.34N-75.46E") {
  const payload = Buffer.concat([Buffer.from("Exif\0\0"), Buffer.from(marker)]);
  const seg = Buffer.alloc(4 + payload.length);
  seg[0] = 0xff; seg[1] = 0xe1; seg.writeUInt16BE(payload.length + 2, 2); payload.copy(seg, 4);
  return Buffer.concat([jpeg.subarray(0, 2), seg, jpeg.subarray(2)]);
}

async function choose(page, buffer, name = "leaf.jpg", mimeType = "image/jpeg") {
  await page.setInputFiles("#scnFileInput", { name, mimeType, buffer });
  await waitState(page, "preview");
}
async function pickAndAnalyze(page, buffer, name, mimeType) {
  await choose(page, buffer, name, mimeType);
  await page.click("#scnAnalyzeBtn");
}
const analyzeCalls = () => mock.requests.filter((r) => r.path === "/api/scan/analyze");
const visible = (page, sel) => page.isVisible(sel);

// ------------------------------------------------------------------ spec 11 & 12/13: rendering paths

test("successful structured diagnosis renders the diagnosis view (renderDiagnosis path)", async () => {
  const jpeg = await makeJpeg(800, 600);
  const { page, context, consoleErrors } = await openScanner();
  await pickAndAnalyze(page, jpeg);
  await waitState(page, "result");

  const text = await page.innerText("#scnResultContent");
  assert.match(text, /Tomato/i);
  assert.match(text, /Early blight/);
  assert.match(text, /Moderate/);          // severity pill
  assert.match(text, /High/);              // confidence pill
  assert.match(text, /Brown concentric-ring spots/);
  assert.match(text, /local agricultural officer/);
  assert.equal(await visible(page, "#scnPanelResult"), true);
  assert.equal(await visible(page, "#scnPanelUncertain"), false);
  assert.equal(await visible(page, "#scnPanelError"), false);
  assert.deepEqual(consoleErrors, []);
  await context.close();
});

test("non-plant image renders the uncertain experience with the model's explanation", async () => {
  mock.setScenario(({ respond }) => respond(200, NON_PLANT));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "uncertain");
  const text = await page.innerText("#scnUncertainContent");
  assert.match(text, /More information is needed/);
  assert.match(text, /coffee mug on a desk/);
  assert.equal(await visible(page, "#scnPanelResult"), false);
  await context.close();
});

test("uncertain image (unknown disease) renders the uncertain experience", async () => {
  mock.setScenario(({ respond }) => respond(200, UNKNOWN_DISEASE));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "uncertain");
  assert.match(await page.innerText("#scnUncertainContent"), /too distant and blurred/);
  await context.close();
});

test("routing rule: unknown disease OR no plant -> uncertain; otherwise diagnosis (neither needs_human_review nor LOW confidence hides a named diagnosis)", async () => {
  const cases = [
    // BUGFIX regression case: a named, confident diagnosis that also needs human review (e.g.
    // because it looks severe or could be a regulated disease) must still render as a full result —
    // this is exactly the "valid Gemini diagnosis shown as if no case was found" bug.
    [{ ...DIAGNOSIS, needs_human_review: true }, "result"],
    // BUGFIX 2: Gemini often names the right disease but rates itself "low". That must still show the
    // disease, severity % and spread-chance % — it used to be routed to the generic uncertain screen.
    [{ ...DIAGNOSIS, confidence: "low" }, "result"],
    [{ ...DIAGNOSIS, disease: "Unknown" }, "uncertain"],
    [{ ...DIAGNOSIS, disease: "  " }, "uncertain"],
    [{ ...DIAGNOSIS, plant_detected: false }, "uncertain"],
    [{ ...DIAGNOSIS, confidence: "moderate" }, "result"],
    [DIAGNOSIS, "result"],
  ];
  const { page, context } = await openScanner();
  const jpeg = await makeJpeg(300, 200);
  for (const [payload, expected] of cases) {
    mock.setScenario(({ respond }) => respond(200, payload));
    await pickAndAnalyze(page, jpeg);
    await waitState(page, expected);
    assert.equal(await state(page), expected, JSON.stringify(payload).slice(0, 90));
    await page.evaluate(() => window.FolixScanner.reset());
    await waitState(page, "empty");
  }
  await context.close();
});

test("BUGFIX: a named diagnosis flagged needs_human_review still shows the disease, not the uncertain screen", async () => {
  mock.setScenario(({ respond }) => respond(200, { ...DIAGNOSIS, needs_human_review: true }));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "result");
  const text = await page.innerText("#scnResultContent");
  assert.match(text, /Early blight/);
  assert.match(text, /Human review recommended/);
  assert.equal(await visible(page, "#scnPanelUncertain"), false);
  await context.close();
});

test("renderDiagnosis() and renderUncertain() still work when driven directly (Stage 3 integration seam)", async () => {
  const { page, context } = await openScanner();
  await page.evaluate((d) => window.FolixScanner.renderDiagnosis(d), DIAGNOSIS);
  assert.equal(await state(page), "result");
  assert.match(await page.innerText("#scnResultContent"), /Early blight/);
  await page.evaluate((d) => window.FolixScanner.renderUncertain(d), NON_PLANT);
  assert.equal(await state(page), "uncertain");
  assert.match(await page.innerText("#scnUncertainContent"), /coffee mug/);
  assert.equal(mock.requests.length, 0, "direct rendering must not hit the network");
  await context.close();
});

test("model text is rendered as text, never as HTML", async () => {
  mock.setScenario(({ respond }) => respond(200, {
    ...DIAGNOSIS,
    crop: "<img src=x onerror=window.__pwned=1>",
    reasoning: "<script>window.__pwned=2</script> bold <b>text</b>",
  }));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(300, 200));
  await waitState(page, "result");
  assert.equal(await page.evaluate(() => window.__pwned), undefined);
  assert.equal(await page.locator("#scnResultContent img").count(), 0);
  assert.match(await page.innerText("#scnResultContent"), /<script>window\.__pwned=2<\/script>/);
  await context.close();
});

// ------------------------------------------------------------------ one request, pending state, cancel

test("exactly one analysis request per scan, even on a double-click", async () => {
  mock.setScenario(({ respond }) => respond(200, DIAGNOSIS, { delayMs: 300 }));
  const { page, context } = await openScanner();
  await choose(page, await makeJpeg(400, 300));
  await page.dblclick("#scnAnalyzeBtn");
  await waitState(page, "result");
  assert.equal(analyzeCalls().length, 1);
  await context.close();
});

test("the analyzing animation stays active while the request is pending", async () => {
  mock.setScenario(({ respond }) => respond(200, DIAGNOSIS, { delayMs: 900 }));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "analyzing");
  await page.waitForTimeout(400);
  assert.equal(await state(page), "analyzing");
  assert.equal(await visible(page, "#scnPanelAnalyzing"), true);
  assert.equal(await page.locator("#scnPanelAnalyzing .scn-frame.scn-is-analyzing").count(), 1);
  await waitState(page, "result");
  await context.close();
});

test("cancelling returns to the preview and a late response is never shown", async () => {
  mock.setScenario(({ respond }) => respond(200, DIAGNOSIS, { delayMs: 900 }));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "analyzing");
  await page.click("#scnCancelAnalysisBtn");
  await waitState(page, "preview");
  await page.waitForTimeout(1400); // longer than the server delay
  assert.equal(await state(page), "preview", "a stale response must not change the UI");
  assert.equal((await page.innerText("#scnResultContent")).trim(), "");
  assert.equal(analyzeCalls().length, 1);
  await context.close();
});

test("removing the image while analyzing also discards the pending result", async () => {
  mock.setScenario(({ respond }) => respond(200, DIAGNOSIS, { delayMs: 700 }));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "analyzing");
  await page.evaluate(() => window.FolixScanner.reset());
  await page.waitForTimeout(1100);
  assert.equal(await state(page), "empty");
  await context.close();
});

// ------------------------------------------------------------------ failure handling

async function expectError(page, { messageRe, retry }) {
  await waitState(page, "error");
  assert.equal(await visible(page, "#scnPanelError"), true);
  assert.match(await page.innerText("#scnErrorTitle"), /FOLIX couldn't complete the analysis\./);
  assert.match(await page.innerText("#scnErrorMessage"), messageRe);
  assert.equal(await visible(page, "#scnErrorRetryBtn"), retry);
  assert.equal(await visible(page, "#scnErrorChooseBtn"), true);
  // An error is never presented as a diagnosis.
  assert.equal(await visible(page, "#scnPanelResult"), false);
  assert.equal(await visible(page, "#scnPanelUncertain"), false);
  assert.equal((await page.innerText("#scnResultContent")).trim(), "");
  assert.doesNotMatch(await page.innerText("#scnPanelError"), /Tomato|blight|Severity|Confidence/i);
}

const errorCases = [
  ["rate limit (429)", (r) => r(429, { error: { code: "RATE_LIMITED", message: "FOLIX is handling a lot of requests right now. Please wait a moment, then try again.", retryable: true } }), /lot of requests/, true],
  ["429 from the legacy rate-limit filter (no structured error)", (r) => r(429, { errorCode: "RATE_LIMITED", message: "Too many requests." }), /lot of requests/, true],
  ["Gemini not configured (503 unavailable)", (r) => r(503, { error: { code: "ANALYSIS_UNAVAILABLE", message: "FOLIX analysis isn't available right now. Please try again later.", retryable: false } }), /isn't available right now/, false],
  ["Gemini auth failure surfaced as unavailable", (r) => r(503, { error: { code: "ANALYSIS_UNAVAILABLE", retryable: false } }), /isn't available right now/, false],
  ["malformed Gemini output (502)", (r) => r(502, { error: { code: "ANALYSIS_FAILED", message: "FOLIX couldn't complete the analysis. Please try again.", retryable: true } }), /Please try again/, true],
  ["unexpected 500 with an HTML body", (r) => r(500, "<html>Internal Server Error</html>"), /Please try again/, true],
  ["invalid upload (400)", (r) => r(400, { error: { code: "INVALID_IMAGE", retryable: false } }), /couldn't be read as an image/, false],
  ["unsupported file type (415)", (r) => r(415, { error: { code: "UNSUPPORTED_IMAGE_TYPE", retryable: false } }), /isn't supported/, false],
  ["oversized upload rejected by the framework (413, legacy body shape)", (r) => r(413, { errorCode: "IMAGE_TOO_LARGE", message: "Uploaded image exceeds the maximum allowed size." }), /too large/, false],
  ["gateway timeout (504)", (r) => r(504, { error: { code: "ANALYSIS_TIMEOUT", retryable: true } }), /took too long/, true],
  ["200 OK but an HTML page (misrouted proxy)", (r) => r(200, "<!doctype html><title>nginx</title>"), /Please try again/, true],
  ["200 OK but not the contract", (r) => r(200, { disease: "Late blight", confidence: "high" }), /Please try again/, true],
  ["200 OK with an out-of-set enum value", (r) => r(200, { ...DIAGNOSIS, confidence: "very high" }), /Please try again/, true],
  ["200 OK with a stringly-typed boolean", (r) => r(200, { ...DIAGNOSIS, plant_detected: "true" }), /Please try again/, true],
];
for (const [name, scenario, messageRe, retry] of errorCases) {
  test(`error state: ${name}`, async () => {
    mock.setScenario(({ respond }) => scenario(respond));
    const { page, context } = await openScanner();
    await pickAndAnalyze(page, await makeJpeg(300, 200));
    await expectError(page, { messageRe, retry });
    assert.equal(analyzeCalls().length, 1, "a failure must not trigger an automatic second request");
    await context.close();
  });
}

test("error state: backend unreachable (browser/network failure)", async () => {
  const dead = await startMock();
  const deadUrl = dead.url;
  await dead.close();
  const { page, context } = await openScanner({ apiBase: deadUrl });
  await pickAndAnalyze(page, await makeJpeg(300, 200));
  await expectError(page, { messageRe: /couldn't reach the analysis service/, retry: true });
  await context.close();
});

test("error state: client-side timeout", async () => {
  mock.setScenario(({ respond }) => respond(200, DIAGNOSIS, { delayMs: 3000 }));
  const { page, context } = await openScanner({ timeoutMs: 600 });
  await pickAndAnalyze(page, await makeJpeg(300, 200));
  await expectError(page, { messageRe: /took too long/, retry: true });
  assert.equal(analyzeCalls().length, 1);
  await context.close();
});

test("Try Again is a deliberate user action: exactly one more request, then the result", async () => {
  let n = 0;
  mock.setScenario(({ respond }) => (++n === 1 ? respond(503, { error: { code: "ANALYSIS_FAILED", retryable: true } }) : respond(200, DIAGNOSIS)));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(300, 200));
  await waitState(page, "error");
  assert.equal(analyzeCalls().length, 1);
  await page.click("#scnErrorRetryBtn");
  await waitState(page, "result");
  assert.equal(analyzeCalls().length, 2);
  assert.match(await page.innerText("#scnResultContent"), /Early blight/);
  await context.close();
});

test("Choose Another Image returns to the start and clears the previous photo", async () => {
  mock.setScenario(({ respond }) => respond(500, "boom"));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(300, 200));
  await waitState(page, "error");
  await page.click("#scnErrorChooseBtn");
  await waitState(page, "empty");
  assert.equal(await page.getAttribute("#scnPreviewImg", "src"), null);
  await context.close();
});

test("server-provided error text is shown as text, and over-long/odd messages fall back to local wording", async () => {
  mock.setScenario(({ respond }) => respond(502, { error: { code: "ANALYSIS_FAILED", message: "<b>x</b>".repeat(100), retryable: true } }));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(300, 200));
  await waitState(page, "error");
  assert.match(await page.innerText("#scnErrorMessage"), /FOLIX couldn't complete the analysis\. Please try again\./);
  assert.equal(await page.locator("#scnErrorMessage b").count(), 0);
  await context.close();
});

// ------------------------------------------------------------------ what the browser sends

test("the request is one multipart POST with a single 'image' field — no identity, no location", async () => {
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(500, 400));
  await waitState(page, "result");
  const [req] = analyzeCalls();
  assert.equal(req.method, "POST");
  assert.match(req.headers["content-type"], /^multipart\/form-data; boundary=/);
  assert.deepEqual(req.parts.map((p) => p.name), ["image"]);
  assert.equal(req.headers.cookie, undefined, "credentials must be omitted");
  assert.equal(req.headers.authorization, undefined);
  await context.close();
});

test("large photos are downsized to <=1920px JPEG and EXIF/GPS never leave the browser", async () => {
  const marker = "SECRET-GPS-MARKER-12.34N-75.46E";
  const big = withExif(await makeJpeg(4000, 3000, 0.97), marker);
  assert.ok(big.includes(Buffer.from(marker)), "fixture must carry the GPS marker");
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, big, "IMG_0001.jpg");
  await waitState(page, "result");

  const image = analyzeCalls()[0].parts.find((p) => p.name === "image");
  assert.equal(image.contentType, "image/jpeg");
  const size = jpegSize(image.data);
  assert.ok(size, "uploaded bytes must be a decodable JPEG");
  assert.ok(Math.max(size.width, size.height) <= 1920, `long edge ${Math.max(size.width, size.height)}`);
  assert.ok(size.width / size.height > 1.3 && size.width / size.height < 1.4, "aspect ratio preserved");
  assert.ok(image.data.length < big.length / 2, `upload ${image.data.length} vs original ${big.length}`);
  assert.equal(image.data.includes(Buffer.from(marker)), false, "GPS marker leaked to the network");
  assert.equal(image.data.includes(Buffer.from("Exif")), false);
  await context.close();
});

test("a small photo is sent unchanged (the server strips its metadata)", async () => {
  const small = await makeJpeg(640, 480, 0.8);
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, small, "small.jpg");
  await waitState(page, "result");
  const image = analyzeCalls()[0].parts.find((p) => p.name === "image");
  assert.ok(image.data.equals(small));
  await context.close();
});

test("a transparent PNG is flattened onto white, not black, when converted", async () => {
  const pg = await browser.newPage();
  const b64 = await pg.evaluate(() => {
    const c = document.createElement("canvas"); c.width = 2600; c.height = 2000; // > 1920 forces conversion
    return c.toDataURL("image/png").split(",")[1]; // fully transparent
  });
  await pg.close();
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, Buffer.from(b64, "base64"), "clear.png", "image/png");
  await waitState(page, "result");
  const image = analyzeCalls()[0].parts.find((p) => p.name === "image");
  assert.equal(image.contentType, "image/jpeg");
  const px = await page.evaluate(async (bytes) => {
    const bmp = await createImageBitmap(new Blob([new Uint8Array(bytes)], { type: "image/jpeg" }));
    const c = document.createElement("canvas"); c.width = bmp.width; c.height = bmp.height;
    const ctx = c.getContext("2d"); ctx.drawImage(bmp, 0, 0);
    return Array.from(ctx.getImageData(10, 10, 1, 1).data);
  }, Array.from(image.data));
  assert.ok(px[0] > 240 && px[1] > 240 && px[2] > 240, `expected near-white, got ${px}`);
  await context.close();
});

// ------------------------------------------------------------------ Stage 3 behaviour is intact / no legacy path

test("unsupported file types are still rejected locally, with no request", async () => {
  const { page, context } = await openScanner();
  await page.setInputFiles("#scnFileInput", { name: "notes.txt", mimeType: "text/plain", buffer: Buffer.from("hello") });
  await page.waitForSelector("#scnFileError:not([hidden])");
  assert.match(await page.innerText("#scnFileError"), /isn't supported/);
  assert.equal(await state(page), "empty");
  assert.equal(mock.requests.length, 0);
  await context.close();
});

test("preview, change and remove still work; selecting alone sends nothing", async () => {
  const { page, context } = await openScanner();
  await choose(page, await makeJpeg(300, 200));
  assert.ok(await page.getAttribute("#scnPreviewImg", "src"));
  await page.click("#scnRemoveBtn");
  await waitState(page, "empty");
  assert.equal(mock.requests.length, 0);
  await context.close();
});

test("an online scan never calls the legacy scan API or Gemini directly, even though the offline/local-model files are present for genuine offline use", async () => {
  const { page, context, requested } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(300, 200));
  await waitState(page, "result");
  const joined = requested.join("\n");
  // These must never be hit while online: the legacy scan endpoint and Google directly.
  for (const forbidden of ["/app.js", "/api/v1/", "generativelanguage"]) {
    assert.equal(joined.includes(forbidden), false, `unexpected request matching ${forbidden}`);
  }
  assert.ok(requested.some((u) => u.endsWith("/api/scan/analyze")));
  // The browser must never reach Google directly nor carry a key.
  assert.equal(requested.some((u) => /key=|AIza/.test(u)), false);
  await context.close();
});

// ------------------------------------------------------------------ Stage 5B: Save Report

const reportCalls = () => mock.requests.filter((r) => r.path === "/api/scan/reports");

test("Save Report sends the currently displayed diagnosis to POST /api/scan/reports and shows a saved state", async () => {
  mock.setReportScenario(({ respond, request }) => respond(201, SAVED_REPORT(request.json)));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "result");

  await page.click('[data-scn-action="save"]');
  await page.waitForSelector('[data-scn-action="save"][disabled]');
  await page.waitForFunction(() => document.querySelector('[data-scn-action="save"]').textContent.includes("Saved"));

  assert.equal(reportCalls().length, 1, "exactly one save request");
  const [req] = reportCalls();
  assert.equal(req.method, "POST");
  assert.match(req.headers["content-type"], /^application\/json/);
  // (Stage 6A: saving is credentialed — see the cookie tests below. No cookie exists in this fresh context.)
  assert.equal(req.headers.authorization, undefined, "no bearer token / Authorization header is ever used");
  // the diagnosis contract fields, mapped onto FolixReportRequest field names, sent unchanged
  assert.equal(req.json.crop, DIAGNOSIS.crop);
  assert.equal(req.json.disease, DIAGNOSIS.disease);
  assert.equal(req.json.severity, DIAGNOSIS.severity);
  assert.equal(req.json.confidence, DIAGNOSIS.confidence);
  assert.equal(req.json.plantPart, DIAGNOSIS.plant_part);
  assert.deepEqual(req.json.symptoms, DIAGNOSIS.symptoms);
  assert.equal(req.json.reasoning, DIAGNOSIS.reasoning);
  assert.deepEqual(req.json.recommendedActions, DIAGNOSIS.recommended_actions);
  assert.equal(req.json.needsHumanReview, DIAGNOSIS.needs_human_review);
  assert.equal("plant_detected" in req.json, false, "plant_detected is not part of the persisted report");
  assert.equal("plantDetected" in req.json, false);

  // saved state is visible and the button no longer accepts another click
  assert.match(await page.innerText('[data-scn-action-note]'), /saved/i);
  await context.close();
});

test("Save Report on the uncertain panel saves the same result shown there, without calling Gemini again", async () => {
  mock.setScenario(({ respond }) => respond(200, UNKNOWN_DISEASE));
  mock.setReportScenario(({ respond, request }) => respond(201, SAVED_REPORT(request.json)));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "uncertain");

  await page.click('#scnUncertainContent [data-scn-action="save"]');
  await page.waitForFunction(() => document.querySelector('#scnUncertainContent [data-scn-action="save"]').textContent.includes("Saved"));

  assert.equal(analyzeCalls().length, 1, "no second analysis request was made");
  assert.equal(reportCalls().length, 1);
  assert.equal(reportCalls()[0].json.disease, UNKNOWN_DISEASE.disease);
  await context.close();
});

test("a server validation error on save is shown, and does not show a false saved state", async () => {
  mock.setReportScenario(({ respond }) =>
    respond(400, { error: { code: "VALIDATION_FAILED", message: "invalid severity: catastrophic", retryable: false } }));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "result");

  await page.click('[data-scn-action="save"]');
  await page.waitForFunction(() => /invalid severity/.test(document.querySelector('[data-scn-action-note]').textContent));

  assert.doesNotMatch(await page.innerText('[data-scn-action="save"]'), /Saved/);
  assert.equal(await page.getAttribute('[data-scn-action="save"]', "disabled"), null, "button re-enabled for retry");
  await context.close();
});

test("a network/server failure on save allows retry, and a later success shows the saved state", async () => {
  let attempt = 0;
  mock.setReportScenario(({ respond, request }) => {
    attempt += 1;
    if (attempt === 1) return respond(500, { error: { code: "ANALYSIS_FAILED", message: "unexpected server error", retryable: true } });
    return respond(201, SAVED_REPORT(request.json));
  });
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "result");

  await page.click('[data-scn-action="save"]');
  await page.waitForFunction(() => /unexpected server error/.test(document.querySelector('[data-scn-action-note]').textContent));
  assert.equal(reportCalls().length, 1);

  await page.click('[data-scn-action="save"]'); // retry
  await page.waitForFunction(() => document.querySelector('[data-scn-action="save"]').textContent.includes("Saved"));
  assert.equal(reportCalls().length, 2, "retry sent exactly one more request");
  await context.close();
});

test("double-clicking Save Report while a save is pending sends exactly one request", async () => {
  mock.setReportScenario(({ respond, request }) => respond(201, SAVED_REPORT(request.json), { delayMs: 300 }));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "result");

  await page.dblclick('[data-scn-action="save"]');
  await page.waitForFunction(() => document.querySelector('[data-scn-action="save"]').textContent.includes("Saved"));
  assert.equal(reportCalls().length, 1);
  await context.close();
});

test("scanner sources contain no API key material or Gemini credentials", async () => {
  const fs = require("node:fs");
  for (const f of ["scanner-experience.js", "scanner.html"]) {
    const src = fs.readFileSync(path.join(FRONTEND, f), "utf8");
    assert.equal(/AIza[0-9A-Za-z_-]{20,}/.test(src), false, `${f}: looks like a Google API key`);
    const code = src.replace(/<!--[\s\S]*?-->/g, "").replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
    assert.equal(/x-goog-api-key|GEMINI_API_KEY|GEMINI_KEY|generativelanguage/i.test(code), false, `${f}: references Gemini credentials or endpoint`);
  }
});


// ------------------------------------------------------------------ Stage 6A: authentication and the Scanner

/** Puts an HttpOnly session cookie for the mock API host into the browser context, as a real login would. */
async function withSessionCookie(context) {
  await context.addCookies([{ name: "FOLIX_SESSION", value: "T".repeat(43), domain: "127.0.0.1", path: "/api", httpOnly: true }]);
}

test("Save Report sends the session cookie, but the analysis request still sends no credentials", async () => {
  mock.setReportScenario(({ respond, request }) => respond(201, SAVED_REPORT(request.json)));
  const { page, context } = await openScanner();
  await withSessionCookie(context);
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "result");
  await page.click('[data-scn-action="save"]');
  await page.waitForFunction(() => document.querySelector('[data-scn-action="save"]').textContent.includes("Saved"));

  const [analyze] = analyzeCalls();
  const [save] = reportCalls();
  assert.equal(analyze.headers.cookie, undefined, "scanning must not carry the session cookie");
  assert.match(save.headers.cookie || "", /FOLIX_SESSION=/, "saving must carry the session cookie");
  assert.equal(save.headers.authorization, undefined);
  assert.equal(JSON.stringify(save.json).includes("farmer"), false, "the browser never sends a user id in the report body");
  await context.close();
});

test("a 401 on Save Report explains that login is needed, links to login in a NEW tab, and allows a retry", async () => {
  mock.setReportScenario(({ respond }) =>
    respond(401, { error: { code: "UNAUTHENTICATED", message: "You need to log in to do that.", retryable: false } }));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(400, 300));
  await waitState(page, "result");
  await page.click('[data-scn-action="save"]');
  await page.waitForFunction(() => /log in/i.test(document.querySelector("[data-scn-action-note]").textContent));

  assert.doesNotMatch(await page.innerText('[data-scn-action="save"]'), /Saved/);
  assert.equal(await page.getAttribute('[data-scn-action="save"]', "disabled"), null, "button re-enabled so Try Again works after logging in");
  const link = await page.$("[data-scn-action-note] a");
  assert.ok(link, "a login link is offered");
  assert.equal(await link.getAttribute("target"), "_blank", "login opens in a new tab so the diagnosis is not lost");
  assert.match(await link.getAttribute("href"), /^login\.html\?next=close$/);
  assert.equal(await page.isVisible("#scnResultContent"), true, "the diagnosis is still on screen");
  await context.close();
});

test("the header shows a Log in control on the Scanner page", async () => {
  const { page, context } = await openScanner();
  await page.waitForSelector("#authWidget a.auth-login");
  assert.equal(await page.innerText("#authWidget a.auth-login"), "Log in");
  assert.ok(mock.authRequests.some((r) => r.path === "/api/auth/session"));
  await context.close();
});


// ------------------------------------------------------------------ Low confidence keeps the numbers; real Forward Case

const LOW_CONF = {
  ...DIAGNOSIS, disease: "Late blight", confidence: "low", severity: "moderate", needs_human_review: true,
  epidemic_risk: true, severity_percent: 40, spread_chance_percent: 71,
};

test("a named LOW-confidence diagnosis shows the disease, severity %, spread-chance %, epidemic warning and a low-confidence label", async () => {
  mock.setScenario(({ respond }) => respond(200, LOW_CONF));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(300, 200));
  await waitState(page, "result");
  const text = await page.innerText("#scnResultContent");
  assert.match(text, /Possibly: Late blight/);
  assert.match(text, /Low confidence/i);
  assert.match(text, /40%/);
  assert.match(text, /71%/);
  assert.match(text, /Epidemic-risk disease/);
  await context.close();
});

test("missing percentages are shown as 'Not available', never as 0%", async () => {
  mock.setScenario(({ respond }) => respond(200, { ...LOW_CONF, severity_percent: null, spread_chance_percent: null }));
  const { page, context } = await openScanner();
  await pickAndAnalyze(page, await makeJpeg(300, 200));
  await waitState(page, "result");
  const text = await page.innerText("#scnResultContent");
  assert.match(text, /Not available/);
  assert.equal(/\b0%/.test(text), false);
  await context.close();
});

/** Fulfils a cross-origin credentialed request from the scanner page to the mock backend. */
function fulfillJson(route, status, body) {
  return route.fulfill({
    status,
    contentType: "application/json",
    headers: { "Access-Control-Allow-Origin": route.request().headers()["origin"] || "*", "Access-Control-Allow-Credentials": "true", "Access-Control-Allow-Headers": "content-type" },
    body: JSON.stringify(body),
  });
}

test("Forward Case saves the report, lets the farmer pick a contact, and only forwards after Confirm", async () => {
  mock.setScenario(({ respond }) => respond(200, LOW_CONF));
  mock.setReportScenario(({ respond, request }) => respond(201, SAVED_REPORT(request.json)));
  const { page, context } = await openScanner();
  const forwardPosts = [];
  await page.route("**/api/farmer/agri-network/directory", (route) =>
    route.request().method() === "OPTIONS"
      ? fulfillJson(route, 204, {})
      : fulfillJson(route, 200, [{ id: 7, name: "Malappuram KVK", category: "KVK", organization: "ICAR" }]));
  await page.route("**/api/scan/reports/*/forward", (route) => {
    if (route.request().method() === "OPTIONS") return fulfillJson(route, 204, {});
    forwardPosts.push(route.request().postDataJSON());
    return fulfillJson(route, 201, { status: "FORWARDED", supportContact: { id: 7, name: "Malappuram KVK" }, forwardedAt: new Date().toISOString() });
  });

  await pickAndAnalyze(page, await makeJpeg(300, 200));
  await waitState(page, "result");
  await page.click('[data-scn-action="forward"]');
  await page.waitForSelector("[data-scn-forward-picker]");
  assert.equal(reportCalls().length, 1, "the case is saved first");
  const saved = reportCalls()[0].json;
  assert.equal(saved.severityPercent, 40, "severity % is saved with the report");
  assert.equal(saved.spreadChancePercent, 71, "spread-chance % is saved with the report");
  assert.equal(saved.epidemicRisk, true, "the epidemic flag is no longer dropped on save");
  assert.equal(forwardPosts.length, 0, "nothing is forwarded before the farmer confirms");
  assert.equal((await page.innerText("#scnResultContent")).includes("coming in a future stage"), false);

  await page.click("[data-scn-forward-confirm]");
  await page.waitForFunction(() => document.querySelector('[data-scn-action="forward"]').textContent.includes("Forwarded"));
  assert.deepEqual(forwardPosts, [{ supportContactId: 7 }]);
  assert.match(await page.innerText("#scnResultContent"), /Malappuram KVK/);
  await context.close();
});
