"use strict";
/**
 * Stage 6I Part 3B Nearby Farmer Alerts & Dashboard Trigger frontend tests.
 * Runs in Chromium against the real HTML and JS with mocked API routes.
 *
 *   $env:CHROME_PATH="C:\Program Files\Google\Chrome\Application\chrome.exe"; node --test tests/farmer-alerts-frontend/farmer-alerts.test.js
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
  browser = await chromium.launch(
    process.env.CHROME_PATH
      ? { executablePath: process.env.CHROME_PATH, args: ["--no-sandbox"] }
      : { args: ["--no-sandbox"] }
  );
  staticSrv = await startStatic(FRONTEND);
});

after(async () => {
  await browser?.close();
  await staticSrv?.close();
});

function jsonRoute(json, status = 200) {
  return (route) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(json) });
}

const SAMPLE_ALERT = {
  alertId: 101,
  diseaseName: "Late Blight",
  cropName: "Potato",
  regionDescription: "Nearby area",
  epidemicRisk: true,
  createdAt: new Date(Date.now() - 3600000).toISOString(),
  expiresAt: new Date(Date.now() + 6 * 86400000).toISOString(),
};

const SAMPLE_ALERT_2 = {
  alertId: 102,
  diseaseName: "Tomato Leaf Curl",
  cropName: "Tomato",
  regionDescription: "Nearby area",
  epidemicRisk: true,
  createdAt: new Date(Date.now() - 7200000).toISOString(),
  expiresAt: new Date(Date.now() + 5 * 86400000).toISOString(),
};

const SAMPLE_EPIDEMIC_REPORT = {
  id: "33333333-3333-3333-3333-333333333333",
  createdAt: "2026-09-01T10:15:30Z",
  crop: "Potato",
  plantPart: "leaf",
  disease: "Late blight",
  severity: "severe",
  confidence: "high",
  symptoms: ["Water-soaked dark lesions", "White fungal growth under leaves"],
  reasoning: "High humidity and cool temps accelerate late blight spread rapidly.",
  recommendedActions: ["Destroy infected plants.", "Alert neighboring farms immediately."],
  needsHumanReview: false,
  epidemicRisk: true,
  status: "RECEIVED",
};

const SAMPLE_NON_EPIDEMIC_REPORT = {
  id: "44444444-4444-4444-4444-444444444444",
  createdAt: "2026-09-02T11:20:00Z",
  crop: "Tomato",
  plantPart: "fruit",
  disease: "Sunscald",
  severity: "mild",
  confidence: "high",
  symptoms: ["White blistered patches on exposed fruits"],
  reasoning: "Direct intense sunlight without foliage cover causes sunscald.",
  recommendedActions: ["Maintain adequate canopy cover."],
  needsHumanReview: false,
  epidemicRisk: false,
  status: "RECEIVED",
};

async function openAlertsPage(routes) {
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.route(`${API_ORIGIN}/**`, (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/farmer/alerts") {
      return routes.alerts ? routes.alerts(route) : route.fulfill({ status: 200, body: "[]" });
    }
    if (url.pathname === "/api/auth/session") {
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ authenticated: true, farmer: { id: "f1", name: "Ramesh" } }) });
    }
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });

  await page.addInitScript((origin) => { window.CROPGUARD_API_BASE_URL = origin; }, API_ORIGIN);
  await page.goto(`${staticSrv.url}/farmer-alerts.html`);
  return { context, page };
}

async function openDashboardPage(routes) {
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.route(`${API_ORIGIN}/**`, (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/scan/reports/summary") {
      return routes.summary ? routes.summary(route) : route.fulfill({ status: 200, body: JSON.stringify({ totalReports: 1, reportsNeedingHumanReview: 0 }) });
    }
    if (url.pathname === "/api/scan/reports") {
      return routes.list ? routes.list(route) : route.fulfill({ status: 200, body: JSON.stringify({ reports: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }) });
    }
    if (/^\/api\/scan\/reports\/[^/]+$/.test(url.pathname)) {
      return routes.detail ? routes.detail(route, url) : route.fulfill({ status: 404, body: "{}" });
    }
    if (/^\/api\/farmer\/alerts\/trigger\/[^/]+$/.test(url.pathname)) {
      return routes.trigger ? routes.trigger(route, url) : route.fulfill({ status: 200, body: JSON.stringify({ reportId: "33333333-3333-3333-3333-333333333333", alertsSent: 2 }) });
    }
    if (url.pathname === "/api/farmer/reports/forward/contacts") {
      return route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
    }
    if (url.pathname.startsWith("/api/farmer/reports/forward/status/")) {
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ forwarded: false }) });
    }
    if (url.pathname === "/api/auth/session") {
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ authenticated: true, farmer: { id: "f1", name: "Ramesh" } }) });
    }
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });

  await page.addInitScript((origin) => { window.CROPGUARD_API_BASE_URL = origin; }, API_ORIGIN);
  await page.goto(`${staticSrv.url}/dashboard.html`);
  return { context, page };
}

// ==================================================================
// 1. Nearby Farmer Alerts page tests
// ==================================================================

test("farmer-alerts: empty list renders the genuine empty state without mock data", async () => {
  const { context, page } = await openAlertsPage({
    alerts: jsonRoute([]),
  });
  try {
    await page.waitForSelector("#faEmpty:not([hidden])", { timeout: 5000 });
    assert.equal(await page.isHidden("#faList"), true);
    assert.equal(await page.isHidden("#faError"), true);
    assert.equal(await page.isHidden("#faAuthRequired"), true);
  } finally {
    await context.close();
  }
});

test("farmer-alerts: populated list renders alerts conforming to privacy contract", async () => {
  const { context, page } = await openAlertsPage({
    alerts: jsonRoute([SAMPLE_ALERT, SAMPLE_ALERT_2]),
  });
  try {
    await page.waitForSelector("#faList:not([hidden])", { timeout: 5000 });
    assert.equal(await page.textContent("#faCount"), "2 alerts");

    const cards = await page.$$(".fa-card");
    assert.equal(cards.length, 2);

    const firstCardText = await cards[0].textContent();
    assert.ok(firstCardText.includes("Late Blight"));
    assert.ok(firstCardText.includes("Potato"));
    assert.ok(firstCardText.includes("Nearby area"));
    assert.ok(firstCardText.includes("Epidemic risk"));

    // Privacy contract: no farmer ID, no GPS coordinates, no scan IDs, no raw alert IDs exposed in text
    assert.ok(!firstCardText.includes("101"));
    assert.ok(!firstCardText.includes("latitude"));
    assert.ok(!firstCardText.includes("gps"));
    assert.ok(!firstCardText.includes("sender"));
  } finally {
    await context.close();
  }
});

test("farmer-alerts: 401 unauthenticated renders login required prompt", async () => {
  const { context, page } = await openAlertsPage({
    alerts: (route) => route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ error: { code: "UNAUTHENTICATED", message: "Not logged in" } }) }),
  });
  try {
    await page.waitForSelector("#faAuthRequired:not([hidden])", { timeout: 5000 });
    assert.equal(await page.isHidden("#faList"), true);
    assert.equal(await page.isHidden("#faLoading"), true);
    assert.equal(await page.isHidden("#faError"), true);
  } finally {
    await context.close();
  }
});

test("farmer-alerts: 500 error renders error state with retry button", async () => {
  let callCount = 0;
  const { context, page } = await openAlertsPage({
    alerts: (route) => {
      callCount++;
      if (callCount === 1) {
        return route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ error: { message: "Server error" } }) });
      }
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([SAMPLE_ALERT]) });
    },
  });
  try {
    await page.waitForSelector("#faError:not([hidden])", { timeout: 5000 });
    assert.equal(await page.textContent("#faErrorMessage"), "Server error");

    // Click retry
    await page.click("#faRetryBtn");
    await page.waitForSelector("#faList:not([hidden])", { timeout: 5000 });
    assert.equal(await page.textContent("#faCount"), "1 alert");
  } finally {
    await context.close();
  }
});

test("farmer-alerts: filters dynamically by crop, disease and resets cleanly", async () => {
  const { context, page } = await openAlertsPage({
    alerts: jsonRoute([SAMPLE_ALERT, SAMPLE_ALERT_2]),
  });
  try {
    await page.waitForSelector("#faList:not([hidden])", { timeout: 5000 });
    assert.equal(await page.textContent("#faActiveCount"), "2 Active");

    // Select crop Potato
    await page.selectOption("#faFilterCrops", "Potato");
    let cards = await page.$$(".fa-card");
    assert.equal(cards.length, 1);
    assert.ok((await cards[0].textContent()).includes("Potato"));

    // Select crop Tomato
    await page.selectOption("#faFilterCrops", "Tomato");
    cards = await page.$$(".fa-card");
    assert.equal(cards.length, 1);
    assert.ok((await cards[0].textContent()).includes("Tomato"));

    // Click Reset button
    await page.click("#faResetBtn");
    cards = await page.$$(".fa-card");
    assert.equal(cards.length, 2);
  } finally {
    await context.close();
  }
});

test("farmer-alerts: clicking an alert card opens detail modal with real alert information", async () => {
  const { context, page } = await openAlertsPage({
    alerts: jsonRoute([SAMPLE_ALERT]),
  });
  try {
    await page.waitForSelector("#faList:not([hidden])", { timeout: 5000 });
    const card = await page.$(".fa-card");
    assert.ok(card);

    // Modal is initially hidden
    assert.equal(await page.isHidden("#faDetailModal"), true);

    // Click alert card
    await card.click();
    await page.waitForSelector("#faDetailModal:not([hidden])", { timeout: 5000 });
    assert.equal(await page.textContent("#faModalTitle"), "Late Blight");
    assert.equal(await page.textContent("#faModalCrop"), "Potato");

    // Click close button
    await page.click("#faModalCloseBtn");
    await page.waitForSelector("#faDetailModal", { state: "hidden", timeout: 5000 });
    assert.equal(await page.isHidden("#faDetailModal"), true);
  } finally {
    await context.close();
  }
});

test("farmer-alerts: sort dropdown orders alerts by timestamp", async () => {
  const { context, page } = await openAlertsPage({
    alerts: jsonRoute([SAMPLE_ALERT, SAMPLE_ALERT_2]),
  });
  try {
    await page.waitForSelector("#faList:not([hidden])", { timeout: 5000 });

    // Initially Latest (SAMPLE_ALERT is 1h ago, SAMPLE_ALERT_2 is 2h ago)
    let cards = await page.$$(".fa-card");
    assert.ok((await cards[0].textContent()).includes("Late Blight"));
    assert.ok((await cards[1].textContent()).includes("Tomato Leaf Curl"));

    // Sort by Oldest
    await page.selectOption("#faSortSelect", "OLDEST");
    cards = await page.$$(".fa-card");
    assert.ok((await cards[0].textContent()).includes("Tomato Leaf Curl"));
    assert.ok((await cards[1].textContent()).includes("Late Blight"));
  } finally {
    await context.close();
  }
});

// ==================================================================
// 2. Dashboard trigger integration tests
// ==================================================================

test("dashboard: epidemic-risk report shows alert trigger section; non-epidemic hides it", async () => {
  let requestedDetail = null;
  const { context, page } = await openDashboardPage({
    summary: jsonRoute({ totalReports: 2, reportsNeedingHumanReview: 0 }),
    list: jsonRoute({ reports: [SAMPLE_EPIDEMIC_REPORT, SAMPLE_NON_EPIDEMIC_REPORT], page: 0, size: 10, totalElements: 2, totalPages: 1 }),
    detail: (route, url) => {
      const id = url.pathname.split("/").pop();
      requestedDetail = id;
      if (id === SAMPLE_EPIDEMIC_REPORT.id) {
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(SAMPLE_EPIDEMIC_REPORT) });
      }
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(SAMPLE_NON_EPIDEMIC_REPORT) });
    },
  });

  try {
    await page.waitForSelector("#dashHistorySection:not([hidden])", { timeout: 5000 });
    const rows = await page.$$(".dash-row");
    assert.equal(rows.length, 2);

    // Open first report (epidemic-risk: true)
    await rows[0].click();
    await page.waitForSelector("#dashAlertTriggerSection:not([hidden])", { timeout: 5000 });
    assert.equal(await page.textContent("#dashAlertTriggerBtn"), "Send alert");

    // Close panel
    await page.click("#dashPanelCloseBtn");
    await page.waitForSelector("#dashOverlay", { state: "hidden", timeout: 5000 });

    // Open second report (epidemic-risk: false)
    await rows[1].click();
    await page.waitForSelector("#dashPanelDisease", { timeout: 5000 });
    assert.equal(await page.isHidden("#dashAlertTriggerSection"), true);
  } finally {
    await context.close();
  }
});

test("dashboard: clicking Send alert triggers POST and shows success feedback", async () => {
  let triggerCalled = false;
  let triggerReportId = null;

  const { context, page } = await openDashboardPage({
    summary: jsonRoute({ totalReports: 1, reportsNeedingHumanReview: 0 }),
    list: jsonRoute({ reports: [SAMPLE_EPIDEMIC_REPORT], page: 0, size: 10, totalElements: 1, totalPages: 1 }),
    detail: (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(SAMPLE_EPIDEMIC_REPORT) }),
    trigger: (route, url) => {
      triggerCalled = true;
      triggerReportId = url.pathname.split("/").pop();
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ reportId: triggerReportId, alertsSent: 4 }) });
    },
  });

  try {
    await page.waitForSelector("#dashHistorySection:not([hidden])", { timeout: 5000 });
    const rows = await page.$$(".dash-row");
    await rows[0].click();

    await page.waitForSelector("#dashAlertTriggerBtn:not([disabled])", { timeout: 5000 });
    await page.click("#dashAlertTriggerBtn");

    await page.waitForSelector("#dashAlertTriggerMessage.is-success", { timeout: 5000 });
    assert.equal(triggerCalled, true);
    assert.equal(triggerReportId, SAMPLE_EPIDEMIC_REPORT.id);
    assert.equal(await page.textContent("#dashAlertTriggerBtn"), "Sent");
    assert.ok((await page.textContent("#dashAlertTriggerMessage")).includes("Alert sent to 4 nearby farmers."));
    assert.equal(await page.isHidden("#dashAlertViewLink"), false);
  } finally {
    await context.close();
  }
});
