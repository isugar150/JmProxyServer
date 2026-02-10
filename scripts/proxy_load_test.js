#!/usr/bin/env node
"use strict";

const net = require("net");

const PROXY_PORT = Number(process.env.PROXY_PORT || 19310);
const BACKEND_PORT = Number(process.env.BACKEND_PORT || 19320);
const TOTAL_REQUESTS = Number(process.env.TOTAL_REQUESTS || 2000);
const CONCURRENCY = Number(process.env.CONCURRENCY || 200);
const CONNECT_TIMEOUT_MS = Number(process.env.CONNECT_TIMEOUT_MS || 3000);
const IO_TIMEOUT_MS = Number(process.env.IO_TIMEOUT_MS || 3000);
const PAYLOAD = (process.env.PAYLOAD || "LOAD_TEST_PAYLOAD_1234567890\n");
const START_BACKEND = (process.env.START_BACKEND || "true").toLowerCase() !== "false";
const BACKEND_MODE = (process.env.BACKEND_MODE || "echo").toLowerCase();
const BACKEND_DELAY_MS = Number(process.env.BACKEND_DELAY_MS || 0);
const TEST_TIMEOUT_MS = Number(process.env.TEST_TIMEOUT_MS || 120000);

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
      if (BACKEND_MODE === "slow") {
        socket.on("data", (chunk) => {
          setTimeout(() => {
            if (!socket.destroyed) {
              socket.write(chunk);
            }
          }, Math.max(0, BACKEND_DELAY_MS));
        });
      } else {
        socket.pipe(socket);
      }
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

async function runSingleRequest() {
  const t0 = nowMs();
  const socket = await connectSocket(PROXY_PORT, CONNECT_TIMEOUT_MS);
  socket.setTimeout(IO_TIMEOUT_MS);
  return new Promise((resolve, reject) => {
    let settled = false;
    let buf = "";
    function cleanup() {
      socket.removeAllListeners("data");
      socket.removeAllListeners("timeout");
      socket.removeAllListeners("error");
      socket.removeAllListeners("end");
      socket.removeAllListeners("close");
    }
    function resolveOnce(value) {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(value);
    }
    function rejectOnce(err) {
      if (settled) return;
      settled = true;
      cleanup();
      reject(err);
    }
    socket.on("data", (chunk) => {
      buf += chunk.toString("utf8");
      if (buf.includes(PAYLOAD)) {
        socket.end();
        resolveOnce(nowMs() - t0);
      }
    });
    socket.on("timeout", () => {
      socket.destroy();
      rejectOnce(new Error("io timeout"));
    });
    socket.on("error", rejectOnce);
    socket.on("end", () => {
      if (!buf.includes(PAYLOAD)) {
        rejectOnce(new Error("upstream ended before expected payload"));
      }
    });
    socket.on("close", () => {
      if (!buf.includes(PAYLOAD)) {
        rejectOnce(new Error("socket closed before expected payload"));
      }
    });
    socket.write(PAYLOAD);
  });
}

function percentile(sortedArr, p) {
  if (sortedArr.length === 0) return 0;
  const idx = Math.min(sortedArr.length - 1, Math.floor((p / 100) * sortedArr.length));
  return sortedArr[idx];
}

async function runLoad() {
  let inFlight = 0;
  let nextId = 0;
  let done = 0;
  let ok = 0;
  let fail = 0;
  const latencies = [];

  return new Promise((resolve, reject) => {
    const testTimer = setTimeout(() => {
      reject(new Error(`load run timeout after ${TEST_TIMEOUT_MS}ms`));
    }, Math.max(1000, TEST_TIMEOUT_MS));

    function pump() {
      while (inFlight < CONCURRENCY && nextId < TOTAL_REQUESTS) {
        inFlight++;
        nextId++;
        runSingleRequest()
          .then((lat) => {
            ok++;
            latencies.push(lat);
          })
          .catch(() => {
            fail++;
          })
          .finally(() => {
            inFlight--;
            done++;
            if (done >= TOTAL_REQUESTS) {
              clearTimeout(testTimer);
              resolve({ ok, fail, latencies });
            } else {
              pump();
            }
          });
      }
    }
    pump();
  });
}

async function main() {
  let backend = null;
  try {
    if (START_BACKEND) {
      backend = await startEchoServer(BACKEND_PORT);
    }
    await waitForProxyOpen(PROXY_PORT, 15000);

    const start = nowMs();
    const result = await runLoad();
    const elapsedMs = nowMs() - start;
    const rps = (result.ok / Math.max(1, elapsedMs)) * 1000;
    const sorted = result.latencies.slice().sort((a, b) => a - b);

    const p50 = percentile(sorted, 50);
    const p95 = percentile(sorted, 95);
    const p99 = percentile(sorted, 99);
    const successRate = (result.ok / Math.max(1, TOTAL_REQUESTS)) * 100;

    console.log("LOAD_TEST_RESULT");
    console.log(`backend_mode=${BACKEND_MODE}`);
    console.log(`backend_delay_ms=${BACKEND_DELAY_MS}`);
    console.log(`total=${TOTAL_REQUESTS}`);
    console.log(`concurrency=${CONCURRENCY}`);
    console.log(`ok=${result.ok}`);
    console.log(`fail=${result.fail}`);
    console.log(`success_rate=${successRate.toFixed(2)}%`);
    console.log(`elapsed_ms=${elapsedMs}`);
    console.log(`rps=${rps.toFixed(2)}`);
    console.log(`latency_p50_ms=${p50}`);
    console.log(`latency_p95_ms=${p95}`);
    console.log(`latency_p99_ms=${p99}`);

    if (result.fail > 0) {
      process.exitCode = 1;
    }
  } catch (err) {
    console.error("LOAD_TEST_FAILED:", err.message);
    process.exitCode = 1;
  } finally {
    if (backend) {
      await backend.close();
    }
  }
}

main();
