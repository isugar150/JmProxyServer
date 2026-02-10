#!/usr/bin/env node
"use strict";

const net = require("net");

const PROXY_PORT = Number(process.env.PROXY_PORT || 19210);
const BACKEND_PORT = Number(process.env.BACKEND_PORT || 19220);
const CONNECT_TIMEOUT_MS = 3000;
const IO_TIMEOUT_MS = 2000;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function onceWithTimeout(emitter, event, timeoutMs, timeoutMessage) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => {
      cleanup();
      reject(new Error(timeoutMessage));
    }, timeoutMs);
    function onEvent(...args) {
      cleanup();
      resolve(args);
    }
    function cleanup() {
      clearTimeout(t);
      emitter.removeListener(event, onEvent);
    }
    emitter.on(event, onEvent);
  });
}

function connectSocket(port, timeoutMs = CONNECT_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: "127.0.0.1", port });
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error(`Connect timeout (${timeoutMs}ms)`));
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
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    try {
      const s = await connectSocket(port, 500);
      s.destroy();
      return;
    } catch (_) {
      await sleep(100);
    }
  }
  throw new Error(`Proxy port ${port} did not open within ${timeoutMs}ms`);
}

function startEchoServer(port) {
  return new Promise((resolve, reject) => {
    const server = net.createServer((socket) => {
      socket.on("error", () => {});
      socket.pipe(socket);
    });
    server.on("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

async function requestEcho(port, payload) {
  const socket = await connectSocket(port);
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
      if (buf.includes(payload)) {
        socket.end();
        resolveOnce(buf);
      }
    });
    socket.on("timeout", () => {
      socket.destroy();
      rejectOnce(new Error("I/O timeout while waiting echo response"));
    });
    socket.on("error", rejectOnce);
    socket.on("end", () => {
      if (!buf.includes(payload)) {
        rejectOnce(new Error("Socket ended before echo payload"));
      }
    });
    socket.on("close", () => {
      if (!buf.includes(payload)) {
        rejectOnce(new Error("Socket closed before echo payload"));
      }
    });
    socket.write(payload);
  });
}

async function testBasicRelay() {
  const payload = "PING_CLIENT_E2E\n";
  const resp = await requestEcho(PROXY_PORT, payload);
  if (!resp.includes(payload)) {
    throw new Error("Basic relay failed: response mismatch.");
  }
  console.log("[PASS] basic relay");
}

async function testMaxActiveRelays() {
  const s1 = await connectSocket(PROXY_PORT);
  const s2 = await connectSocket(PROXY_PORT);
  s1.setTimeout(IO_TIMEOUT_MS);
  s2.setTimeout(IO_TIMEOUT_MS);
  s1.write("HOLD1\n");
  s2.write("HOLD2\n");

  const s3 = await connectSocket(PROXY_PORT);
  s3.setTimeout(IO_TIMEOUT_MS);
  s3.write("SHOULD_FAIL\n");

  let closedQuickly = false;
  try {
    await onceWithTimeout(s3, "close", 1200, "Third connection did not close quickly");
    closedQuickly = true;
  } finally {
    s3.destroy();
    s1.destroy();
    s2.destroy();
  }

  if (!closedQuickly) {
    throw new Error("maxActiveRelays was not enforced as expected.");
  }
  console.log("[PASS] maxActiveRelays enforcement");
}

async function main() {
  let backend = null;
  try {
    backend = await startEchoServer(BACKEND_PORT);
    await waitForProxyOpen(PROXY_PORT, 15000);

    await testBasicRelay();
    await testMaxActiveRelays();

    console.log("CLIENT_E2E_ALL_TESTS_PASSED");
  } catch (err) {
    console.error("CLIENT_E2E_TEST_FAILED:", err.message);
    process.exitCode = 1;
  } finally {
    if (backend) {
      await new Promise((resolve) => backend.close(resolve));
    }
  }
}

main();
