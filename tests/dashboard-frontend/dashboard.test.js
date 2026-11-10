"use strict";
/**
 * Stage 5C Dashboard frontend tests — run in a REAL Chromium against the REAL
 * frontend/dashboard.html + dashboard.js, with GET /api/scan/reports* mocked
 * at the network layer via Playwright route interception. Nothing here
 * touches a real backend or database.
 *
 *   node --test tests/dashboard-frontend/dashboard.test.js
 */
const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { chromium } = require("playwright");
const { startStatic } = require("../scanner-frontend/mock-backend");

const FRONTEND = path.resolve(__dirname, "..", "..", "frontend");
const API_ORIGIN = "http://cropguard-api.test";

let browser, staticSrv;

before(async () => {
  browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH, args: ["--no-sandbox"] } : { args: ["--no-sandbox"] });
  staticSrv = await startStatic(FRONTEND);
});
after(async () => {
  await browser?.close();
  await staticSrv?.close();
});

function jsonRoute(json, status = 200) {
  return (route) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(json) });
}

const SAMPLE_REPORT = {
  id: "11111111-1111-1111-1111-111111111111",
  createdAt: "2026-09-01T10:15:30Z",
  crop: "Tomato",
  plantPart: "leaf",
  disease: "Early blight",
  severity: "moderate",
  confidence: "high",
  symptoms: ["Brown concentric-ring spots", "Yellow halo around lesions"],
  reasoning: "Concentric target-like rings are typical of early blight.",
  recommendedActions: ["Remove affected leaves.", "Ask a local agricultural officer to confirm."],
  needsHumanReview: false,
  status: "RECEIVED",
};

const SAMPLE_REPORT_2 = {
  ...SAMPLE_REPORT,
  id: "22222222-2222-2222-2222-222222222222",
  crop: "Rice",
  disease: "Blast",
  severity: "severe",
  confidence: "low",
  needsHumanReview: true,
};

/** Opens the real dashboard page with window.CROPGUARD_API_BASE_URL pointed at a fake origin, then wires
 * Playwright route interception for that origin so no real network call ever leaves the browser. */
async function openDashboard(routes) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const requested = [];
  page.on("request", (r) => { if (r.url().startsWith(API_ORIGIN)) requested.push(r.url()); });

  await page.route(`${API_ORIGIN}/**`, (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/scan/reports/summary" && routes.summary) return routes.summary(route);
    if (url.pathname === "/api/scan/reports" && routes.list) return routes.list(route);
    if (/^\/api\/scan\/reports\/[^/]+$/.test(url.pathname) && routes.detail) return routes.detail(route, url);
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });

  await page.addInitScript((origin) => { window.CROPGUARD_API_BASE_URL = origin; }, API_ORIGIN);
  await page.goto(`${staticSrv.url}/dashboard.html`);
  return { context, page, requested };
}

// ------------------------------------------------------------------
// 1. Empty state — a genuine empty database, no sample data anywhere
// ------------------------------------------------------------------

test("empty database renders the genuine empty state, not sample reports", async () => {
  const { context, page } = await openDashboard({
    summary: jsonRoute({ totalReports: 0, reportsNeedingHumanReview: 0 }),
    list: jsonRoute({ reports: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }),
  });
  try {
    await page.waitForSelector("#dashEmpty:not([hidden])", { timeout: 5000 });
    assert.equal(await page.isHidden("#dashHistorySection"), true);
    assert.equal(await page.isHidden("#dashError"), true);
    const bodyText = await page.textContent("body");
    assert.ok(!bodyText.includes("Tomato"), "empty state must not contain any invented crop data");
  } finally {
    await context.close();
  }
});

// ------------------------------------------------------------------
// 2. Populated state — real data from the API renders correctly
// ------------------------------------------------------------------

test("populated dashboard shows overview counts and history rows from the real API shape", async () => {
  const { context, page } = await openDashboard({
    summary: jsonRoute({ totalReports: 2, reportsNeedingHumanReview: 1 }),
    list: jsonRoute({ reports: [SAMPLE_REPORT, SAMPLE_REPORT_2], page: 0, size: 10, totalElements: 2, totalPages: 1 }),
  });
  try {
    await page.waitForSelector("#dashHistorySection:not([hidden])", { timeout: 5000 });
    assert.equal(await page.textContent("#dashStatTotal"), "2");
    assert.equal(await page.textContent("#dashStatReview"), "1");

    const rows = await page.$$(".dash-row");
    assert.equal(rows.length, 2, "expected exactly the two reports the API returned, no more no less");

    const firstRowText = await rows[0].textContent();
    assert.ok(firstRowText.includes("Tomato"));
    assert.ok(firstRowText.includes("Early blight"));

    // pagination hidden when there's only one page
    assert.equal(await page.isHidden("#dashPagination"), true);
  } finally {
    await context.close();
  }
});

// ------------------------------------------------------------------
// 3. Report detail — clicking a row fetches and renders GET /api/scan/reports/{id}
// ------------------------------------------------------------------

test("clicking a history row opens the report detail panel with full fields", async () => {
  let detailRequestedId = null;
  const { context, page } = await openDashboard({
    summary: jsonRoute({ totalReports: 1, reportsNeedingHumanReview: 0 }),
    list: jsonRoute({ reports: [SAMPLE_REPORT], page: 0, size: 10, totalElements: 1, totalPages: 1 }),
    detail: (route, url) => {
      detailRequestedId = url.pathname.split("/").pop();
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(SAMPLE_REPORT) });
    },
  });
  try {
    await page.waitForSelector(".dash-row", { timeout: 5000 });
    await page.click(".dash-row");
    await page.waitForSelector("#dashOverlay:not([hidden])", { timeout: 5000 });
    await page.waitForFunction(() => document.getElementById("dashPanelDisease").textContent === "Early blight");

    assert.equal(detailRequestedId, SAMPLE_REPORT.id, "must call GET /api/scan/reports/{id} with the clicked report's real id");
    const panelText = await page.textContent("#dashPanelBody");
    assert.ok(panelText.includes("Brown concentric-ring spots"));
    assert.ok(panelText.includes("Remove affected leaves."));
    assert.ok(panelText.includes("Concentric target-like rings"));

    // closing works
    await page.click("#dashPanelCloseBtn");
    assert.equal(await page.isHidden("#dashOverlay"), true);
  } finally {
    await context.close();
  }
});

// ------------------------------------------------------------------
// 4. Error state — the summary/list call fails
// ------------------------------------------------------------------

test("a failed API call renders the error state with a retry option, not a crash", async () => {
  const { context, page } = await openDashboard({
    summary: jsonRoute({ error: { code: "INTERNAL", message: "boom", retryable: true } }, 500),
    list: jsonRoute({ reports: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }),
  });
  try {
    await page.waitForSelector("#dashError:not([hidden])", { timeout: 5000 });
    assert.equal(await page.isHidden("#dashHistorySection"), true);
    assert.equal(await page.isHidden("#dashEmpty"), true);
    const msg = await page.textContent("#dashErrorMessage");
    assert.ok(msg.includes("boom"), "should surface the FOLIX error envelope's message");
    assert.equal(await page.isVisible("#dashRetryBtn"), true);
  } finally {
    await context.close();
  }
});

// ------------------------------------------------------------------
// 5. Pagination — Next/Prev call the API with the right page params
// ------------------------------------------------------------------

test("pagination requests the next page with correct query params", async () => {
  const requestedPages = [];
  const { context, page } = await openDashboard({
    summary: jsonRoute({ totalReports: 15, reportsNeedingHumanReview: 0 }),
    list: (route) => {
      const url = new URL(route.request().url());
      const p = Number(url.searchParams.get("page") || "0");
      requestedPages.push(p);
      return route.fulfill({
        status: 200, contentType: "application/json",
        body: JSON.stringify({ reports: [SAMPLE_REPORT], page: p, size: 10, totalElements: 15, totalPages: 2 }),
      });
    },
  });
  try {
    await page.waitForSelector("#dashPagination:not([hidden])", { timeout: 5000 });
    await page.click("#dashNextBtn");
    await page.waitForFunction(() => document.getElementById("dashPageLabel").textContent.includes("2 of 2"));
    assert.deepEqual(requestedPages, [0, 1]);
  } finally {
    await context.close();
  }
});
