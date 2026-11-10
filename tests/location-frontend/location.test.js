"use strict";
/**
 * Stage 6C frontend tests — the REAL frontend files (heatmap.html, location.js, disease-map.js) in a REAL Chromium,
 * against a stateful fake of the location/map API contract (fake-location-api.js).
 *
 *   node --test tests/location-frontend/location.test.js
 *
 * Geolocation is observed by wrapping navigator.geolocation in an init script that COUNTS every request. "Allow"
 * delegates to Chromium's real geolocation (granted permission + a mocked position); "deny" answers PERMISSION_DENIED.
 */
const { test, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { chromium } = require("playwright");
const { startStatic } = require("../scanner-frontend/mock-backend");
const { startFakeLocationApi } = require("./fake-location-api");

const FRONTEND = path.resolve(__dirname, "..", "..", "frontend");
let browser, staticSrv, api;

before(async () => {
  browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH, args: ["--no-sandbox"] } : { args: ["--no-sandbox"] });
  staticSrv = await startStatic(FRONTEND);
  api = await startFakeLocationApi();
});
after(async () => { await browser?.close(); await staticSrv?.close(); await api?.close(); });
beforeEach(() => api.reset());

/**
 * mode: "allow" (real geolocation, permission granted, position mocked), "deny" (PERMISSION_DENIED), "none" (no API).
 * blockedPermission: the Permissions API reports "denied" (without any prompt).
 */
async function open(pagePath, { loggedIn = true, mode = "allow", blockedPermission = false, position = { latitude: 11.25871, longitude: 75.78049 } } = {}) {
  const context = await browser.newContext({ serviceWorkers: "block" });
  if (loggedIn) await context.addCookies([{ name: "FOLIX_SESSION", value: "tok", url: api.url }]);
  if (mode === "allow") {
    await context.grantPermissions(["geolocation"], { origin: staticSrv.url });
    await context.setGeolocation(position);
  }
  // Leaflet / tiles / fonts are external; make their failure immediate and deterministic.
  await context.route(/unpkg\.com|tile\.openstreetmap\.org|fonts\.(googleapis|gstatic)\.com/, (route) => route.abort());
  const page = await context.newPage();
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));
  await page.addInitScript(({ base, mode, blockedPermission }) => {
    window.CROPGUARD_API_BASE_URL = base;
    window.__geoCalls = 0;
    if (mode === "none") {
      Object.defineProperty(navigator, "geolocation", { value: undefined, configurable: true });
    } else {
      const real = navigator.geolocation;
      const count = () => { window.__geoCalls += 1; };
      Object.defineProperty(navigator, "geolocation", {
        configurable: true,
        value: {
          getCurrentPosition(success, error, options) {
            count();
            window.__geoOptions = options;
            if (mode === "deny") { setTimeout(() => error({ code: 1, message: "User denied Geolocation" }), 0); return; }
            real.getCurrentPosition(success, error, options);
          },
          watchPosition() { count(); return 0; },
          clearWatch() {},
        },
      });
    }
    if (blockedPermission) {
      const q = navigator.permissions && navigator.permissions.query ? navigator.permissions.query.bind(navigator.permissions) : null;
      Object.defineProperty(navigator, "permissions", {
        configurable: true,
        value: { query: (d) => (d && d.name === "geolocation" ? Promise.resolve({ state: "denied" }) : q(d)) },
      });
    }
  }, { base: api.url, mode, blockedPermission });
  await page.goto(`${staticSrv.url}/${pagePath}`);
  return { page, context, pageErrors };
}

const geoCalls = (page) => page.evaluate(() => window.__geoCalls);
const locRequests = () => api.log.filter((r) => r.path === "/api/farmer/location");
const decimals = (n) => (String(n).split(".")[1] || "").length;

// ------------------------------------------------------------------ Automatic GPS & UI updates

test("the location card, update area button and old manual contribution text are removed from the page", async () => {
  const { page, context, pageErrors } = await open("heatmap.html");
  await page.waitForLoadState("domcontentloaded");
  assert.equal(await page.$("#locCard"), null, "#locCard must not exist in DOM");
  assert.equal(await page.$("#locEnableBtn"), null, "#locEnableBtn must not exist");
  const text = (await page.innerText("main")).replace(/\s+/g, " ");
  assert.doesNotMatch(text, /Contribute to regional disease insights/i);
  assert.doesNotMatch(text, /Sharing is optional/i);
  assert.doesNotMatch(text, /Sharing is on\. Your saved reports contribute/i);
  assert.doesNotMatch(text, /Reports you saved before turning this on are not included/i);
  assert.deepEqual(pageErrors, []);
  await context.close();
});

test("automatic location request: entering the website automatically fetches GPS coordinates and enables location", async () => {
  const { page, context, pageErrors } = await open("heatmap.html");
  await page.waitForTimeout(500);
  assert.ok((await geoCalls(page)) >= 1, "geolocation requested automatically on load");
  const put = locRequests().find((r) => r.method === "PUT");
  assert.ok(put, "a PUT was made automatically");
  assert.equal(put.body.consent, true);
  assert.ok(decimals(put.body.latitude) <= 2 && decimals(put.body.longitude) <= 2);
  assert.equal(put.body.latitude, 11.26);
  assert.equal(put.body.longitude, 75.78);
  assert.deepEqual(pageErrors, []);
  await context.close();
});

test("a browser without geolocation does not crash the map", async () => {
  const { page, context, pageErrors } = await open("heatmap.html", { mode: "none" });
  await page.waitForSelector("#mapStatus");
  assert.deepEqual(pageErrors, []);
  await context.close();
});

test("denied permission does not crash the page or prevent the map from loading", async () => {
  const { page, context, pageErrors } = await open("heatmap.html", { mode: "deny" });
  await page.waitForSelector("#mapStatus");
  assert.deepEqual(pageErrors, []);
  await context.close();
});

test("disease trend graph is removed from the map page", async () => {
  const { page, context, pageErrors } = await open("heatmap.html");
  await page.waitForLoadState("domcontentloaded");
  assert.equal(await page.$("#diseaseTrendSvg"), null, "trend SVG should be removed");
  const text = await page.innerText("main");
  assert.doesNotMatch(text, /Disease Trend \(Last 30 Days\)/i);
  assert.deepEqual(pageErrors, []);
  await context.close();
});

// ------------------------------------------------------------------ Disease Map states

const SAMPLE_MAP = {
  lookbackDays: 30, minimumReports: 3, areaSizeDegrees: 0.1,
  regions: [
    { centerLatitude: 11.25, centerLongitude: 75.75, totalReports: 7, diseases: [
      { crop: "Tomato", disease: "Early blight", reportCount: 4, severity: { mild: 1, moderate: 2, severe: 1, unknown: 0 } },
      { crop: "Tomato", disease: "Leaf curl", reportCount: 3, severity: { mild: 3, moderate: 0, severe: 0, unknown: 0 } },
    ] },
    { centerLatitude: 12.35, centerLongitude: 76.15, totalReports: 3, diseases: [
      { crop: "Rice", disease: "Blast", reportCount: 3, severity: { mild: 0, moderate: 0, severe: 3, unknown: 0 } },
    ] },
  ],
};

test("map empty state: a clear, calm explanation and no list", async () => {
  const { page, context, pageErrors } = await open("heatmap.html");
  await page.waitForSelector("#mapEmpty:not([hidden])");
  assert.match(await page.innerText("#mapEmpty"), /No regions to show yet/);
  assert.match(await page.innerText("#mapEmpty"), /enough different farmers/);
  assert.equal(await page.isHidden("#regionListSection"), true);
  assert.equal(await page.isHidden("#mapError"), true);
  assert.deepEqual(pageErrors, []);
  await context.close();
});

test("map populated state: regions come from the API, aggregated only, and render even when the map library is blocked", async () => {
  api.state.map = SAMPLE_MAP;
  const { page, context, pageErrors } = await open("heatmap.html");
  await page.waitForSelector("#regionListSection:not([hidden])");

  const regions = await page.$$('[data-testid="region"]');
  assert.equal(regions.length, 2);
  const first = (await regions[0].innerText()).replace(/\s+/g, " ");
  assert.match(first, /Area around 11\.25° N, 75\.75° E/);
  assert.match(first, /7 reports in this area/);
  assert.match(first, /Early blight on Tomato — 4 reports/);
  assert.match(first, /1 mild · 2 moderate · 1 severe/);
  assert.match(await page.innerText("#mapStatus"), /Showing 2 regions from the last 30 days/);
  assert.equal(await page.isHidden("#mapEmpty"), true);

  // Leaflet was blocked: the data is still fully available and the reason is explained separately
  assert.equal(await page.isVisible("#mapLibNotice"), true);
  assert.match(await page.innerText("#mapLibNotice"), /interactive map couldn't load/);

  // the map request is anonymous: no session cookie is sent to the public endpoint
  const mapReq = api.log.find((r) => r.path === "/api/folix/disease-map");
  assert.equal(mapReq.hasCookie, false);
  assert.deepEqual(pageErrors, []);
  await context.close();
});

test("map data is rendered as text, never as HTML", async () => {
  api.state.map = { ...SAMPLE_MAP, regions: [{ centerLatitude: 11.25, centerLongitude: 75.75, totalReports: 3, diseases: [
    { crop: "<b>Tomato</b>", disease: "<img src=x onerror=window.__pwned=1>", reportCount: 3, severity: { mild: 3, moderate: 0, severe: 0, unknown: 0 } },
  ] }] };
  const { page, context } = await open("heatmap.html");
  await page.waitForSelector("#regionListSection:not([hidden])");
  assert.equal(await page.$('#regionList img, #regionList b'), null, "no injected elements");
  assert.equal(await page.evaluate(() => window.__pwned), undefined);
  assert.match(await page.innerText("#regionList"), /<img src=x/);
  await context.close();
});

test("map error state: shown when the server fails, with a working retry", async () => {
  api.state.mapStatus = 500;
  const { page, context } = await open("heatmap.html");
  await page.waitForSelector("#mapError:not([hidden])");
  assert.match(await page.innerText("#mapError"), /couldn't be loaded/);
  assert.match(await page.innerText("#mapError"), /scans and reports are not affected/);

  api.state.mapStatus = 200;
  api.state.map = SAMPLE_MAP;
  await page.click("#mapRetryBtn");
  await page.waitForSelector("#regionListSection:not([hidden])");
  assert.equal(await page.isHidden("#mapError"), true);
  await context.close();
});

test("the page never renders coordinates other than area centres, and shows no farmer identity", async () => {
  api.state.map = SAMPLE_MAP;
  api.state.location = { lat: 11.25, lng: 75.75 };
  const { page, context } = await open("heatmap.html");
  await page.waitForSelector("#regionListSection:not([hidden])");
  const body = await page.innerText("main");
  assert.doesNotMatch(body, /asha@example\.com|Asha Nair/, "no account details in the map page body");
  for (const m of body.matchAll(/(\d+\.\d+)°/g)) {
    assert.ok(decimals(m[1]) <= 2, "coordinates are shown at area precision only: " + m[0]);
  }
  await context.close();
});
