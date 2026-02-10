#!/usr/bin/env node
"use strict";

const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");
const { spawn, spawnSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const OUT_DIR = path.join(ROOT, "out");
const CONFIG_DIR = path.join(ROOT, "config");
const LIB_CP = process.platform === "win32" ? "lib/*" : "lib/*";
const JAVA_CP = process.platform === "win32" ? `out;${LIB_CP}` : `out:${LIB_CP}`;

const PROXY_PORT = 19110;
const BACKEND_PORT = 19120;
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
  const configPath = path.join(os.tmpdir(), `jmproxy-e2e-${Date.now()}.yml`);
  const yaml = `
global:
  hotReloadEnabled: false
  geoIpDbPath: ${path.join(CONFIG_DIR, "GeoLite2-Country.mmdb").replace(/\\/g, "/")}

proxy:
  - type: in
    name: e2e-proxy
    bindPort: ${PROXY_PORT}
    forwardHost: localhost
    forwardPort: ${BACKEND_PORT}
    allowedCountries: [any]
    transferTimeoutSeconds: 0
    halfCloseLingerSeconds: 120
    maxActiveRelays: 2
`;
  fs.writeFileSync(configPath, yaml.trimStart(), "utf8");
  return configPath;
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

function startProxy(configPath) {
  const child = spawn("java", ["-cp", JAVA_CP, "com.namejm.proxy.ProxyServer", configPath], {
    cwd: ROOT,
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.on("data", (d) => process.stdout.write(`[proxy] ${d}`));
  child.stderr.on("data", (d) => process.stderr.write(`[proxy-err] ${d}`));
  return child;
}

async function waitForPort(port, timeoutMs) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    try {
      const socket = await connectSocket(port, CONNECT_TIMEOUT_MS);
      socket.destroy();
      return;
    } catch (_) {
      await sleep(100);
    }
  }
  throw new Error(`Port ${port} did not open within ${timeoutMs}ms`);
}

function connectSocket(port, timeoutMs) {
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

async function requestEcho(port, payload) {
  const socket = await connectSocket(port, CONNECT_TIMEOUT_MS);
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
  const payload = "PING_E2E\n";
  const resp = await requestEcho(PROXY_PORT, payload);
  if (!resp.includes(payload)) {
    throw new Error("Basic relay failed: response mismatch.");
  }
  console.log("[PASS] basic relay");
}

async function testMaxActiveRelays() {
  const s1 = await connectSocket(PROXY_PORT, CONNECT_TIMEOUT_MS);
  const s2 = await connectSocket(PROXY_PORT, CONNECT_TIMEOUT_MS);
  s1.setTimeout(IO_TIMEOUT_MS);
  s2.setTimeout(IO_TIMEOUT_MS);

  s1.write("HOLD1\n");
  s2.write("HOLD2\n");

  const s3 = await connectSocket(PROXY_PORT, CONNECT_TIMEOUT_MS);
  s3.setTimeout(IO_TIMEOUT_MS);
  s3.write("SHOULD_FAIL\n");

  let blockedAsExpected = false;
  try {
    await Promise.race([
      onceWithTimeout(s3, "close", 2000, "no-close"),
      onceWithTimeout(s3, "end", 2000, "no-end"),
      onceWithTimeout(s3, "error", 2000, "no-error"),
    ]);
    blockedAsExpected = true;
  } catch (_) {
    blockedAsExpected = false;
  } finally {
    s3.destroy();
    s1.destroy();
    s2.destroy();
  }

  if (!blockedAsExpected) {
    throw new Error("maxActiveRelays was not enforced as expected.");
  }
  console.log("[PASS] maxActiveRelays enforcement");
}

async function main() {
  let backend = null;
  let proxy = null;
  let configPath = null;
  try {
    compileProject();
    backend = await startEchoServer(BACKEND_PORT);
    configPath = createTempConfig();
    proxy = startProxy(configPath);
    await waitForPort(PROXY_PORT, 15000);

    await testBasicRelay();
    await testMaxActiveRelays();

    console.log("ALL_TESTS_PASSED");
  } catch (err) {
    console.error("TEST_FAILED:", err.message);
    process.exitCode = 1;
  } finally {
    if (proxy && !proxy.killed) {
      proxy.kill("SIGTERM");
      await sleep(500);
      if (!proxy.killed) {
        proxy.kill("SIGKILL");
      }
    }
    if (backend) {
      await new Promise((resolve) => backend.close(resolve));
    }
    if (configPath && fs.existsSync(configPath)) {
      try {
        fs.unlinkSync(configPath);
      } catch (_) {}
    }
  }
}

main();
