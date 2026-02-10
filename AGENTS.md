# Repository Guidelines

## Project Structure & Module Organization
- `src/com/namejm/proxy`: core Java source.
  - `ProxyServer`: app entry point and YAML loading.
  - `ProxyMain`: runtime orchestration (accept loop, forwarding, scheduler).
  - `ForwardTarget`, `ForwardTargetSelector`: target model and round-robin candidate selection.
  - `TargetHealthTracker`: health state transitions with fail/success thresholds.
  - `ConnectionPolicy`: allow/deny policy (`any`, `private`, `localhost`, country code).
  - `ProxyDto`: configuration schema and validation.
- `src/META-INF/MANIFEST.MF`: JAR entry point metadata (`Main-Class: com.namejm.proxy.ProxyServer`).
- `resources/logback.xml`: logging configuration bundled on classpath.
- `config/application.yml`: runtime proxy rules; `config/GeoLite2-Country.mmdb`: GeoIP database.
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
jar cfm JmProxyServer.jar src/META-INF/MANIFEST.MF -C out . -C resources .
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
- Target JDK 17 (`.idea/misc.xml`), 4-space indentation, UTF-8 encoding.
- Use package `com.namejm.proxy`; class names in `PascalCase`, methods/fields in `camelCase`, constants in `UPPER_SNAKE_CASE`.
- Keep logging through SLF4J (`LoggerFactory.getLogger(...)`); avoid `System.out` except startup banner text.
- Keep `ProxyMain` orchestration-only; add reusable logic to dedicated classes under `src/com/namejm/proxy`.

## Testing Guidelines
- No automated test suite exists yet. Add tests under `src/test/java/com/namejm/proxy` when introducing non-trivial logic.
- Recommended focus: `ProxyDto.isValid`, `ConnectionPolicy`, `TargetHealthTracker` threshold transitions, and failover target selection.
- For now, validate changes by running the server with sample entries in `config/application.yml` and checking log output.

## Commit & Pull Request Guidelines
- Recent history uses short, imperative messages (often Korean), e.g., `로직 개선`, `Update ProxyDto.java`.
- Prefer concise subject lines under 72 chars; include scope when useful (example: `proxy: improve private IP filtering`).
- PRs should include: what changed, why, local verification steps, and config/runtime impact (ports, LB options, timeout values, GeoIP DB path).
- Link related issues and attach log snippets when behavior or access control output changes.

## Security & Configuration Tips
- Do not commit real internal IPs or environment-specific access rules in `config/application.yml`.
- Keep `GeoLite2-Country.mmdb` updated and verify file path exists before release.
- Review `allowedCountries` carefully; `any` bypasses source filtering.
- For long-lived connections, keep `clientSoTimeoutMillis` and `forwardSoTimeoutMillis` at `0` unless explicit idle cutoff is needed.
