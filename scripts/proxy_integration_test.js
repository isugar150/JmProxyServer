#!/usr/bin/env node
"use strict";

const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");
const { spawn, spawnSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const LIB_CP = "lib/*";
const JAVA_CP = process.platform === "win32" ? `out;${LIB_CP}` : `out:${LIB_CP}`;

const PROXY_PORT = Number(process.env.PROXY_PORT || 19510);
const BACKEND1_PORT = Number(process.env.BACKEND1_PORT || 19521);
const BACKEND2_PORT = Number(process.env.BACKEND2_PORT || 19522);

const SHORT_CLIENTS = Number(process.env.SHORT_CLIENTS || 12);
const LONG_CLIENTS = Number(process.env.LONG_CLIENTS || 12);
const SHORT_MIN_MS = Number(process.env.SHORT_MIN_MS || 1000);
const SHORT_MAX_MS = Number(process.env.SHORT_MAX_MS || 60000);
const SHORT_GAP_MIN_MS = Number(process.env.SHORT_GAP_MIN_MS || 30);
const SHORT_GAP_MAX_MS = Number(process.env.SHORT_GAP_MAX_MS || 160);
const LONG_MIN_MS = Number(process.env.LONG_MIN_MS || 1000);
const LONG_MAX_MS = Number(process.env.LONG_MAX_MS || 60000);
const LONG_PING_INTERVAL_MS = Number(process.env.LONG_PING_INTERVAL_MS || 1000);

const LB_REQUESTS = Number(process.env.LB_REQUESTS || 60);
const HIGH_TOTAL_REQUESTS = Number(process.env.HIGH_TOTAL_REQUESTS || 5000);
const HIGH_CONCURRENCY = Number(process.env.HIGH_CONCURRENCY || 300);

const CONNECT_TIMEOUT_MS = Number(process.env.CONNECT_TIMEOUT_MS || 3000);
const IO_TIMEOUT_MS = Number(process.env.IO_TIMEOUT_MS || 5000);
const CONNECT_RETRY_COUNT = Number(process.env.CONNECT_RETRY_COUNT || 30);
const CONNECT_RETRY_DELAY_MS = Number(process.env.CONNECT_RETRY_DELAY_MS || 50);
const SHORT_REQUEST_RETRY_COUNT = Number(process.env.SHORT_REQUEST_RETRY_COUNT || 30);
const SHORT_REQUEST_RETRY_DELAY_MS = Number(process.env.SHORT_REQUEST_RETRY_DELAY_MS || 80);
const LONG_REQUEST_RETRY_COUNT = Number(process.env.LONG_REQUEST_RETRY_COUNT || 12);
const LONG_REQUEST_RETRY_DELAY_MS = Number(process.env.LONG_REQUEST_RETRY_DELAY_MS || 100);
const SHORT_START_JITTER_MS = Number(process.env.SHORT_START_JITTER_MS || 300);
const CASE_COOLDOWN_MS = Number(process.env.CASE_COOLDOWN_MS || 10000);

function nowMs() {
  return Date.now();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function randomInt(min, max) {
  const lo = Math.max(1, Math.min(min, max));
  const hi = Math.max(lo, Math.max(min, max));
  return Math.floor(Math.random() * (hi - lo + 1)) + lo;
}

function isTransientConnectError(err) {
  if (!err) return false;
  if (err.message && err.message.includes("connect timeout")) return true;
  if (!err.code) return false;
  return (
    err.code === "EADDRINUSE" ||
    err.code === "EADDRNOTAVAIL" ||
    err.code === "ECONNREFUSED" ||
    err.code === "ECONNRESET"
  );
}

function isTransientRequestError(err) {
  if (!err) return false;
  if (isTransientConnectError(err)) return true;
  const msg = err.message || "";
  return (
    msg.includes("connect timeout") ||
    msg === "io timeout" ||
    msg === "socket closed" ||
    msg === "socket ended"
  );
}

function compileProject() {
  const cmd = process.platform === "win32" ? "powershell" : "sh";
  const args =
    process.platform === "win32"
      ? [
          "-NoProfile",
          "-Command",
          'javac -encoding UTF-8 -cp "lib/*" -d out src/com/namejm/proxy/*.java',
        ]
      : ["-lc", 'javac -encoding UTF-8 -cp "lib/*" -d out src/com/namejm/proxy/*.java'];

  const result = spawnSync(cmd, args, { cwd: ROOT, stdio: "inherit" });
  if (result.status !== 0) {
    throw new Error("Compilation failed.");
  }
}

function createTempConfig() {
  const configPath = path.join(os.tmpdir(), `jmproxy-integration-${Date.now()}.yml`);
  const yaml = `
global:
  hotReloadEnabled: false
  geoIpDbPath: ${path.join(ROOT, "config", "GeoLite2-Country.mmdb").replace(/\\/g, "/")}

proxy:
  - type: in
    name: integration-proxy
    bindPort: ${PROXY_PORT}
    allowedCountries: [any]
    lbStrategy: round_robin
    lbHealthCheckIntervalSeconds: 1
    healthCheckInitialDelaySeconds: 0
    healthCheckConnectTimeoutMillis: 500
    forwardConnectTimeoutMillis: 1000
    transferTimeoutSeconds: 0
    halfCloseLingerSeconds: 5
    maxActiveRelays: 10000
    lb:
      - name: b1
        forwardHost: 127.0.0.1
        forwardPort: ${BACKEND1_PORT}
      - name: b2
        forwardHost: 127.0.0.1
        forwardPort: ${BACKEND2_PORT}
`;
  fs.writeFileSync(configPath, yaml.trimStart(), "utf8");
  return configPath;
}

function startTaggedBackend(port, tag) {
  return new Promise((resolve, reject) => {
    const sockets = new Set();
    const server = net.createServer((socket) => {
      sockets.add(socket);
      socket.on("close", () => sockets.delete(socket));
      socket.on("error", () => {});
      socket.on("data", (chunk) => {
        socket.write(`${tag}:${chunk.toString("utf8")}`);
      });
    });
    server.on("error", reject);
    server.listen(port, "127.0.0.1", () =>
      resolve({
        close: () =>
          new Promise((done) => {
            for (const s of sockets) {
              try {
                s.destroy();
              } catch (_) {}
            }
            server.close(() => done());
          }),
      })
    );
  });
}

function startProxy(configPath) {
  const child = spawn("java", ["-cp", JAVA_CP, "com.namejm.proxy.ProxyServer", configPath], {
    cwd: ROOT,
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.on("data", (d) => process.stdout.write(`[proxy] ${d}`));
  child.stderr.on("data", (d) => process.stderr.write(`[proxy-err] ${d}`));
  return child;
}

function connectSocket(port, timeoutMs = CONNECT_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: "127.0.0.1", port });
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error(`connect timeout ${timeoutMs}ms`));
    }, timeoutMs);
    socket.once("connect", () => {
      clearTimeout(timer);
      resolve(socket);
    });
    socket.once("error", (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

async function connectSocketWithRetry(port, timeoutMs = CONNECT_TIMEOUT_MS) {
  let lastErr = null;
  const attempts = Math.max(1, CONNECT_RETRY_COUNT);
  for (let i = 0; i < attempts; i++) {
    try {
      return await connectSocket(port, timeoutMs);
    } catch (err) {
      lastErr = err;
      if (!isTransientConnectError(err) || i === attempts - 1) {
        throw err;
      }
      await sleep(Math.max(0, CONNECT_RETRY_DELAY_MS));
    }
  }
  throw lastErr || new Error("connect failed");
}

async function waitForPort(port, timeoutMs) {
  const start = nowMs();
  while (nowMs() - start < timeoutMs) {
    try {
      const s = await connectSocket(port, 500);
      s.destroy();
      return;
    } catch (_) {
      await sleep(100);
    }
  }
  throw new Error(`port ${port} did not open within ${timeoutMs}ms`);
}

async function oneShotRequest(payload) {
  const socket = await connectSocketWithRetry(PROXY_PORT, CONNECT_TIMEOUT_MS);
  socket.setTimeout(IO_TIMEOUT_MS);
  return new Promise((resolve, reject) => {
    let settled = false;
    let buf = "";
    function doneOk(value) {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(value);
    }
    function doneErr(err) {
      if (settled) return;
      settled = true;
      cleanup();
      reject(err);
    }
    function cleanup() {
      socket.removeAllListeners("data");
      socket.removeAllListeners("timeout");
      socket.removeAllListeners("error");
      socket.removeAllListeners("close");
      socket.removeAllListeners("end");
      socket.destroy();
    }
    socket.on("data", (chunk) => {
      buf += chunk.toString("utf8");
      if (buf.includes(payload)) {
        doneOk(buf);
      }
    });
    socket.on("timeout", () => doneErr(new Error("io timeout")));
    socket.on("error", doneErr);
    socket.on("close", () => {
      if (!settled) doneErr(new Error("socket closed"));
    });
    socket.on("end", () => {
      if (!settled) doneErr(new Error("socket ended"));
    });
    socket.write(payload);
  });
}

async function oneShotRequestWithRetry(payload) {
  let lastErr = null;
  const attempts = Math.max(1, SHORT_REQUEST_RETRY_COUNT);
  for (let i = 0; i < attempts; i++) {
    try {
      return await oneShotRequest(payload);
    } catch (err) {
      lastErr = err;
      if (!isTransientRequestError(err) || i === attempts - 1) {
        throw err;
      }
      const linearBackoff = i * Math.max(0, SHORT_REQUEST_RETRY_DELAY_MS);
      const delay = Math.max(0, SHORT_REQUEST_RETRY_DELAY_MS) + linearBackoff + randomInt(0, 30);
      await sleep(delay);
    }
  }
  throw lastErr || new Error("request failed");
}

function roundTripOnSocket(socket, payload, timeoutMs) {
  return new Promise((resolve, reject) => {
    let settled = false;
    let buf = "";
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error("io timeout"));
    }, Math.max(1, timeoutMs));

    function cleanup() {
      clearTimeout(timer);
      socket.off("data", onData);
      socket.off("error", onErr);
      socket.off("close", onClose);
      socket.off("end", onEnd);
    }
    function doneOk(response) {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(response);
    }
    function doneErr(err) {
      if (settled) return;
      settled = true;
      cleanup();
      reject(err);
    }
    function onData(chunk) {
      buf += chunk.toString("utf8");
      if (buf.includes(payload)) {
        doneOk(buf);
      }
    }
    function onErr(err) {
      doneErr(err);
    }
    function onClose() {
      doneErr(new Error("socket closed"));
    }
    function onEnd() {
      doneErr(new Error("socket ended"));
    }

    socket.on("data", onData);
    socket.on("error", onErr);
    socket.on("close", onClose);
    socket.on("end", onEnd);
    socket.write(payload);
  });
}

async function runShortLivedRandomCase() {
  const started = nowMs();
  const workers = [];
  const failReasons = new Map();

  for (let i = 0; i < SHORT_CLIENTS; i++) {
    workers.push(
      (async () => {
        if (SHORT_START_JITTER_MS > 0) {
          await sleep(randomInt(0, SHORT_START_JITTER_MS));
        }
        const durationMs = randomInt(SHORT_MIN_MS, SHORT_MAX_MS);
        const deadline = nowMs() + durationMs;
        let ok = 0;
        let fail = 0;
        while (nowMs() < deadline) {
          const payload = `SHORT_${i}_${ok + fail}_${nowMs()}\n`;
          try {
            await oneShotRequestWithRetry(payload);
            ok++;
          } catch (err) {
            fail++;
            const code = err && err.code ? err.code : "NO_CODE";
            const msg = err && err.message ? err.message : "unknown";
            const key = `${code}:${msg}`;
            failReasons.set(key, (failReasons.get(key) || 0) + 1);
          }
          await sleep(randomInt(SHORT_GAP_MIN_MS, SHORT_GAP_MAX_MS));
        }
        return { durationMs, ok, fail };
      })()
    );
  }

  const results = await Promise.all(workers);
  const elapsed = nowMs() - started;
  const totalOk = results.reduce((s, r) => s + r.ok, 0);
  const totalFail = results.reduce((s, r) => s + r.fail, 0);
  const minDuration = Math.min(...results.map((r) => r.durationMs));
  const maxDuration = Math.max(...results.map((r) => r.durationMs));
  const topFailReasons = Array.from(failReasons.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([reason, count]) => ({ reason, count }));
  return {
    name: "short_lived_random",
    clients: SHORT_CLIENTS,
    minDuration,
    maxDuration,
    elapsed,
    ok: totalOk,
    fail: totalFail,
    topFailReasons,
    pass: totalOk > 0 && totalFail === 0,
  };
}

async function runLongLivedRandomCase() {
  const started = nowMs();
  const workers = [];
  const failReasons = new Map();

  for (let i = 0; i < LONG_CLIENTS; i++) {
    workers.push(
      (async () => {
        const durationMs = randomInt(LONG_MIN_MS, LONG_MAX_MS);
        const deadline = nowMs() + durationMs;
        let socket = null;
        let ok = 0;
        let fail = 0;
        while (nowMs() < deadline) {
          const payload = `LONG_${i}_${ok + fail}_${nowMs()}\n`;
          let requestSucceeded = false;
          let lastErr = null;

          for (let attempt = 0; attempt < Math.max(1, LONG_REQUEST_RETRY_COUNT); attempt++) {
            try {
              if (!socket || socket.destroyed) {
                socket = await connectSocketWithRetry(PROXY_PORT, CONNECT_TIMEOUT_MS);
                socket.setTimeout(0);
              }
              await roundTripOnSocket(socket, payload, IO_TIMEOUT_MS);
              ok++;
              requestSucceeded = true;
              break;
            } catch (err) {
              lastErr = err;
              if (socket) {
                try {
                  socket.destroy();
                } catch (_) {}
              }
              socket = null;

              if (!isTransientRequestError(err)) {
                break;
              }

              const retryDelay =
                Math.max(0, LONG_REQUEST_RETRY_DELAY_MS) +
                attempt * Math.max(0, LONG_REQUEST_RETRY_DELAY_MS) +
                randomInt(0, 40);
              await sleep(retryDelay);
            }
          }

          if (!requestSucceeded) {
            fail++;
            const code = lastErr && lastErr.code ? lastErr.code : "NO_CODE";
            const msg = lastErr && lastErr.message ? lastErr.message : "unknown";
            const key = `${code}:${msg}`;
            failReasons.set(key, (failReasons.get(key) || 0) + 1);
          }

          await sleep(LONG_PING_INTERVAL_MS);
        }

        if (socket) {
          try {
            socket.destroy();
          } catch (_) {}
        }

        return { durationMs, ok, fail };
      })()
    );
  }

  const results = await Promise.all(workers);
  const elapsed = nowMs() - started;
  const totalOk = results.reduce((s, r) => s + r.ok, 0);
  const totalFail = results.reduce((s, r) => s + r.fail, 0);
  const minDuration = Math.min(...results.map((r) => r.durationMs));
  const maxDuration = Math.max(...results.map((r) => r.durationMs));
  const topFailReasons = Array.from(failReasons.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([reason, count]) => ({ reason, count }));
  return {
    name: "long_lived_random",
    clients: LONG_CLIENTS,
    minDuration,
    maxDuration,
    elapsed,
    ok: totalOk,
    fail: totalFail,
    topFailReasons,
    pass: totalOk > 0 && totalFail === 0,
  };
}

async function runLbCase() {
  let b1 = 0;
  let b2 = 0;
  let fail = 0;
  for (let i = 0; i < LB_REQUESTS; i++) {
    const payload = `LB_${i}_${nowMs()}\n`;
    try {
      const resp = await oneShotRequest(payload);
      if (resp.startsWith("B1:")) b1++;
      else if (resp.startsWith("B2:")) b2++;
      else fail++;
    } catch (_) {
      fail++;
    }
  }

  return {
    name: "lb_round_robin",
    requests: LB_REQUESTS,
    b1,
    b2,
    fail,
    pass: fail === 0 && b1 > 0 && b2 > 0,
  };
}

async function runHighTrafficCase() {
  let inFlight = 0;
  let nextId = 0;
  let done = 0;
  let ok = 0;
  let fail = 0;
  const failReasons = new Map();
  const started = nowMs();

  await new Promise((resolve) => {
    function pump() {
      while (inFlight < HIGH_CONCURRENCY && nextId < HIGH_TOTAL_REQUESTS) {
        inFlight++;
        const id = nextId++;
        const payload = `HIGH_${id}_${nowMs()}\n`;
        oneShotRequest(payload)
          .then(() => {
            ok++;
          })
          .catch((err) => {
            fail++;
            const code = err && err.code ? err.code : "NO_CODE";
            const msg = err && err.message ? err.message : "unknown";
            const key = `${code}:${msg}`;
            failReasons.set(key, (failReasons.get(key) || 0) + 1);
          })
          .finally(() => {
            inFlight--;
            done++;
            if (done >= HIGH_TOTAL_REQUESTS) {
              resolve();
            } else {
              pump();
            }
          });
      }
    }
    pump();
  });

  const elapsed = nowMs() - started;
  const rps = (ok / Math.max(1, elapsed)) * 1000;
  const topFailReasons = Array.from(failReasons.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([reason, count]) => ({ reason, count }));
  return {
    name: "high_traffic",
    total: HIGH_TOTAL_REQUESTS,
    concurrency: HIGH_CONCURRENCY,
    elapsed,
    ok,
    fail,
    rps: Number(rps.toFixed(2)),
    topFailReasons,
    pass: ok > 0 && fail === 0,
  };
}

function printCaseResult(result) {
  const head = result.pass ? "[PASS]" : "[FAIL]";
  console.log(`${head} ${result.name} ${JSON.stringify(result)}`);
}

async function stopProxy(proxy) {
  if (!proxy || proxy.killed) return;
  proxy.kill("SIGTERM");
  await sleep(700);
  if (!proxy.killed) {
    proxy.kill("SIGKILL");
  }
}

async function main() {
  let backend1 = null;
  let backend2 = null;
  let proxy = null;
  let configPath = null;

  try {
    console.log("[INFO] compiling project...");
    compileProject();

    console.log("[INFO] starting backends...");
    backend1 = await startTaggedBackend(BACKEND1_PORT, "B1");
    backend2 = await startTaggedBackend(BACKEND2_PORT, "B2");

    console.log("[INFO] starting proxy...");
    configPath = createTempConfig();
    proxy = startProxy(configPath);
    await waitForPort(PROXY_PORT, 15000);

    console.log("[INFO] running integration cases...");

    const shortResult = await runShortLivedRandomCase();
    printCaseResult(shortResult);
    if (CASE_COOLDOWN_MS > 0) {
      console.log(`[INFO] cooldown ${CASE_COOLDOWN_MS}ms before next case`);
      await sleep(CASE_COOLDOWN_MS);
    }

    const longResult = await runLongLivedRandomCase();
    printCaseResult(longResult);
    if (CASE_COOLDOWN_MS > 0) {
      console.log(`[INFO] cooldown ${CASE_COOLDOWN_MS}ms before next case`);
      await sleep(CASE_COOLDOWN_MS);
    }

    const lbResult = await runLbCase();
    printCaseResult(lbResult);
    if (CASE_COOLDOWN_MS > 0) {
      console.log(`[INFO] cooldown ${CASE_COOLDOWN_MS}ms before next case`);
      await sleep(CASE_COOLDOWN_MS);
    }

    const highResult = await runHighTrafficCase();
    printCaseResult(highResult);

    const allPass = shortResult.pass && longResult.pass && lbResult.pass && highResult.pass;
    if (!allPass) {
      throw new Error("one or more integration cases failed");
    }

    console.log("INTEGRATION_TEST_RESULT: ALL_PASSED");
  } catch (err) {
    console.error("INTEGRATION_TEST_FAILED:", err.message);
    process.exitCode = 1;
  } finally {
    await stopProxy(proxy);
    if (backend1) await backend1.close();
    if (backend2) await backend2.close();
    if (configPath && fs.existsSync(configPath)) {
      try {
        fs.unlinkSync(configPath);
      } catch (_) {}
    }
  }
}

main();
