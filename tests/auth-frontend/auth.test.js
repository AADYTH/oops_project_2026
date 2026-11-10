"use strict";
/**
 * Stage 6A frontend tests — the REAL frontend files in a REAL Chromium, against a stateful fake of the account
 * API (fake-auth-api.js) so HttpOnly cookies, credentialed CORS and redirects behave like the real thing.
 *
 *   node --test tests/auth-frontend/auth.test.js
 */
const { test, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");
const { startStatic } = require("../scanner-frontend/mock-backend");
const { startFakeApi } = require("./fake-auth-api");

const FRONTEND = path.resolve(__dirname, "..", "..", "frontend");
let browser, staticSrv, api;

before(async () => {
  browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH, args: ["--no-sandbox"] } : { args: ["--no-sandbox"] });
  staticSrv = await startStatic(FRONTEND);
  api = await startFakeApi();
});
after(async () => { await browser?.close(); await staticSrv?.close(); await api?.close(); });
beforeEach(() => api.reset());

async function open(pagePath, context) {
  context = context || await browser.newContext({ serviceWorkers: "block" });
  const page = await context.newPage();
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));
  await page.addInitScript((base) => { window.CROPGUARD_API_BASE_URL = base; }, api.url);
  await page.goto(`${staticSrv.url}/${pagePath}`);
  return { page, context, pageErrors };
}

const sessionCookie = async (context) => (await context.cookies()).find((c) => c.name === "FOLIX_SESSION");

async function loginVia(page, email, password) {
  // login.html now opens on a "Farmer / User" vs "Authority" chooser; pick "Farmer / User" to
  // reveal the actual login form, if that chooser is present (it may already be dismissed, e.g.
  // for an already-authenticated session).
  const chooseUser = page.locator("#chooseUserBtn");
  if (await chooseUser.count()) {
    try { await chooseUser.click({ timeout: 1000 }); } catch (e) { /* already past the chooser */ }
  }
  await page.fill("#loginEmail", email);
  await page.fill("#loginPassword", password);
  await page.click("#loginSubmit");
}

// ------------------------------------------------------------------ registration

test("registering creates the account, logs in, and lands on the Dashboard with an HttpOnly cookie", async () => {
  const { page, context, pageErrors } = await open("register.html");
  await page.fill("#regName", "Asha Nair");
  await page.fill("#regEmail", "Asha@Example.com");
  await page.fill("#regPassword", "correct horse 42");
  await page.fill("#regConfirm", "correct horse 42");
  await page.click("#registerSubmit");
  await page.waitForURL("**/dashboard.html");

  assert.equal(api.accounts.size, 1);
  assert.deepEqual(api.log.filter((r) => r.path.startsWith("/api/auth/") && r.method === "POST").map((r) => r.path), ["/api/auth/register", "/api/auth/login"]);
  const c = await sessionCookie(context);
  assert.ok(c, "session cookie set by the server");
  assert.equal(c.httpOnly, true);
  assert.equal(await page.evaluate(() => document.cookie.includes("FOLIX_SESSION")), false, "page JavaScript cannot see the session cookie");
  assert.deepEqual(pageErrors, []);
  await context.close();
});

test("registration form: mismatched passwords and short passwords are caught before any request", async () => {
  const { page, context } = await open("register.html");
  await page.fill("#regName", "Asha");
  await page.fill("#regEmail", "asha@example.com");
  await page.fill("#regPassword", "short");
  await page.fill("#regConfirm", "different");
  await page.click("#registerSubmit");
  assert.match(await page.innerText("#regPasswordError"), /at least 8/);
  assert.match(await page.innerText("#regConfirmError"), /don't match/);
  assert.equal(await page.getAttribute("#regPassword", "aria-invalid"), "true");
  assert.equal(api.log.filter((r) => r.path === "/api/auth/register").length, 0);
  await context.close();
});

test("a duplicate email shows the server's message and creates nothing new", async () => {
  api.addAccount("Existing", "asha@example.com", "whatever pass 1");
  const { page, context } = await open("register.html");
  await page.fill("#regName", "Asha");
  await page.fill("#regEmail", "asha@example.com");
  await page.fill("#regPassword", "correct horse 42");
  await page.fill("#regConfirm", "correct horse 42");
  await page.click("#registerSubmit");
  await page.waitForSelector("#registerError:not([hidden])");
  assert.match(await page.innerText("#registerError"), /already exists/i);
  assert.equal(api.accounts.size, 1);
  assert.equal(await sessionCookie(context), undefined);
  await context.close();
});

test("server-side field errors are shown next to the right fields", async () => {
  const { page, context } = await open("register.html");
  await page.fill("#regName", "Asha");
  await page.fill("#regEmail", "not-an-email");        // passes the browser-side check, fails the server's
  await page.fill("#regPassword", "correct horse 42");
  await page.fill("#regConfirm", "correct horse 42");
  await page.click("#registerSubmit");
  await page.waitForSelector("#regEmailError:not([hidden])");
  assert.match(await page.innerText("#regEmailError"), /valid email/i);
  await context.close();
});

// ------------------------------------------------------------------ login / logout

test("login with valid credentials redirects to the Dashboard, which shows the saved scans", async () => {
  api.addAccount("Asha Nair", "asha@example.com", "correct horse 42");
  const { page, context } = await open("login.html?next=dashboard.html");
  await loginVia(page, "asha@example.com", "correct horse 42");
  await page.waitForURL("**/dashboard.html");
  await page.waitForSelector("#dashHistorySection:not([hidden])");
  assert.match(await page.innerText("#dashHistoryList"), /Early blight/);
  assert.equal((await sessionCookie(context)).httpOnly, true);
  await context.close();
});

test("invalid credentials show a generic error, keep the user on the page, and set no cookie", async () => {
  api.addAccount("Asha Nair", "asha@example.com", "correct horse 42");
  const { page, context } = await open("login.html");
  await loginVia(page, "asha@example.com", "wrong password");
  await page.waitForSelector("#loginError:not([hidden])");
  assert.equal(await page.innerText("#loginError"), "Incorrect email or password.");
  assert.match(page.url(), /login\.html/);
  assert.equal(await sessionCookie(context), undefined);
  await context.close();
});

test("login never puts anything in localStorage or sessionStorage, and no token reaches page JavaScript", async () => {
  api.addAccount("Asha Nair", "asha@example.com", "correct horse 42");
  const { page, context } = await open("login.html?next=profile.html");
  await loginVia(page, "asha@example.com", "correct horse 42");
  await page.waitForURL("**/profile.html");
  await page.waitForSelector("#profileCard:not([hidden])");
  const stored = await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage), cookie: document.cookie }));
  assert.deepEqual(stored, { local: [], session: [], cookie: "" });
  for (const f of ["auth.js", "auth-pages.js"]) {
    const src = fs.readFileSync(path.join(FRONTEND, f), "utf8").replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, "");
    assert.equal(/localStorage|sessionStorage|document\.cookie/.test(src), false, `${f} must not use browser storage or cookies`);
  }
  await context.close();
});

test("the ?next= redirect cannot be abused: absolute and odd URLs fall back to the Dashboard", async () => {
  for (const evil of ["https://evil.example/phish", "//evil.example", "javascript:alert(1)", "../secret.html", "a/b.html"]) {
    api.reset();
    api.addAccount("Asha Nair", "asha@example.com", "correct horse 42");
    const { page, context } = await open(`login.html?next=${encodeURIComponent(evil)}`);
    await loginVia(page, "asha@example.com", "correct horse 42");
    await page.waitForURL(`${staticSrv.url}/dashboard.html`);
    await context.close();
  }
});

test("next=close (the Scanner's new-tab login) stays put and tells the farmer to go back", async () => {
  api.addAccount("Asha Nair", "asha@example.com", "correct horse 42");
  const { page, context } = await open("login.html?next=close");
  await loginVia(page, "asha@example.com", "correct horse 42");
  await page.waitForSelector("#loginDone:not([hidden])");
  assert.match(await page.innerText("#loginDoneText"), /go back to your scan/i);
  assert.match(page.url(), /login\.html/);
  await context.close();
});

test("the header shows the farmer's name after login and Log out really ends the server session", async () => {
  api.addAccount("Asha Nair", "asha@example.com", "correct horse 42");
  const { page, context } = await open("login.html?next=index.html");
  await loginVia(page, "asha@example.com", "correct horse 42");
  await page.waitForURL("**/index.html");
  await page.waitForSelector("#authWidget a.auth-name");
  assert.equal(await page.innerText("#authWidget a.auth-name"), "Asha");
  assert.equal(api.sessions.size, 1);

  const token = (await sessionCookie(context)).value;
  await page.click("#authWidget .auth-logout");
  await page.waitForSelector("#authWidget a.auth-login");
  assert.equal(api.sessions.size, 0, "server-side session deleted");
  assert.equal(await sessionCookie(context), undefined, "cookie cleared");
  assert.equal(api.sessions.has(token), false);
  await context.close();
});

test("logging out on the Dashboard sends the farmer to login, and the Dashboard is then locked", async () => {
  api.addAccount("Asha Nair", "asha@example.com", "correct horse 42");
  const { page, context } = await open("login.html");
  await loginVia(page, "asha@example.com", "correct horse 42");
  await page.waitForURL("**/dashboard.html");
  await page.waitForSelector("#authWidget .auth-logout");
  await page.click("#authWidget .auth-logout");
  await page.waitForURL("**/login.html");
  await page.goto(`${staticSrv.url}/dashboard.html`);
  await page.waitForSelector("#dashAuthRequired:not([hidden])");
  await context.close();
});

// ------------------------------------------------------------------ dashboard states

test("Dashboard when logged out: the genuine 'log in' state, no report data, links that keep the destination", async () => {
  const { page, context } = await open("dashboard.html");
  await page.waitForSelector("#dashAuthRequired:not([hidden])");
  assert.equal(await page.isVisible("#dashHistorySection"), false);
  assert.equal(await page.isVisible("#dashOverview"), false);
  assert.equal(await page.isVisible("#dashError"), false, "not shown as a scary error");
  assert.equal(await page.getAttribute("#dashAuthRequired a.btn-primary", "href"), "login.html?next=dashboard.html");
  assert.equal(await page.getAttribute("#dashAuthRequired a.btn-secondary", "href"), "register.html?next=dashboard.html");
  await context.close();
});

test("Dashboard when a session expires while the page is open: opening a report falls back to the login state", async () => {
  api.addAccount("Asha Nair", "asha@example.com", "correct horse 42");
  const { page, context } = await open("login.html");
  await loginVia(page, "asha@example.com", "correct horse 42");
  await page.waitForURL("**/dashboard.html");
  await page.waitForSelector("#dashHistorySection:not([hidden])");

  api.revokeAllSessions();                       // server-side expiry/revocation
  await page.click(".dash-row");
  await page.waitForSelector("#dashAuthRequired:not([hidden])");
  assert.equal(await page.isVisible("#dashOverlay"), false, "the detail overlay is closed");
  await context.close();
});

test("a network failure on the Dashboard is still the error state (not mistaken for logged-out)", async () => {
  const { page, context } = await open("dashboard.html");
  await page.waitForSelector("#dashAuthRequired:not([hidden])");
  await page.route(`${api.url}/**`, (r) => r.abort());
  await page.click("#dashAuthRequired a.btn-secondary", { trial: true });   // page still alive
  await page.evaluate(() => location.reload());
  await page.waitForSelector("#dashError:not([hidden])");
  assert.equal(await page.isVisible("#dashAuthRequired"), false);
  await context.close();
});

// ------------------------------------------------------------------ profile

test("Profile when logged out prompts for login; when logged in shows name, email and member-since", async () => {
  let { page, context } = await open("profile.html");
  await page.waitForSelector("#profileAuthRequired:not([hidden])");
  assert.equal(await page.isVisible("#profileCard"), false);
  await context.close();

  api.addAccount("Asha Nair", "asha@example.com", "correct horse 42");
  ({ page, context } = await open("login.html?next=profile.html"));
  await loginVia(page, "asha@example.com", "correct horse 42");
  await page.waitForURL("**/profile.html");
  await page.waitForSelector("#profileCard:not([hidden])");
  assert.equal(await page.innerText("#profileName"), "Asha Nair");
  assert.equal(await page.innerText("#profileEmail"), "asha@example.com");
  assert.match(await page.innerText("#profileSince"), /2026/);
  assert.equal((await page.innerText("#profileCard")).includes("$2"), false);
  await context.close();
});

test("a hostile name is rendered as text, never as HTML", async () => {
  api.addAccount('<img src=x onerror="window.__pwned=1">', "x@example.com", "correct horse 42");
  const { page, context } = await open("login.html?next=profile.html");
  await loginVia(page, "x@example.com", "correct horse 42");
  await page.waitForURL("**/profile.html");
  await page.waitForSelector("#profileCard:not([hidden])");
  assert.equal(await page.evaluate(() => window.__pwned), undefined);
  assert.equal(await page.innerText("#profileName"), '<img src=x onerror="window.__pwned=1">');
  await context.close();
});

// ------------------------------------------------------------------ authority portal + navigation stay separate

test("the legacy Authority Dashboard is untouched: no farmer login script or control, and no farmer page links to it", async () => {
  const authorityHtml = fs.readFileSync(path.join(FRONTEND, "authority-dashboard.html"), "utf8");
  assert.equal(/auth\.js|auth-pages\.js|auth\.css/.test(authorityHtml), false);

  const { page, context } = await open("authority-dashboard.html");
  await page.waitForSelector("header.site-header");
  assert.equal(await page.$("#authWidget"), null, "no farmer login control on the authority portal");
  assert.equal(api.log.filter((r) => r.path.startsWith("/api/auth/")).length, 0, "the authority page never asks about farmer sessions");
  await context.close();

  // login.html is the one deliberate exception: it offers a "Farmer / User" vs "Authority" chooser,
  // and picking "Authority" sends the person to the (separate, untouched) authority portal instead
  // of ever mixing farmer and authority auth on the same page.
  for (const f of ["index", "how-it-works", "scanner", "dashboard", "heatmap", "agricultural-network", "register", "profile"]) {
    const html = fs.readFileSync(path.join(FRONTEND, `${f}.html`), "utf8");
    // Every page's header nav carries a single "Authority" entry link (added in sw.js v14); nothing
    // else on a farmer page may point at the authority portal.
    const withoutNav = html.replace(/<a href="authority-dashboard\.html" data-nav="authority">Authority<\/a>/g, "");
    assert.equal(/authority-dashboard/.test(withoutNav), false, `${f}.html must not link to the authority portal beyond the nav entry`);
  }
});

test("login.html offers a Farmer/User vs Authority chooser that routes to the right portal", async () => {
  const { page, context } = await open("login.html");
  await page.waitForSelector("#roleChoice:not([hidden])");
  assert.equal(await page.isVisible("#loginCard"), false, "login form is hidden until a role is chosen");

  await page.click("#chooseAuthorityBtn");
  await page.waitForURL("**/authority.html");
  await context.close();
});

test("every farmer-facing page loads the account control", async () => {
  for (const f of ["index", "how-it-works", "scanner", "dashboard", "heatmap", "agricultural-network"]) {
    const html = fs.readFileSync(path.join(FRONTEND, `${f}.html`), "utf8");
    assert.match(html, /<script src="auth\.js"><\/script>/, `${f}.html`);
  }
});
