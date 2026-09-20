#!/usr/bin/env node
/**
 * Update KuramanimeProvider `authorization` and bump the plugin version.
 *
 * Input : token.json produced by scrape-token.js (field "token"),
 *         or the KURAMANIME_TOKEN env variable.
 * Output: KuramanimeProvider.kt + KuramanimeProvider/build.gradle.kts
 *         (only touched when the token actually changed)
 *
 * Usage:
 *   node kuramanime-scraper/update-provider.js token.json
 *   KURAMANIME_TOKEN=xxxx node kuramanime-scraper/update-provider.js
 *
 * Env (optional):
 *   PROVIDER_FILE  path to KuramanimeProvider.kt
 *   GRADLE_FILE    path to KuramanimeProvider/build.gradle.kts
 *   GITHUB_OUTPUT  set automatically in Actions -> writes `changed` and `version`
 */

const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const PROVIDER_FILE = path.resolve(
  process.env.PROVIDER_FILE ||
    path.join(ROOT, "KuramanimeProvider/src/main/kotlin/com/hexated/KuramanimeProvider.kt")
);
const GRADLE_FILE = path.resolve(
  process.env.GRADLE_FILE || path.join(ROOT, "KuramanimeProvider/build.gradle.kts")
);

// Only characters that are safe inside a Kotlin string literal (no quote, backslash or `$`).
const TOKEN_RE = /^[A-Za-z0-9_\-.~+/=]{16,128}$/;
// var authorization: String? = "xxxx"
const AUTH_RE = /(var[ \t]+authorization[ \t]*:[ \t]*String\?[ \t]*=[ \t]*")([^"]*)(")/;
// version = 6
const VERSION_RE = /^([ \t]*version[ \t]*=[ \t]*)(\d+)\b/m;

function fail(msg) {
  console.error("[error]", msg);
  process.exit(1);
}

function setOutput(name, value) {
  if (process.env.GITHUB_OUTPUT) {
    fs.appendFileSync(process.env.GITHUB_OUTPUT, `${name}=${value}\n`);
  }
}

function readToken() {
  const file = process.argv.slice(2).find((a) => !a.startsWith("--"));
  let token = process.env.KURAMANIME_TOKEN || "";
  if (file) {
    try {
      token = JSON.parse(fs.readFileSync(path.resolve(file), "utf8")).token;
    } catch (e) {
      fail(`cannot read token from ${file}: ${e.message}`);
    }
  }
  token = String(token || "").replace(/^Bearer\s+/i, "").trim();
  if (!TOKEN_RE.test(token)) {
    fail("token is missing or has an unexpected format, refusing to write it into the source");
  }
  return token;
}

function main() {
  const token = readToken();

  const provider = fs.readFileSync(PROVIDER_FILE, "utf8");
  const authMatch = provider.match(AUTH_RE);
  if (!authMatch) fail(`'var authorization: String? = "..."' not found in ${PROVIDER_FILE}`);

  const oldToken = authMatch[2];
  if (oldToken === token) {
    console.error("[info] token unchanged, nothing to update");
    setOutput("changed", "false");
    return;
  }

  const gradle = fs.readFileSync(GRADLE_FILE, "utf8");
  const verMatch = gradle.match(VERSION_RE);
  if (!verMatch) fail(`'version = <number>' not found in ${GRADLE_FILE}`);

  const oldVersion = parseInt(verMatch[2], 10);
  const newVersion = oldVersion + 1;

  // Replacer functions so `$` in a value is never treated as a replacement pattern.
  fs.writeFileSync(PROVIDER_FILE, provider.replace(AUTH_RE, (_, a, _old, c) => a + token + c));
  fs.writeFileSync(GRADLE_FILE, gradle.replace(VERSION_RE, (_, a) => a + newVersion));

  console.error(`[info] authorization: ${oldToken} -> ${token}`);
  console.error(`[info] version: ${oldVersion} -> ${newVersion}`);
  setOutput("changed", "true");
  setOutput("version", String(newVersion));
}

main();
