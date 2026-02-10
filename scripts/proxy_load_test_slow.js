#!/usr/bin/env node
"use strict";

const { spawn } = require("child_process");
const path = require("path");

const scriptPath = path.join(__dirname, "proxy_load_test.js");
const env = { ...process.env };

if (!env.BACKEND_MODE) env.BACKEND_MODE = "slow";
if (!env.BACKEND_DELAY_MS) env.BACKEND_DELAY_MS = "50";
if (!env.TEST_TIMEOUT_MS) env.TEST_TIMEOUT_MS = "180000";

const child = spawn(process.execPath, [scriptPath], {
  stdio: "inherit",
  env,
});

child.on("exit", (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code == null ? 1 : code);
});
