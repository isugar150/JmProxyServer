#!/usr/bin/env node
"use strict";

const net = require("net");

const PROXY_PORT = Number(process.env.PROXY_PORT || 19310);
const BACKEND_PORT = Number(process.env.BACKEND_PORT || 19320);
const CONNECTIONS = Number(process.env.CONNECTIONS || 200);
const REQUESTS_PER_CONNECTION = Number(process.env.REQUESTS_PER_CONNECTION || 20);
const CONNECT_TIMEOUT_MS = Number(process.env.CONNECT_TIMEOUT_MS || 3000);
const IO_TIMEOUT_MS = Number(process.env.IO_TIMEOUT_MS || 5000);
const START_BACKEND = (process.env.START_BACKEND || "true").toLowerCase() !== "false";

function nowMs() {
  return Date.now();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function startEchoServer(port) {
  return new Promise((resolve, reject) => {
    const sockets = new Set();
    const server = net.createServer((socket) => {
      sockets.add(socket);
      socket.on("close", () => sockets.delete(socket));
      socket.on("error", () => {});
      socket.pipe(socket);
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
            const guard = setTimeout(() => done(), 2000);
            server.close(() => {
              clearTimeout(guard);
              done();
            });
          }),
      })
    );
  });
}

function connectSocket(port, timeoutMs) {
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

async function waitForProxyOpen(port, timeoutMs) {
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
  throw new Error(`proxy port ${port} did not open in ${timeoutMs}ms`);
}

function roundTrip(socket, payload, timeoutMs) {
  return new Promise((resolve, reject) => {
    let settled = false;
    let buf = "";
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error("io timeout"));
    }, timeoutMs);

    function cleanup() {
      clearTimeout(timer);
      socket.off("data", onData);
      socket.off("error", onError);
      socket.off("close", onClose);
      socket.off("end", onEnd);
    }
    function doneOk(latency) {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(latency);
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
        doneOk(0);
      }
    }
    function onError(err) {
      doneErr(err);
    }
    function onClose() {
      doneErr(new Error("socket closed"));
    }
    function onEnd() {
      doneErr(new Error("socket ended"));
    }

    socket.on("data", onData);
    socket.on("error", onError);
    socket.on("close", onClose);
    socket.on("end", onEnd);
    socket.write(payload);
  });
}

async function runWorker(connId) {
  let socket = null;
  let ok = 0;
  let fail = 0;
  const latencies = [];
  try {
    socket = await connectSocket(PROXY_PORT, CONNECT_TIMEOUT_MS);
    socket.setTimeout(0);
    for (let i = 0; i < REQUESTS_PER_CONNECTION; i++) {
      const payload = `PERSIST_${connId}_${i}\n`;
      const t0 = nowMs();
      try {
        await roundTrip(socket, payload, IO_TIMEOUT_MS);
        ok++;
        latencies.push(nowMs() - t0);
      } catch (_) {
        fail++;
      }
    }
  } catch (_) {
    fail += REQUESTS_PER_CONNECTION;
  } finally {
    if (socket) {
      socket.destroy();
    }
  }
  return { ok, fail, latencies };
}

function percentile(sortedArr, p) {
  if (sortedArr.length === 0) return 0;
  const idx = Math.min(sortedArr.length - 1, Math.floor((p / 100) * sortedArr.length));
  return sortedArr[idx];
}

async function main() {
  let backend = null;
  try {
    if (START_BACKEND) {
      backend = await startEchoServer(BACKEND_PORT);
    }
    await waitForProxyOpen(PROXY_PORT, 15000);

    const start = nowMs();
    const results = await Promise.all(Array.from({ length: CONNECTIONS }, (_, i) => runWorker(i + 1)));
    const elapsedMs = nowMs() - start;

    let ok = 0;
    let fail = 0;
    const latencies = [];
    for (const r of results) {
      ok += r.ok;
      fail += r.fail;
      latencies.push(...r.latencies);
    }

    const total = CONNECTIONS * REQUESTS_PER_CONNECTION;
    const sorted = latencies.slice().sort((a, b) => a - b);
    const avgLatency = latencies.length ? latencies.reduce((s, v) => s + v, 0) / latencies.length : 0;
    const rps = (ok / Math.max(1, elapsedMs)) * 1000;
    const successRate = (ok / Math.max(1, total)) * 100;

    console.log("PERSISTENT_LOAD_TEST_RESULT");
    console.log(`connections=${CONNECTIONS}`);
    console.log(`requests_per_connection=${REQUESTS_PER_CONNECTION}`);
    console.log(`total=${total}`);
    console.log(`ok=${ok}`);
    console.log(`fail=${fail}`);
    console.log(`success_rate=${successRate.toFixed(2)}%`);
    console.log(`elapsed_ms=${elapsedMs}`);
    console.log(`rps=${rps.toFixed(2)}`);
    console.log(`avg_latency_ms=${avgLatency.toFixed(2)}`);
    console.log(`latency_p50_ms=${percentile(sorted, 50)}`);
    console.log(`latency_p95_ms=${percentile(sorted, 95)}`);
    console.log(`latency_p99_ms=${percentile(sorted, 99)}`);

    if (fail > 0) {
      process.exitCode = 1;
    }
  } catch (err) {
    console.error("PERSISTENT_LOAD_TEST_FAILED:", err.message);
    process.exitCode = 1;
  } finally {
    if (backend) {
      await backend.close();
    }
  }
}

main();
