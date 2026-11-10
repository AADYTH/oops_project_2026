"use strict";
/**
 * Tests for CropGuardOffline (frontend/offline-classifier.js) — run in a REAL Chromium against the
 * REAL scanner.html (which loads offline-classifier.js as CropGuardOffline), with
 * /api/scan/analyze mocked at the network layer. IndexedDB, navigator.onLine and fetch are all
 * real browser behavior; nothing here is simulated in Node.
 *
 * node --test tests/offline-frontend/offline.test.js
 */
const { test, before, after, beforeEach, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { chromium } = require("playwright");
const { startStatic, startMock } = require("../scanner-frontend/mock-backend");

const FRONTEND = path.resolve(__dirname, "..", "..", "frontend");

let browser, staticSrv, mock;

before(async () => {
  browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH, args: ["--no-sandbox"] } : { args: ["--no-sandbox"] });
  staticSrv = await startStatic(FRONTEND);
});
after(async () => {
  await browser?.close();
  await staticSrv?.close();
});
beforeEach(async () => {
  mock = await startMock();
});
afterEach(async () => {
  await mock?.close();
});

async function openPage() {
  const context = await browser.newContext();
  const page = await context.newPage();
  // This sandbox has no real network egress; stub the two external resources scanner.html
  // references (ONNX runtime CDN script, Google Fonts) so the page loads cleanly and quickly —
  // unrelated to what these tests are checking, which is the offline queue module itself.
  await page.route("https://cdn.jsdelivr.net/**", (r) => r.fulfill({ status: 200, contentType: "text/javascript", body: "// stub" }));
  await page.route("https://fonts.googleapis.com/**", (r) => r.fulfill({ status: 200, contentType: "text/css", body: "/* stub */" }));
  await page.addInitScript((apiUrl) => { window.CROPGUARD_API_BASE_URL = apiUrl; }, mock.url);
  await page.goto(`${staticSrv.url}/scanner.html`);
  // NOTE: CropGuardOffline is a top-level `const` in a classic (non-module) script, so it is a
  // global lexical binding, not a `window` property — reachable here as a bare identifier, but
  // deliberately NOT as window.CropGuardOffline.
  await page.waitForFunction(() => typeof CropGuardOffline !== "undefined");
  // Each test gets a clean queue: wipe whatever a previous test on this origin left behind.
  await page.evaluate(async () => {
    const scans = await CropGuardOffline.getQueuedScans();
    for (const s of scans) await CropGuardOffline.removeQueuedScan(s.id);
  });
  return { context, page };
}

async function queueOneScan(page, extra = {}) {
  return page.evaluate(async (extra) => {
    // A tiny in-memory blob stands in for a captured photo — the module never inspects its bytes.
    const blob = new Blob([new Uint8Array([1, 2, 3, 4])], { type: "image/jpeg" });
    return CropGuardOffline.queueScan({ blob, plantPart: "leaf", latitude: null, longitude: null, offlineEstimate: null, ...extra });
  }, extra);
}

test("a queued scan survives a page refresh (IndexedDB, not memory)", async () => {
  mock.setScenario(({ respond }) => respond(500, { error: { code: "ANALYSIS_FAILED", retryable: true } }));
  const { context, page } = await openPage();
  try {
    await queueOneScan(page);
    assert.equal((await page.evaluate(() => CropGuardOffline.getQueuedScans())).length, 1);

    await page.reload();
    await page.waitForFunction(() => typeof CropGuardOffline !== "undefined");
    const afterReload = await page.evaluate(() => CropGuardOffline.getQueuedScans());
    assert.equal(afterReload.length, 1, "the queued scan must still be there after a refresh");
  } finally {
    await context.close();
  }
});

test("trySyncQueue removes a scan on a successful sync and reports it via onEach", async () => {
  mock.setScenario(({ respond }) => respond(200, { plant_detected: true, disease: "unknown", crop: "unknown", confidence: "low", severity: "unknown", symptoms: [], reasoning: "x", recommended_actions: [], needs_human_review: true }));
  const { context, page } = await openPage();
  try {
    await queueOneScan(page);
    const outcome = await page.evaluate((apiUrl) => new Promise((resolve) => {
      const seen = [];
      CropGuardOffline.trySyncQueue(apiUrl, (scan, result, err) => seen.push({ result: !!result, err: err ? String(err) : null }))
        .then((r) => resolve({ r, seen }));
    }), mock.url);
    assert.equal(outcome.r.synced, 1);
    assert.equal(outcome.r.remaining, 0);
    assert.equal(outcome.seen.length, 1);
    assert.equal(outcome.seen[0].result, true);
    assert.equal((await page.evaluate(() => CropGuardOffline.getQueuedScans())).length, 0);
  } finally {
    await context.close();
  }
});

test("a sync failure leaves the scan queued for retry rather than dropping it", async () => {
  mock.setScenario(({ respond }) => respond(500, { error: { code: "ANALYSIS_FAILED" } }));
  const { context, page } = await openPage();
  try {
    await queueOneScan(page);
    const outcome = await page.evaluate((apiUrl) => CropGuardOffline.trySyncQueue(apiUrl, () => {}), mock.url);
    assert.equal(outcome.synced, 0);
    assert.equal(outcome.remaining, 1, "a failed sync must leave the scan queued, not discard it");
    assert.equal((await page.evaluate(() => CropGuardOffline.getQueuedScans())).length, 1);
  } finally {
    await context.close();
  }
});

test("BUGFIX: two overlapping trySyncQueue calls submit a queued scan only once, not twice", async () => {
  // A deliberate delay makes the two calls' HTTP requests genuinely overlap in time — without the
  // fix, both calls would independently read the same still-queued scan (neither has removed it
  // yet) and both POST it.
  mock.setScenario(({ respond }) => respond(200, { plant_detected: true, disease: "unknown", crop: "unknown", confidence: "low", severity: "unknown", symptoms: [], reasoning: "x", recommended_actions: [], needs_human_review: true }, { delayMs: 150 }));
  const { context, page } = await openPage();
  try {
    await queueOneScan(page);

    const [r1, r2] = await page.evaluate((apiUrl) => Promise.all([
      CropGuardOffline.trySyncQueue(apiUrl, () => {}),
      CropGuardOffline.trySyncQueue(apiUrl, () => {}),
    ]), mock.url);

    assert.equal(mock.requests.length, 1, "the same queued scan must only be POSTed once across both overlapping calls");
    // Both callers were sharing one real underlying sync, so they see the SAME outcome, not two
    // independent ones (e.g. it would be wrong for the total synced count to add up to 2 for one scan).
    assert.deepEqual(r1, r2, "both overlapping callers should observe the one shared sync's result");
    assert.equal(r1.synced, 1);
    assert.equal((await page.evaluate(() => CropGuardOffline.getQueuedScans())).length, 0);
  } finally {
    await context.close();
  }
});

test("BUGFIX: overlapping calls with two DIFFERENT queued scans still sync both, exactly once each", async () => {
  mock.setScenario(({ respond }) => respond(200, { plant_detected: true, disease: "unknown", crop: "unknown", confidence: "low", severity: "unknown", symptoms: [], reasoning: "x", recommended_actions: [], needs_human_review: true }, { delayMs: 100 }));
  const { context, page } = await openPage();
  try {
    await queueOneScan(page);
    await queueOneScan(page);

    await page.evaluate((apiUrl) => Promise.all([
      CropGuardOffline.trySyncQueue(apiUrl, () => {}),
      CropGuardOffline.trySyncQueue(apiUrl, () => {}),
    ]), mock.url);

    assert.equal(mock.requests.length, 2, "both distinct queued scans should each be synced exactly once");
    assert.equal((await page.evaluate(() => CropGuardOffline.getQueuedScans())).length, 0);
  } finally {
    await context.close();
  }
});
