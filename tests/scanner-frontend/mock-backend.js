"use strict";
/**
 * Test doubles for the Scanner frontend tests. No real Gemini, no real backend, no network beyond localhost.
 *
 *  - startStatic(dir)  serves the real frontend/ files.
 *  - startMock()       stands in for POST /api/scan/analyze. Every request is recorded (parsed multipart
 *                      fields, image bytes, headers) so tests can assert on exactly what the browser sent.
 */
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

const MIME = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml", ".png": "image/png", ".webmanifest": "application/manifest+json", ".json": "application/json",
};

function listen(server) {
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve(server.address().port)));
}

async function startStatic(root) {
  const server = http.createServer((req, res) => {
    const urlPath = decodeURIComponent(new URL(req.url, "http://x").pathname);
    const file = path.join(root, urlPath === "/" ? "index.html" : urlPath);
    if (!file.startsWith(root)) { res.writeHead(403).end(); return; }
    fs.readFile(file, (err, data) => {
      if (err) { res.writeHead(404).end("not found"); return; }
      res.writeHead(200, { "Content-Type": MIME[path.extname(file)] || "application/octet-stream" });
      res.end(data);
    });
  });
  const port = await listen(server);
  return { url: `http://127.0.0.1:${port}`, close: () => new Promise((r) => server.close(r)) };
}

/** Minimal multipart/form-data parser: returns [{name, filename, contentType, data:Buffer}]. */
function parseMultipart(buffer, contentType) {
  const m = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || "");
  if (!m) return [];
  const boundary = Buffer.from("--" + (m[1] || m[2]));
  const parts = [];
  let pos = buffer.indexOf(boundary);
  while (pos !== -1) {
    const next = buffer.indexOf(boundary, pos + boundary.length);
    if (next === -1) break;
    const raw = buffer.subarray(pos + boundary.length + 2, next - 2); // strip CRLF after boundary and before next
    const headerEnd = raw.indexOf("\r\n\r\n");
    if (headerEnd !== -1) {
      const head = raw.subarray(0, headerEnd).toString("latin1");
      const disp = /name="([^"]*)"(?:; filename="([^"]*)")?/i.exec(head) || [];
      const ct = /content-type:\s*([^\r\n]+)/i.exec(head);
      parts.push({ name: disp[1], filename: disp[2], contentType: ct ? ct[1] : null, data: raw.subarray(headerEnd + 4) });
    }
    pos = next;
  }
  return parts;
}

/** JPEG long-edge dimensions from the first SOFn marker. */
function jpegSize(buf) {
  let i = 2;
  while (i + 9 < buf.length) {
    if (buf[i] !== 0xff) { i++; continue; }
    const marker = buf[i + 1];
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      return { height: buf.readUInt16BE(i + 5), width: buf.readUInt16BE(i + 7) };
    }
    i += 2 + buf.readUInt16BE(i + 2);
  }
  return null;
}

/**
 * scenario = async ({ respond, request }) => void. Default scenario answers a confident diagnosis.
 * respond(status, jsonBodyOrString, { delayMs, headers }).
 */
async function startMock() {
  const state = { requests: [], authRequests: [], scenario: null, reportScenario: null };

  const server = http.createServer((req, res) => {
    // Stage 6A: the Save Report call is credentialed (it carries the farmer's session cookie), and browsers refuse
    // a credentialed response unless the origin is echoed (never "*"), headers are echoed, and credentials are allowed.
    const cors = {
      "Access-Control-Allow-Origin": req.headers.origin || "*",
      "Access-Control-Allow-Credentials": "true",
      "Access-Control-Allow-Headers": req.headers["access-control-request-headers"] || "*",
      "Vary": "Origin",
    };
    if (req.method === "OPTIONS") { res.writeHead(204, { ...cors, "Access-Control-Allow-Methods": "GET, POST" }).end(); return; }

    // Stage 6A: every page's header now asks GET /api/auth/session. That is site chrome, not a scan request, so it is
    // answered (anonymous by default) and recorded separately — `requests` stays "scan API calls only".
    if (new URL(req.url, "http://x").pathname.startsWith("/api/auth/")) {
      const path = new URL(req.url, "http://x").pathname;
      state.authRequests.push({ method: req.method, path, headers: req.headers });
      res.writeHead(200, { ...cors, "Content-Type": "application/json" });
      res.end(JSON.stringify(path === "/api/auth/session" ? { authenticated: false, farmer: null } : {}));
      return;
    }

    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const body = Buffer.concat(chunks);
      const urlPath = new URL(req.url, "http://x").pathname;
      const isReports = urlPath === "/api/scan/reports";
      const parts = isReports ? [] : parseMultipart(body, req.headers["content-type"]);
      let json = null;
      if (isReports) { try { json = JSON.parse(body.toString("utf8")); } catch (e) { json = null; } }
      const record = { method: req.method, path: urlPath, headers: req.headers, parts, bodyBytes: body.length, json };
      state.requests.push(record);

      const respond = (status, payload, opts = {}) => {
        const send = () => {
          const isString = typeof payload === "string";
          res.writeHead(status, { ...cors, "Content-Type": isString ? "text/html" : "application/json", ...(opts.headers || {}) });
          res.end(isString ? payload : JSON.stringify(payload));
        };
        if (opts.delayMs) setTimeout(send, opts.delayMs); else send();
      };

      if (isReports) {
        const scenario = state.reportScenario || (async ({ respond: r, request: req2 }) => r(201, SAVED_REPORT(req2.json)));
        Promise.resolve(scenario({ respond, request: record })).catch(() => respond(500, { error: { code: "ANALYSIS_FAILED", retryable: true } }));
        return;
      }

      const scenario = state.scenario || (async ({ respond: r }) => r(200, DIAGNOSIS));
      Promise.resolve(scenario({ respond, request: record })).catch(() => respond(500, { error: { code: "ANALYSIS_FAILED" } }));
    });
  });
  const port = await listen(server);
  return {
    url: `http://127.0.0.1:${port}`,
    requests: state.requests,
    authRequests: state.authRequests,
    setScenario: (fn) => { state.scenario = fn; },
    setReportScenario: (fn) => { state.reportScenario = fn; },
    reset: () => { state.requests.length = 0; state.authRequests.length = 0; state.scenario = null; state.reportScenario = null; },
    close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(r); }),
  };
}

const DIAGNOSIS = {
  plant_detected: true, plant_part: "leaf", crop: "Tomato", disease: "Early blight", severity: "moderate", confidence: "high",
  symptoms: ["Brown concentric-ring spots on lower leaves", "Yellow halo around lesions"],
  reasoning: "Target-like rings with a yellow halo on older leaves are typical of early blight.",
  recommended_actions: ["Remove the most affected leaves.", "Confirm the diagnosis and any treatment with a local agricultural officer."],
  needs_human_review: false,
};
const NON_PLANT = {
  plant_detected: false, plant_part: "unknown", crop: "unknown", disease: "unknown", severity: "unknown", confidence: "low",
  symptoms: [], reasoning: "The image shows a coffee mug on a desk; no plant is visible.", recommended_actions: [], needs_human_review: true,
};
const UNKNOWN_DISEASE = {
  ...DIAGNOSIS, disease: "unknown", confidence: "low", severity: "unknown", needs_human_review: true,
  reasoning: "The photo is too distant and blurred to tell lesions from shadow.",
};

/** Stand-in for the existing FolixReportResponse DTO, built from the request body the browser sent. */
function SAVED_REPORT(reportRequest) {
  const r = reportRequest || {};
  return {
    id: "11111111-1111-1111-1111-111111111111",
    createdAt: new Date().toISOString(),
    crop: r.crop, plantPart: r.plantPart, disease: r.disease, severity: r.severity, confidence: r.confidence,
    symptoms: r.symptoms || [], reasoning: r.reasoning, recommendedActions: r.recommendedActions || [],
    needsHumanReview: !!r.needsHumanReview, status: "RECEIVED",
  };
}

module.exports = { startStatic, startMock, parseMultipart, jpegSize, DIAGNOSIS, NON_PLANT, UNKNOWN_DISEASE, SAVED_REPORT };
