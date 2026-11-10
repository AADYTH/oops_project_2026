"use strict";
/**
 * A small STATEFUL FAKE of the FOLIX account API contract (not the Spring backend): accounts and sessions live in
 * memory; login sets a real HttpOnly cookie; protected routes answer 401 in the FOLIX envelope without one; CORS is
 * credentialed for the calling origin. It lets the real frontend files run end-to-end in a real browser — cookie
 * handling and redirects included — without a database. The Spring behaviour itself is covered by the JUnit tests.
 */
const http = require("node:http");
const crypto = require("node:crypto");

const SAMPLE_REPORT = {
  id: "11111111-1111-1111-1111-111111111111", createdAt: "2026-09-01T10:15:30Z", crop: "Tomato", plantPart: "leaf",
  disease: "Early blight", severity: "moderate", confidence: "high", symptoms: ["Brown concentric-ring spots"],
  reasoning: "Concentric target-like rings are typical of early blight.", recommendedActions: ["Remove affected leaves."],
  needsHumanReview: false, status: "RECEIVED",
};

function err(res, cors, status, code, message, fields) {
  res.writeHead(status, { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" });
  res.end(JSON.stringify({ error: { code, message, retryable: false, ...(fields ? { fields } : {}) } }));
}

async function startFakeApi() {
  const accounts = new Map();   // email -> { id, name, email, password, createdAt }
  const sessions = new Map();   // token -> email
  const log = [];               // { method, path, cookie }
  const state = { rejectAllSessions: false };

  const server = http.createServer((req, res) => {
    const cors = {
      "Access-Control-Allow-Origin": req.headers.origin || "*",
      "Access-Control-Allow-Credentials": "true",
      "Access-Control-Allow-Headers": req.headers["access-control-request-headers"] || "*",
      "Access-Control-Allow-Methods": "GET, POST",
      "Vary": "Origin",
    };
    if (req.method === "OPTIONS") { res.writeHead(204, cors).end(); return; }

    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const path = new URL(req.url, "http://x").pathname;
      let body = null;
      try { body = JSON.parse(Buffer.concat(chunks).toString("utf8")); } catch (e) { body = null; }
      const cookie = /(?:^|;\s*)FOLIX_SESSION=([^;]+)/.exec(req.headers.cookie || "");
      const token = cookie ? cookie[1] : null;
      log.push({ method: req.method, path, hasCookie: !!token, body });

      const email = token && !state.rejectAllSessions ? sessions.get(token) : null;
      const farmer = email ? accounts.get(email) : null;
      const profile = (a) => ({ id: a.id, name: a.name, email: a.email, createdAt: a.createdAt });
      const json = (status, payload, extra = {}) => {
        res.writeHead(status, { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store", ...extra });
        res.end(JSON.stringify(payload));
      };

      if (path === "/api/auth/register" && req.method === "POST") {
        const f = {};
        const b = body || {};
        if (!b.name || !String(b.name).trim()) f.name = "Enter your name.";
        if (!b.email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(String(b.email).trim())) f.email = "Enter a valid email address.";
        if (!b.password || String(b.password).length < 8) f.password = "Password must be at least 8 characters.";
        if (Object.keys(f).length) return err(res, cors, 400, "VALIDATION_FAILED", "Please correct the highlighted fields.", f);
        const e = String(b.email).trim().toLowerCase();
        if (accounts.has(e)) return err(res, cors, 409, "EMAIL_ALREADY_REGISTERED", "An account with this email already exists. Try logging in instead.");
        const a = { id: crypto.randomUUID(), name: String(b.name).trim(), email: e, password: b.password, createdAt: new Date().toISOString() };
        accounts.set(e, a);
        return json(201, profile(a));                     // note: registering does NOT set a cookie
      }
      if (path === "/api/auth/login" && req.method === "POST") {
        const a = body && accounts.get(String(body.email || "").trim().toLowerCase());
        if (!a || a.password !== body.password) return err(res, cors, 401, "INVALID_CREDENTIALS", "Incorrect email or password.");
        const t = crypto.randomBytes(32).toString("base64url");
        sessions.set(t, a.email);
        return json(200, profile(a), { "Set-Cookie": `FOLIX_SESSION=${t}; Path=/api; HttpOnly; SameSite=Lax; Max-Age=604800` });
      }
      if (path === "/api/auth/logout" && req.method === "POST") {
        if (token) sessions.delete(token);
        res.writeHead(204, { ...cors, "Set-Cookie": "FOLIX_SESSION=; Path=/api; HttpOnly; SameSite=Lax; Max-Age=0" });
        return res.end();
      }
      if (path === "/api/auth/session") return json(200, farmer ? { authenticated: true, farmer: profile(farmer) } : { authenticated: false, farmer: null });

      // protected
      if (path === "/api/farmer/profile" || path.startsWith("/api/scan/reports")) {
        if (!farmer) return err(res, cors, 401, "UNAUTHENTICATED", "You need to log in to do that. Your session may have expired.");
        if (path === "/api/farmer/profile") return json(200, profile(farmer));
        if (path === "/api/scan/reports/summary") return json(200, { totalReports: 1, reportsNeedingHumanReview: 0 });
        if (path === "/api/scan/reports") return json(200, { reports: [SAMPLE_REPORT], page: 0, size: 10, totalElements: 1, totalPages: 1 });
        return json(200, SAMPLE_REPORT);
      }
      return err(res, cors, 404, "NOT_FOUND", "not found");
    });
  });

  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    accounts, sessions, log, state,
    addAccount(name, email, password) {
      accounts.set(email, { id: crypto.randomUUID(), name, email, password, createdAt: "2026-01-15T08:00:00Z" });
    },
    revokeAllSessions() { sessions.clear(); },
    reset() { accounts.clear(); sessions.clear(); log.length = 0; state.rejectAllSessions = false; },
    close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(r); }),
  };
}

module.exports = { startFakeApi };
