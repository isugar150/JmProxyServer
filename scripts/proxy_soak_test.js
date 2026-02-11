#!/usr/bin/env node
"use strict";

const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const ITERATIONS = Number(process.env.SOAK_ITERATIONS || 14);
const CASE_COOLDOWN_MS = Number(process.env.CASE_COOLDOWN_MS || 10000);
const LOG_PATH =
  process.env.SOAK_LOG_PATH ||
  path.join(__dirname, `soak_result_${new Date().toISOString().replace(/[:.]/g, "-")}.log`);

function log(line) {
  fs.appendFileSync(LOG_PATH, `${line}\n`, "utf8");
  console.log(line);
}

function runOnce(index) {
  const env = { ...process.env };
  env.CASE_COOLDOWN_MS = String(CASE_COOLDOWN_MS);
  delete env.SHORT_CLIENTS;
  delete env.LONG_CLIENTS;
  delete env.SHORT_MIN_MS;
  delete env.SHORT_MAX_MS;
  delete env.LONG_MIN_MS;
  delete env.LONG_MAX_MS;
  delete env.LB_REQUESTS;
  delete env.HIGH_TOTAL_REQUESTS;
  delete env.HIGH_CONCURRENCY;

  log(`=== RUN ${index} START ${new Date().toISOString()} ===`);
  const result = spawnSync("node", ["scripts/proxy_integration_test.js"], {
    cwd: ROOT,
    env,
    encoding: "utf8",
    maxBuffer: 50 * 1024 * 1024,
  });

  const combined = `${result.stdout || ""}${result.stderr || ""}`;
  fs.appendFileSync(LOG_PATH, combined, "utf8");

  const passed =
    result.status === 0 && combined.includes("INTEGRATION_TEST_RESULT: ALL_PASSED");
  if (passed) {
    log(`=== RUN ${index} RESULT PASS ===`);
  } else {
    log(`=== RUN ${index} RESULT FAIL exit=${result.status} ===`);
  }

  const highlights = combined
    .split(/\r?\n/)
    .filter((line) =>
      /\[FAIL\]|INTEGRATION_TEST_FAILED|topFailReasons|short_lived_random|long_lived_random|high_traffic/.test(
        line
      )
    );
  for (const line of highlights) {
    if (line.trim().length > 0) log(`[RUN ${index}] ${line}`);
  }

  return passed;
}

function main() {
  const startedAt = new Date();
  let pass = 0;
  let fail = 0;
  log(`SOAK_TEST_START ${startedAt.toISOString()} iterations=${ITERATIONS}`);

  for (let i = 1; i <= ITERATIONS; i++) {
    const ok = runOnce(i);
    if (ok) pass++;
    else fail++;
  }

  const endedAt = new Date();
  const elapsedMs = endedAt.getTime() - startedAt.getTime();
  log(
    `SOAK_TEST_END ${endedAt.toISOString()} pass=${pass} fail=${fail} elapsed_ms=${elapsedMs} log=${LOG_PATH}`
  );

  process.exitCode = fail > 0 ? 1 : 0;
}

main();

