# Repository Guidelines

## Project Structure & Module Organization
- `src/com/namejm/proxy`: core Java source.
  - `ProxyServer`: app entry point and orchestration only.
  - `ProxyConfigLoader`: YAML loading/parsing, global option merge, validation filtering.
  - `ProxyLifecycleManager`: start/stop/reload lifecycle for proxy instances.
  - `GlobalConfig`, `ConfigLoadResult`: configuration transfer models used by loader/server.
  - `ProxyMain`: runtime orchestration (accept loop, forwarding, scheduler).
  - `ForwardTarget`, `ForwardTargetSelector`: target model and LB candidate selection (`round_robin`, `ip_hash`).
  - `TargetHealthTracker`: health state transitions with fail/success thresholds.
  - `ConnectionPolicy`: allow/deny policy (`any`, `private`, `localhost`, country code).
  - `ProxyDto`: configuration schema and validation.
- `src/META-INF/MANIFEST.MF`: JAR entry point metadata (`Main-Class: com.namejm.proxy.ProxyServer`).
- `config/application.yml`: runtime proxy rules (`proxy` + optional `global`); `config/GeoLite2-Country.mmdb`: GeoIP database.
- `config/logback.xml`: external logging configuration file used at runtime.
- `lib/*.jar`: third-party dependencies (SnakeYAML, SLF4J, Logback, GeoIP2).
- `bin/startup.bat` and `bin/startup.sh`: production-style launch scripts for `JmProxyServer.jar`.

## Build, Test, and Development Commands
This repository does not use Maven/Gradle; build is script/IDE driven.
- Compile locally:
```powershell
javac -encoding UTF-8 -cp "lib/*" -d out src/com/namejm/proxy/*.java
```
- Package a runnable JAR:
```powershell
jar cfm JmProxyServer.jar src/META-INF/MANIFEST.MF -C out .
```
- Run with default config:
```powershell
java -jar JmProxyServer.jar
```
- Run with explicit config path:
```powershell
java -jar JmProxyServer.jar .\config\application.yml
```
- Main runtime mode:
  - `type: in`: inbound fixed-target forwarding
  - `type: out`: also fixed-target forwarding (not SOCKS/HTTP CONNECT)
- Windows/Linux launcher:
`bin\\startup.bat` or `bin/startup.sh`.

## Coding Style & Naming Conventions
- Target JDK 11+ compatible code, 4-space indentation, UTF-8 encoding.
- Use package `com.namejm.proxy`; class names in `PascalCase`, methods/fields in `camelCase`, constants in `UPPER_SNAKE_CASE`.
- Keep logging through SLF4J (`LoggerFactory.getLogger(...)`); avoid `System.out` except startup banner text.
- Keep `ProxyMain` orchestration-only; add reusable logic to dedicated classes under `src/com/namejm/proxy`.

## Testing Guidelines
- No automated test suite exists yet. Add tests under `src/test/java/com/namejm/proxy` when introducing non-trivial logic.
- Recommended focus: `ProxyDto.isValid`, `ConnectionPolicy`, `TargetHealthTracker` threshold transitions, and failover target selection.
- For now, validate changes by running the server with sample entries in `config/application.yml` and checking log output.

### E2E / Load Test Playbook (Executed in this repo)
- Prerequisites:
  - Java 11+
  - Node.js 18+
  - Install script deps once:
```powershell
cd scripts
npm install
```

- Compile before tests:
```powershell
javac -encoding UTF-8 -cp "lib/*" -d out src/com/namejm/proxy/*.java
```

- Integrated E2E (script starts backend + proxy):
```powershell
node scripts/proxy_e2e_test.js
```
  - Validates basic relay and `maxActiveRelays` enforcement.

- Client E2E (when proxy is already running externally):
```powershell
node scripts/proxy_client_e2e.js
```

- Load test (normal echo backend):
```powershell
cd scripts
$env:START_BACKEND='true'
$env:PROXY_PORT='19310'
$env:BACKEND_PORT='19320'
$env:TOTAL_REQUESTS='2000'
$env:CONCURRENCY='200'
$env:CONNECT_TIMEOUT_MS='3000'
$env:IO_TIMEOUT_MS='3000'
$env:TEST_TIMEOUT_MS='120000'
node proxy_load_test.js
```

- Load test (intentionally slow backend response):
```powershell
cd scripts
$env:START_BACKEND='true'
$env:PROXY_PORT='19310'
$env:BACKEND_PORT='19320'
$env:TOTAL_REQUESTS='600'
$env:CONCURRENCY='100'
$env:CONNECT_TIMEOUT_MS='3000'
$env:IO_TIMEOUT_MS='5000'
$env:TEST_TIMEOUT_MS='120000'
$env:BACKEND_MODE='slow'
$env:BACKEND_DELAY_MS='80'
node proxy_load_test_slow.js
```

- Proxy for load tests (separate process):
```powershell
java -cp "out;lib/*" com.namejm.proxy.ProxyServer config/loadtest.application.yml
```

- Notes:
  - Load scripts print `LOAD_TEST_RESULT` with success rate and latency percentiles.
  - Current load script returns exit code `1` when any request fails (`fail > 0`), even when test finishes correctly.
  - If port conflict occurs (`EADDRINUSE`), stop existing process on `19310` / `19320` first.
  - If proxy was started as a separate process for testing, always terminate it after tests complete.
  - Windows cleanup example:
```powershell
Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*com.namejm.proxy.ProxyServer*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

## Commit & Pull Request Guidelines
- Prefer concise subject lines under 72 chars; include scope when useful (example: `proxy: improve private IP filtering`).
- PRs should include: what changed, why, local verification steps, and config/runtime impact (ports, LB options, timeout values, GeoIP DB path).
- When changing configuration behavior, update both `README.md` and the commented sample in `config/application.yml` in the same PR.
- Link related issues and attach log snippets when behavior or access control output changes.

## Security & Configuration Tips
- Do not commit real internal IPs or environment-specific access rules in `config/application.yml`.
- Keep `GeoLite2-Country.mmdb` updated and verify file path exists before release.
- Review `allowedCountries` carefully; `any` bypasses source filtering.
- For long-lived connections, keep `clientSoTimeoutMillis` and `forwardSoTimeoutMillis` at `0` unless explicit idle cutoff is needed.
