/*
 * ShotSense Response Console — receiver
 *
 * Zero-dependency Node HTTP server. Receives gunshot alerts from the ShotSense
 * Android app over HTTP (POST /alert) and serves a live web console that plots
 * them on a map, sounds an alarm, and shows desktop notifications.
 *
 * Run:   node server.js
 * Env:   PORT   (default 8080)
 *        TOKEN  (optional shared secret; if set, requests must include it as
 *                ?token=... in the URL or an "x-shotsense-token" header)
 *        DATA   (alert log path; default ./alerts.ndjson)
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '8080', 10);
const TOKEN = process.env.TOKEN || '';
const DATA_FILE = process.env.DATA || path.join(__dirname, 'alerts.ndjson');
const MAX_BODY_BYTES = 64 * 1024; // alerts are tiny; reject anything large
const MAX_IN_MEMORY = 500;

// --- in-memory store, hydrated from the append-only log on startup ----------

/** @type {Array<object>} newest last */
const alerts = [];

function loadFromDisk() {
  try {
    const raw = fs.readFileSync(DATA_FILE, 'utf8');
    for (const line of raw.split('\n')) {
      const t = line.trim();
      if (!t) continue;
      try {
        alerts.push(JSON.parse(t));
      } catch {
        /* skip malformed line */
      }
    }
    if (alerts.length > MAX_IN_MEMORY) {
      alerts.splice(0, alerts.length - MAX_IN_MEMORY);
    }
    console.log(`[store] loaded ${alerts.length} alert(s) from ${DATA_FILE}`);
  } catch (e) {
    if (e.code !== 'ENOENT') console.warn('[store] load failed:', e.message);
  }
}

function persist(record) {
  fs.appendFile(DATA_FILE, JSON.stringify(record) + '\n', (err) => {
    if (err) console.warn('[store] append failed:', err.message);
  });
}

// --- Server-Sent Events: push new alerts to every open console -------------

/** @type {Set<http.ServerResponse>} */
const sseClients = new Set();

function broadcast(event, data) {
  const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of sseClients) {
    res.write(payload);
  }
}

// --- request helpers --------------------------------------------------------

function tokenOk(req, url) {
  if (!TOKEN) return true;
  const q = url.searchParams.get('token');
  const h = req.headers['x-shotsense-token'];
  return q === TOKEN || h === TOKEN;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function num(v) {
  return typeof v === 'number' && isFinite(v) ? v : null;
}

/** Normalize whatever the app sent into a stable record the console renders. */
function normalize(raw, req) {
  const r = raw && typeof raw === 'object' ? raw : {};
  const lat = num(r.lat);
  const lng = num(r.lng);
  return {
    id: crypto.randomUUID(),
    receivedAt: new Date().toISOString(),
    deviceId: typeof r.deviceId === 'string' ? r.deviceId : 'unknown',
    timestamp: typeof r.timestamp === 'string' ? r.timestamp : new Date().toISOString(),
    lat,
    lng,
    accuracyMeters: num(r.accuracyMeters),
    shots: Number.isInteger(r.shots) ? r.shots : (num(r.shots) || 1),
    confirmed: !!r.confirmed,
    soundPeak: num(r.soundPeak),
    recoilG: num(r.recoilG),
    test: !!r.test,
    approximateLocation: !!r.approximateLocation,
    sourceIp: req.socket.remoteAddress || null,
    acknowledged: false,
  };
}

// --- routes -----------------------------------------------------------------

const DASHBOARD = fs.readFileSync(path.join(__dirname, 'public', 'index.html'));

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = url.pathname;

  // CORS preflight (harmless; lets you POST from a browser tool if needed)
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, x-shotsense-token',
    });
    res.end();
    return;
  }

  // Health check
  if (req.method === 'GET' && pathname === '/health') {
    return sendJson(res, 200, { ok: true, alerts: alerts.length, time: new Date().toISOString() });
  }

  // Receive an alert from the phone
  if (req.method === 'POST' && pathname === '/alert') {
    if (!tokenOk(req, url)) return sendJson(res, 401, { ok: false, error: 'bad token' });
    let raw;
    try {
      const text = await readBody(req);
      raw = text ? JSON.parse(text) : {};
    } catch (e) {
      return sendJson(res, 400, { ok: false, error: e.message });
    }
    const record = normalize(raw, req);
    alerts.push(record);
    if (alerts.length > MAX_IN_MEMORY) alerts.shift();
    persist(record);
    broadcast('alert', record);
    const tag = record.test ? 'TEST' : 'GUNSHOT';
    console.log(
      `[alert] ${tag} device=${record.deviceId} shots=${record.shots} ` +
        `confirmed=${record.confirmed} loc=${record.lat},${record.lng} from ${record.sourceIp}`
    );
    return sendJson(res, 200, { ok: true, id: record.id });
  }

  // Initial list for a freshly opened console
  if (req.method === 'GET' && pathname === '/api/alerts') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(JSON.stringify(alerts));
  }

  // Acknowledge an alert (silences the console alarm for it)
  if (req.method === 'POST' && pathname === '/api/ack') {
    const id = url.searchParams.get('id');
    const found = alerts.find((a) => a.id === id);
    if (found) {
      found.acknowledged = true;
      broadcast('ack', { id });
      return sendJson(res, 200, { ok: true });
    }
    return sendJson(res, 404, { ok: false, error: 'not found' });
  }

  // Live event stream for the console
  if (req.method === 'GET' && pathname === '/events') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });
    res.write(`event: hello\ndata: ${JSON.stringify({ time: new Date().toISOString() })}\n\n`);
    sseClients.add(res);
    const ping = setInterval(() => res.write(': ping\n\n'), 20000);
    req.on('close', () => {
      clearInterval(ping);
      sseClients.delete(res);
    });
    return;
  }

  // Dashboard
  if (req.method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(DASHBOARD);
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
});

loadFromDisk();
server.listen(PORT, () => {
  console.log('========================================================');
  console.log(' ShotSense Response Console');
  console.log('========================================================');
  console.log(` Console:  http://localhost:${PORT}/`);
  console.log(` Endpoint: POST http://<this-laptop>:${PORT}/alert`);
  if (TOKEN) console.log(' Token:    REQUIRED (?token=... or x-shotsense-token header)');
  else console.log(' Token:    none (set TOKEN env var to require one)');
  console.log(` Log file: ${DATA_FILE}`);
  console.log('--------------------------------------------------------');
  printLanHints(PORT);
  console.log('========================================================');
});

function printLanHints(port) {
  try {
    const nets = require('os').networkInterfaces();
    const ips = [];
    for (const name of Object.keys(nets)) {
      for (const net of nets[name] || []) {
        if (net.family === 'IPv4' && !net.internal) ips.push(net.address);
      }
    }
    if (ips.length) {
      console.log(' On the same WiFi, set the app HTTP URL to one of:');
      for (const ip of ips) console.log(`   http://${ip}:${port}/alert`);
    }
  } catch {
    /* ignore */
  }
}
