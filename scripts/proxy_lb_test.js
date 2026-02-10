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

const PROXY_PORT = 19410;
const BACKEND1_PORT = 19421;
const BACKEND2_PORT = 19422;
const CONNECT_TIMEOUT_MS = 3000;
const IO_TIMEOUT_MS = 3000;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
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
  const configPath = path.join(os.tmpdir(), `jmproxy-lb-e2e-${Date.now()}.yml`);
  const yaml = `
global:
  hotReloadEnabled: false
  geoIpDbPath: ${path.join(ROOT, "config", "GeoLite2-Country.mmdb").replace(/\\/g, "/")}

proxy:
  - type: in
    name: lb-e2e-proxy
    bindPort: ${PROXY_PORT}
    allowedCountries: [any]
    lbStrategy: round_robin
    lbHealthCheckIntervalSeconds: 1
    healthCheckInitialDelaySeconds: 0
    healthCheckConnectTimeoutMillis: 500
    forwardConnectTimeoutMillis: 1000
    transferTimeoutSeconds: 0
    halfCloseLingerSeconds: 5
    maxActiveRelays: 2000
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
    const server = net.createServer((socket) => {
      socket.on("error", () => {});
      socket.on("data", (chunk) => {
        socket.write(`${tag}:${chunk.toString("utf8")}`);
      });
    });
    server.on("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

function stopServer(server) {
  return new Promise((resolve) => {
    if (!server) return resolve();
    server.close(() => resolve());
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

async function waitForPort(port, timeoutMs) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    try {
      const socket = await connectSocket(port, 500);
      socket.destroy();
      return;
    } catch (_) {
      await sleep(100);
    }
  }
  throw new Error(`Port ${port} did not open within ${timeoutMs}ms`);
}

async function requestOnce(payload) {
  const socket = await connectSocket(PROXY_PORT, CONNECT_TIMEOUT_MS);
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
    socket.on("timeout", () => doneErr(new Error("I/O timeout")));
    socket.on("error", doneErr);
    socket.on("close", () => {
      if (!settled) doneErr(new Error("Socket closed"));
    });
    socket.on("end", () => {
      if (!settled) doneErr(new Error("Socket ended"));
    });
    socket.write(payload);
  });
}

async function collectDistribution(requestCount) {
  let b1 = 0;
  let b2 = 0;
  for (let i = 0; i < requestCount; i++) {
    const payload = `LB_PING_${i}\n`;
    const resp = await requestOnce(payload);
    if (resp.startsWith("B1:")) b1++;
    if (resp.startsWith("B2:")) b2++;
  }
  return { b1, b2 };
}

async function testRoundRobinDistribution() {
  const dist = await collectDistribution(40);
  if (dist.b1 === 0 || dist.b2 === 0) {
    throw new Error(`Round robin distribution failed. b1=${dist.b1}, b2=${dist.b2}`);
  }
  console.log(`[PASS] round_robin distribution b1=${dist.b1}, b2=${dist.b2}`);
}

async function testFailover(server2) {
  await stopServer(server2);
  await sleep(2000); // wait at least one health-check cycle

  const dist = await collectDistribution(20);
  if (dist.b2 !== 0 || dist.b1 !== 20) {
    throw new Error(`Failover failed. Expected only B1 after B2 down. b1=${dist.b1}, b2=${dist.b2}`);
  }
  console.log(`[PASS] failover after backend2 down b1=${dist.b1}, b2=${dist.b2}`);
}

async function main() {
  let configPath = null;
  let proxy = null;
  let server1 = null;
  let server2 = null;
  try {
    compileProject();
    server1 = await startTaggedBackend(BACKEND1_PORT, "B1");
    server2 = await startTaggedBackend(BACKEND2_PORT, "B2");
    configPath = createTempConfig();
    proxy = startProxy(configPath);
    await waitForPort(PROXY_PORT, 15000);

    await testRoundRobinDistribution();
    await testFailover(server2);

    console.log("LB_E2E_ALL_TESTS_PASSED");
  } catch (err) {
    console.error("LB_E2E_TEST_FAILED:", err.message);
    process.exitCode = 1;
  } finally {
    if (proxy && !proxy.killed) {
      proxy.kill("SIGTERM");
      await sleep(500);
      if (!proxy.killed) proxy.kill("SIGKILL");
    }
    await stopServer(server1);
    await stopServer(server2);
    if (configPath && fs.existsSync(configPath)) {
      try {
        fs.unlinkSync(configPath);
      } catch (_) {}
    }
  }
}

main();
