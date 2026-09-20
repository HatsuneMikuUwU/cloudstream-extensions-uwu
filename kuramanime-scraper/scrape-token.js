#!/usr/bin/env node
/**
 * Kuramanime token scraper
 * - Ambil URL leviathan.js dari homepage (atau env LEVIATHAN_URL)
 * - Eval JS (patch anti-debug loop) + intercept Authorization header
 * - Output token ke stdout / file / GitHub Actions output
 *
 * Usage:
 *   node scrape-token.js
 *   MAIN_URL=https://v20.kuramanime.ing node scrape-token.js
 *   node scrape-token.js --out token.txt
 */

const fs = require("fs");
const path = require("path");

const MAIN_URL = (process.env.MAIN_URL || "https://v20.kuramanime.ing").replace(/\/$/, "");
const UA =
  process.env.USER_AGENT ||
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

function parseArgs() {
  const args = process.argv.slice(2);
  const out = { outFile: null, json: false };
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--out" && args[i + 1]) out.outFile = args[++i];
    if (args[i] === "--json") out.json = true;
  }
  return out;
}

async function fetchText(url, headers = {}) {
  // Support local file for offline / CI fixture tests
  if (url.startsWith("file://") || url.startsWith("/") || url.startsWith("./")) {
    const fp = url.startsWith("file://") ? url.slice(7) : path.resolve(url);
    return fs.readFileSync(fp, "utf8");
  }

  const res = await fetch(url, {
    headers: {
      "User-Agent": UA,
      Accept: "*/*",
      "Accept-Language": "en-US,en;q=0.9,id;q=0.8",
      Referer: MAIN_URL + "/",
      ...headers,
    },
    redirect: "follow",
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} for ${url}: ${text.slice(0, 120)}`);
  }
  if (text.trim().startsWith("<!DOCTYPE") || text.trim().startsWith("<html")) {
    throw new Error(`Cloudflare/HTML page returned for ${url} (status ${res.status})`);
  }
  return text;
}

async function resolveLeviathanUrl() {
  // Local file: LEVIATHAN_URL=./leviathan.js atau file://...
  if (process.env.LEVIATHAN_FILE) {
    return "file://" + path.resolve(process.env.LEVIATHAN_FILE);
  }
  if (process.env.LEVIATHAN_URL) return process.env.LEVIATHAN_URL;

  try {
    const html = await fetchText(MAIN_URL + "/", {
      Accept: "text/html,application/xhtml+xml",
    });
    // input#tokenAuthJs value="/storage/leviathan.js?v=..."
    const m =
      html.match(/id=["']tokenAuthJs["'][^>]*value=["']([^"']+)["']/i) ||
      html.match(/value=["']([^"']*leviathan\.js[^"']*)["'][^>]*id=["']tokenAuthJs["']/i) ||
      html.match(/["'](\/storage\/leviathan\.js\?v=\d+)["']/i);
    if (m && m[1]) {
      const u = m[1];
      return u.startsWith("http") ? u : MAIN_URL + u;
    }
  } catch (e) {
    console.error("[warn] homepage parse failed:", e.message);
  }

  return `${MAIN_URL}/storage/leviathan.js?v=${Date.now()}`;
}

function extractTokenFromJs(jsCode, host) {
  // Patch infinite string-array rotator
  let code = jsCode.replace(
    /while\s*\(\s*!!\s*\[\s*\]\s*\)\s*\{/,
    "var __rotCount=0; while(!![] && (++__rotCount)<500){"
  );

  let extracted = "FAILED_EMPTY";

  const sandbox = {
    window: null,
    document: { createElement: () => ({}) },
    navigator: { userAgent: UA },
    location: { hostname: host, href: MAIN_URL + "/" },
    console: { log() {}, warn() {}, error() {} },
  };
  sandbox.window = sandbox;
  sandbox.global = sandbox;

  sandbox.fetch = function (_url, options) {
    if (options && options.headers) {
      const h = options.headers;
      const v = h.Authorization || h.authorization;
      if (v) extracted = v;
    }
    return Promise.resolve({
      ok: true,
      text: async () => "",
      json: async () => ({}),
    });
  };

  const jq = function (options) {
    if (options && options.headers) {
      const h = options.headers;
      const v = h.Authorization || h.authorization;
      if (v) extracted = v;
    }
    return {
      done() {
        return this;
      },
      fail() {
        return this;
      },
      always() {
        return this;
      },
    };
  };
  jq.ajax = jq;
  sandbox.$ = jq;
  sandbox.jQuery = jq;

  // Eval in Function scope bound to sandbox-ish globals
  const fn = new Function(
    "window",
    "global",
    "document",
    "navigator",
    "location",
    "fetch",
    "$",
    "jQuery",
    "console",
    code + "\n; return typeof window !== 'undefined' ? window : this;"
  );

  let win;
  try {
    win = fn(
      sandbox,
      sandbox,
      sandbox.document,
      sandbox.navigator,
      sandbox.location,
      sandbox.fetch,
      sandbox.$,
      sandbox.jQuery,
      sandbox.console
    );
  } catch (e) {
    // partial eval may still expose helpers
    win = sandbox;
  }

  // Call official helpers if present
  const tryCall = (name, args) => {
    try {
      if (typeof sandbox[name] === "function") sandbox[name](...args);
      if (win && typeof win[name] === "function") win[name](...args);
    } catch (_) {}
  };

  tryCall("jAjaxSecure", ["https://dummy", "POST", "{}", null, null, null, null]);
  tryCall("fetchSecure", ["https://dummy", "POST", {}]);

  // Scan any other functions
  if (extracted === "FAILED_EMPTY") {
    const targets = [sandbox, win].filter(Boolean);
    for (const obj of targets) {
      for (const key of Object.keys(obj)) {
        if (typeof obj[key] !== "function") continue;
        if (["fetch", "$", "jQuery", "eval", "Function"].includes(key)) continue;
        try {
          obj[key]("https://dummy", "POST", "{}");
        } catch (_) {}
        try {
          obj[key]("https://dummy", "POST", {}, null, null, null, null);
        } catch (_) {}
      }
    }
  }

  if (!extracted || extracted === "FAILED_EMPTY" || extracted.startsWith("ERROR")) {
    throw new Error("Token extract failed: " + extracted);
  }

  return extracted.replace(/^Bearer\s+/i, "").trim();
}

async function main() {
  const args = parseArgs();
  const host = new URL(MAIN_URL).hostname;

  console.error(`[info] mainUrl=${MAIN_URL}`);
  const leviUrl = await resolveLeviathanUrl();
  console.error(`[info] leviathan=${leviUrl}`);

  const jsCode = await fetchText(leviUrl, {
    "X-Requested-With": "XMLHttpRequest",
  });
  console.error(`[info] js length=${jsCode.length}`);

  const token = extractTokenFromJs(jsCode, host);
  console.error(`[info] token extracted OK`);

  const result = {
    token,
    authorization: `Bearer ${token}`,
    leviathanUrl: leviUrl,
    mainUrl: MAIN_URL,
    scrapedAt: new Date().toISOString(),
  };

  if (args.json) {
    process.stdout.write(JSON.stringify(result, null, 2) + "\n");
  } else {
    process.stdout.write(token + "\n");
  }

  if (args.outFile) {
    const p = path.resolve(args.outFile);
    fs.writeFileSync(p, args.json ? JSON.stringify(result, null, 2) : token);
    console.error(`[info] written ${p}`);
  }

  // GitHub Actions outputs
  if (process.env.GITHUB_OUTPUT) {
    fs.appendFileSync(process.env.GITHUB_OUTPUT, `token=${token}\n`);
    fs.appendFileSync(process.env.GITHUB_OUTPUT, `authorization=Bearer ${token}\n`);
  }

  // Optional: commit-friendly env file
  if (process.env.WRITE_ENV === "1") {
    fs.writeFileSync(
      path.resolve("token.env"),
      `KURAMANIME_TOKEN=${token}\nKURAMANIME_AUTH=Bearer ${token}\n`
    );
  }
}

main().catch((e) => {
  console.error("[error]", e.message || e);
  process.exit(1);
});
