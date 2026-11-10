"use strict";
/**
 * A small STATEFUL FAKE of the Stage 6C API contract (not the Spring backend — the JUnit tests cover that):
 *   GET/PUT/DELETE /api/farmer/location   (session cookie FOLIX_SESSION=tok required, else 401 in the FOLIX envelope)
 *   GET /api/folix/disease-map            (public)
 *   GET /api/auth/session                 (so the shared header account widget works)
 * It mimics the server's rules just enough for the browser flow: consent must be true, and the stored/returned area is
 * the CENTRE of a 0.1-degree cell, never the coordinates that were sent. Every request is logged so tests can prove what
 * the browser did (and did not) send.
 */
const http = require("node:http");

const EMPTY_MAP = { lookbackDays: 30, minimumReports: 3, areaSizeDegrees: 0.1, regions: [] };

async function startFakeLocationApi() {
  const state = { location: null, map: EMPTY_MAP, mapStatus: 200 };
  const log = [];   // { method, path, hasCookie, body }

  const server = http.createServer((req, res) => {
    const cors = {
      "Access-Control-Allow-Origin": req.headers.origin || "*",
      "Access-Control-Allow-Credentials": "true",
      "Access-Control-Allow-Headers": req.headers["access-control-request-headers"] || "*",
      "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE",
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
      const loggedIn = !!cookie && cookie[1] === "tok";
      log.push({ method: req.method, path, hasCookie: !!cookie, body });

      const json = (status, payload) => {
        res.writeHead(status, { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify(payload));
      };
      const error = (status, code, message) => json(status, { error: { code, message, retryable: false } });
      const centre = (v) => Math.round((Math.floor(Math.round(v * 1000) / 100) * 100 + 50)) / 1000;
      const status = () => state.location
        ? { sharingEnabled: true, approximateArea: { centerLatitude: state.location.lat, centerLongitude: state.location.lng, cellSizeDegrees: 0.1 }, enabledAt: "2026-09-21T10:00:00Z" }
        : { sharingEnabled: false, approximateArea: null, enabledAt: null };

      if (path === "/api/auth/session") {
        return json(200, loggedIn
          ? { authenticated: true, farmer: { id: "f1", name: "Asha Nair", email: "asha@example.com", createdAt: "2026-01-01T00:00:00Z" } }
          : { authenticated: false, farmer: null });
      }
      if (path === "/api/farmer/location") {
        if (!loggedIn) return error(401, "UNAUTHENTICATED", "You need to log in to do that. Your session may have expired.");
        if (req.method === "GET") return json(200, status());
        if (req.method === "PUT") {
          const b = body || {};
          if (b.consent !== true) return error(400, "VALIDATION_FAILED", "Location sharing needs your explicit consent.");
          if (typeof b.latitude !== "number" || typeof b.longitude !== "number") return error(400, "VALIDATION_FAILED", "The location is missing or not valid.");
          state.location = { lat: centre(b.latitude), lng: centre(b.longitude) };
          return json(200, status());
        }
        if (req.method === "DELETE") { state.location = null; return json(200, status()); }
      }
      if (path === "/api/folix/disease-map" && req.method === "GET") {
        return state.mapStatus === 200 ? json(200, state.map) : error(state.mapStatus, "SERVER_ERROR", "boom");
      }
      return error(404, "NOT_FOUND", "not found");
    });
  });

  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    state, log,
    reset() { state.location = null; state.map = EMPTY_MAP; state.mapStatus = 200; log.length = 0; },
    close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(r); }),
  };
}

module.exports = { startFakeLocationApi, EMPTY_MAP };
