"use strict";
/**
 * Authority role routing + Authority area tests — REAL Chromium against the REAL frontend/authority.html,
 * with /api/v1/authorities/* mocked at the network layer (nothing touches a real backend).
 *
 *   node --test tests/authority-frontend/authority-role.test.js
 */
const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
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
after(async () => { await browser?.close(); await staticSrv?.close(); });

const ALERT = { id: 1, diseaseName: "Late blight", cropName: "Potato", status: "NOTIFIED", severityPercent: 72,
  epidemicRisk: true, channel: "WEBHOOK", createdAt: "2026-09-01T10:00:00Z", latitude: null, longitude: null };

async function open(pagePath) {
  const context = await browser.newContext({ serviceWorkers: "block" });
  const page = await context.newPage();
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));
  await page.route(`${API_ORIGIN}/**`, (route) => {
    const url = new URL(route.request().url());
    if (/^\/api\/v1\/authorities\/\d+\/alerts$/.test(url.pathname)) {
      if (route.request().headers()["x-authority-api-key"] !== "good-key") {
        return route.fulfill({ status: 401, contentType: "application/json", body: "{}" });
      }
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([ALERT]) });
    }
    if (/^\/api\/v1\/authorities\/\d+\/forwarded-cases$/.test(url.pathname)) {
      return route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
    }
    if (/^\/api\/v1\/authorities\/\d+\/farmers$/.test(url.pathname)) {
      return route.fulfill({
        status: 200, contentType: "application/json",
        body: JSON.stringify([
          { id: "F-101", name: "Asha Nair", village: "Koorachundu", district: "Kozhikode", crops: ["Rubber", "Pepper"], phone: "+91 94470 12345", email: "asha@example.com", status: "Active", lastReport: "2 days ago" },
          { id: "F-102", name: "Bala Menon", village: "Thamarassery", district: "Kozhikode", crops: ["Banana", "Coconut"], phone: "+91 98460 54321", email: "bala@example.com", status: "Active", lastReport: "Yesterday" }
        ])
      });
    }
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });
  await page.addInitScript((origin) => { window.CROPGUARD_API_BASE_URL = origin; }, API_ORIGIN);
  await page.goto(`${staticSrv.url}/${pagePath}`);
  return { page, context, pageErrors };
}

async function signIn(page, key = "good-key") {
  await page.fill("#authorityId", "42");
  await page.fill("#authorityKey", key);
  await page.click("#authoritySignInSubmit");
}

test("before sign-in only the sign-in form exists: no Authority views and no farmer features in the DOM", async () => {
  const { page, context, pageErrors } = await open("authority.html");
  await page.waitForSelector("#authoritySignInForm");
  assert.equal(await page.$("#authorityAlertsList"), null);
  assert.equal(await page.$(".authority-options"), null);
  assert.equal(await page.locator(".header-nav a").count(), 0, "no nav links for an unverified visitor");
  assert.deepEqual(pageErrors, []);
  await context.close();
});

test("a bad key is rejected and the visitor stays on sign-in", async () => {
  const { page, context } = await open("authority.html");
  await signIn(page, "wrong");
  await page.waitForSelector("#authoritySignInError:not([hidden])");
  assert.match(await page.innerText("#authoritySignInError"), /Invalid authority ID or API key/);
  assert.equal(await page.$(".authority-options"), null);
  await context.close();
});

test("a valid Authority sees exactly Your Cases, Forwarded Cases, and Farmers — and none of the farmer pages", async () => {
  const { page, context, pageErrors } = await open("authority.html");
  await signIn(page);
  await page.waitForSelector(".authority-options");

  // textContent, not innerText: the nav labels are upper-cased by CSS text-transform.
  const labels = await page.locator(".header-nav a").evaluateAll((as) => as.map((a) => a.textContent));
  assert.deepEqual(labels.map((s) => s.trim()), ["Your Cases", "Forwarded Cases", "Farmers"]);

  const hrefs = await page.locator("a[href]").evaluateAll((as) => as.map((a) => a.getAttribute("href")));
  for (const farmerPage of ["scanner.html", "dashboard.html", "heatmap.html", "farmer-alerts.html", "profile.html"]) {
    assert.equal(hrefs.some((h) => h.includes(farmerPage)), false, `no link to ${farmerPage}`);
  }

  await page.click(".header-nav a[data-nav='cases']");
  await page.waitForSelector("#authorityAlertsList .authority-alert");
  assert.match(await page.innerText("#authorityAlertsList"), /Late blight — Potato/);
  assert.equal((await page.locator(".header-nav a.nav-active").textContent()).trim(), "Your Cases");

  await page.click(".header-nav a[data-nav='forward']");
  await page.waitForSelector("h1:has-text('Forwarded cases')");
  assert.equal(await page.$("#authorityAlertsList"), null, "Your Cases view is unmounted");
  assert.equal((await page.locator(".header-nav a.nav-active").textContent()).trim(), "Forwarded Cases");

  await page.click(".header-nav a[data-nav='farmers']");
  await page.waitForSelector("#authorityFarmersList .authority-farmer-card");
  assert.equal((await page.locator(".header-nav a.nav-active").textContent()).trim(), "Farmers");
  assert.match(await page.innerText("#authorityFarmersList"), /Asha Nair/);
  assert.match(await page.innerText("#authorityFarmersList"), /Bala Menon/);

  assert.deepEqual(pageErrors, []);
  await context.close();
});

test("signing out returns to the sign-in gate and removes the Authority views", async () => {
  const { page, context } = await open("authority.html");
  await signIn(page);
  await page.waitForSelector(".authority-options");
  await page.click(".authority-account .auth-logout");
  await page.waitForSelector("#authoritySignInForm");
  assert.equal(await page.$(".authority-options"), null);
  await context.close();
});

test("authority registration: visitor can toggle between separate registration and sign-in", async () => {
  const { page, context } = await open("authority.html");
  await page.waitForSelector("#authoritySignInForm");
  await page.click("#authorityGoToRegister");
  await page.waitForSelector("#authorityRegisterForm");
  assert.equal(await page.$("#authoritySignInForm"), null, "sign-in form is replaced by registration form");

  await page.click("#authorityGoToSignIn");
  await page.waitForSelector("#authoritySignInForm");
  assert.equal(await page.$("#authorityRegisterForm"), null, "registration form is replaced by sign-in form");
  await context.close();
});

test("authority.html does not load the farmer session client, and the User pages are unchanged by it", async () => {
  const html = fs.readFileSync(path.join(FRONTEND, "authority.html"), "utf8");
  assert.equal(/auth\.js|auth-pages\.js/.test(html), false);
  const login = fs.readFileSync(path.join(FRONTEND, "login.html"), "utf8");
  assert.match(login, /window\.location\.href = "authority\.html"/);
  assert.match(login, /id="loginForm"/, "the farmer login form is still there");
});
