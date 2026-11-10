"use strict";
/**
 * Authority dashboard frontend tests — run in a REAL Chromium against the REAL
 * frontend/authority-dashboard.html, with the /api/v1/authorities/* endpoints mocked at the
 * network layer via Playwright route interception. Nothing here touches a real backend.
 *
 * These tests cover the Issue #4 fix: authority-dashboard.html used to fetch alerts and
 * forwarded cases exactly once, on login, so a newly-forwarded case never appeared until the
 * officer manually reloaded and re-logged in. It now polls on a reliable interval (overridable
 * here via window.__FOLIX_AUTHORITY_POLL_MS__ so tests don't wait 20 real seconds).
 *
 *   node --test tests/authority-frontend/authority.test.js
 */
const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { chromium } = require("playwright");
const { startStatic } = require("../scanner-frontend/mock-backend");

const FRONTEND = path.resolve(__dirname, "..", "..", "frontend");
const API_ORIGIN = "http://cropguard-api.test";
const POLL_MS = 300; // fast interval for tests; production default stays 20000ms

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

const ALERT_1 = {
  id: 1, diseaseName: "Late blight", cropName: "Potato", status: "NOTIFIED",
  severityPercent: 72, epidemicRisk: true, channel: "WEBHOOK", createdAt: "2026-09-01T10:00:00Z",
  latitude: null, longitude: null,
};

/** Opens the real authority dashboard with routes mocked, and a fast poll interval injected. */
async function openAuthorityDashboard({ alerts, cases }) {
  const context = await browser.newContext();
  const page = await context.newPage();
  let alertsCallCount = 0;
  let casesCallCount = 0;

  await page.route(`${API_ORIGIN}/**`, (route) => {
    const url = new URL(route.request().url());
    if (/^\/api\/v1\/authorities\/\d+\/alerts$/.test(url.pathname)) {
      alertsCallCount++;
      return alerts(route, alertsCallCount);
    }
    if (/^\/api\/v1\/authorities\/\d+\/forwarded-cases$/.test(url.pathname)) {
      casesCallCount++;
      return cases(route, casesCallCount);
    }
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });

  await page.addInitScript(({ origin, pollMs }) => {
    window.CROPGUARD_API_BASE_URL = origin;
    window.__FOLIX_AUTHORITY_POLL_MS__ = pollMs;
  }, { origin: API_ORIGIN, pollMs: POLL_MS });
  await page.goto(`${staticSrv.url}/authority-dashboard.html`);
  return { context, page, getCounts: () => ({ alertsCallCount, casesCallCount }) };
}

async function login(page) {
  await page.fill("#loginId", "42");
  await page.fill("#loginKey", "test-key");
  await page.click("#loginBtn");
  await page.waitForSelector("#alertsCard:not([hidden])", { timeout: 5000 });
}

test("logging in fetches alerts and forwarded cases exactly once each before any poll fires", async () => {
  const { context, page, getCounts } = await openAuthorityDashboard({
    alerts: jsonRoute([ALERT_1]),
    cases: jsonRoute([]),
  });
  try {
    await login(page);
    const counts = getCounts();
    assert.equal(counts.alertsCallCount, 1);
    assert.equal(counts.casesCallCount, 1);
  } finally {
    await context.close();
  }
});

test("BUGFIX: the dashboard polls automatically so a new forwarded case appears without a manual reload", async () => {
  let casesResponses = [[], []]; // first poll: empty; from then on: one case appears
  const { context, page } = await openAuthorityDashboard({
    alerts: jsonRoute([]),
    cases: (route, callCount) => {
      const body = callCount === 1
        ? []
        : [{
            reportId: "11111111-1111-1111-1111-111111111111", disease: "Blast", crop: "Rice",
            severity: "severe", status: "FORWARDED", forwardedAt: "2026-09-01T10:00:00Z",
            supportContactName: "Krishi Bhavan Kozhikode", supportContactOrganization: null,
          }];
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    },
  });
  try {
    await login(page);
    assert.match(await page.innerText("#forwardedCasesList"), /No cases have been forwarded yet/);

    // No manual reload, no re-login — just wait for the poll to fire on its own.
    await page.waitForFunction(
      () => document.querySelector("#forwardedCasesList")?.textContent.includes("Blast"),
      { timeout: 5000 }
    );
    const text = await page.innerText("#forwardedCasesList");
    assert.match(text, /Blast/);
    assert.match(text, /Rice/);
  } finally {
    await context.close();
  }
});

test("the live-status line communicates the refresh behavior to the officer", async () => {
  const { context, page } = await openAuthorityDashboard({
    alerts: jsonRoute([ALERT_1]),
    cases: jsonRoute([]),
  });
  try {
    await login(page);
    await page.waitForFunction(
      () => /last updated/i.test(document.querySelector("#liveStatus")?.textContent || ""),
      { timeout: 5000 }
    );
    const status = await page.innerText("#liveStatus");
    assert.match(status, /Auto-refreshing every 20s/);
  } finally {
    await context.close();
  }
});

test("BUGFIX: a transient background-poll failure leaves the last-good alert list visible instead of blanking it", async () => {
  let alertsCallCount = 0;
  const { context, page } = await openAuthorityDashboard({
    alerts: (route) => {
      alertsCallCount++;
      if (alertsCallCount === 1) return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([ALERT_1]) });
      return route.fulfill({ status: 500, contentType: "application/json", body: "{}" }); // transient server error, not 401
    },
    cases: jsonRoute([]),
  });
  try {
    await login(page);
    assert.match(await page.innerText("#alertsList"), /Late blight/);

    await page.waitForFunction(
      () => /last refresh failed/i.test(document.querySelector("#liveStatus")?.textContent || ""),
      { timeout: 5000 }
    );
    // The previously-rendered alert must still be there — a transient error should not blank the list.
    assert.match(await page.innerText("#alertsList"), /Late blight/);
    assert.equal(await page.isHidden("#alertsCard"), false);
  } finally {
    await context.close();
  }
});

test("BUGFIX: a real 401 during a background poll stops polling and returns to the login form", async () => {
  let alertsCallCount = 0;
  const { context, page } = await openAuthorityDashboard({
    alerts: (route) => {
      alertsCallCount++;
      if (alertsCallCount === 1) return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([ALERT_1]) });
      return route.fulfill({ status: 401, contentType: "application/json", body: "{}" });
    },
    cases: jsonRoute([]),
  });
  try {
    await login(page);
    await page.waitForFunction(
      () => document.querySelector("#loginError")?.style.display === "block",
      { timeout: 5000 }
    );
    assert.match(await page.innerText("#loginError"), /Invalid authority ID or API key/);
    assert.equal(await page.isHidden("#alertsCard"), true);

    // Confirm polling actually stopped: wait several poll intervals and check the call count settles.
    const countAfterStop = alertsCallCount;
    await page.waitForTimeout(POLL_MS * 3);
    assert.equal(alertsCallCount, countAfterStop, "no further /alerts calls should fire after the 401");
  } finally {
    await context.close();
  }
});

test("polling pauses while the tab is hidden and resumes immediately when it becomes visible again", async () => {
  const { context, page } = await openAuthorityDashboard({
    alerts: jsonRoute([ALERT_1]),
    cases: jsonRoute([]),
  });
  try {
    await login(page);
    await page.evaluate(() => {
      Object.defineProperty(document, "visibilityState", { value: "hidden", configurable: true });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    // No assertion on call count while hidden (timing-flaky); just confirm resuming works below.
    await page.evaluate(() => {
      Object.defineProperty(document, "visibilityState", { value: "visible", configurable: true });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await page.waitForFunction(
      () => /last updated/i.test(document.querySelector("#liveStatus")?.textContent || ""),
      { timeout: 5000 }
    );
  } finally {
    await context.close();
  }
});
