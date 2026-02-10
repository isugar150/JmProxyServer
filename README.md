# JmProxyServer

## 특징
JmProxyServer는 프록시 프로그램이며, 쉬운 설정, 국가 및 내부망 기반으로 접속을 차단합니다.  
주의: 국가별로 차단시 프록시 서버가 Super DMZ에 위치해야합니다.

- `out`은 일반 SOCKS/HTTP CONNECT 프록시가 아니라, 고정 대상(`forwardHost:forwardPort` 또는 `lb`)으로 전달하는 모드입니다.
- `lb` 설정 시 라운드로빈 + 헬스체크 + 자동 페일오버가 동작합니다.

## Preview
![image](https://user-images.githubusercontent.com/13088077/127344946-e0eb0144-2ef5-4c58-bcb7-290e19d95fa2.png)  
[구동 화면]  

## 실행
```bash
javac -encoding UTF-8 -cp "lib/*" -d out src/com/namejm/proxy/*.java
jar cfm JmProxyServer.jar src/META-INF/MANIFEST.MF -C out . -C resources .
java -jar JmProxyServer.jar
```

## 설정 방법
```yaml
proxy:
  - type: in # in: 인바운드, out: 고정대상 전달형 아웃바운드
    name: example-single # 프록시 이름
    bindPort: 8080 # 클라이언트가 접속할 로컬 바인드 포트
    forwardHost: 10.1.3.200 # 단건 전달 대상 호스트(lb 미사용 시)
    forwardPort: 80 # 단건 전달 대상 포트(lb 미사용 시)
    allowedCountries: [KR, US, private, localhost] # 허용 원본: 국가코드/private/localhost/Any
    transferTimeoutSeconds: 0 # 양방향 전송 최대시간(초), 0이면 무제한
    clientSoTimeoutMillis: 0 # 클라이언트 소켓 read 타임아웃(ms), 0이면 무제한
    forwardSoTimeoutMillis: 0 # 타깃 소켓 read 타임아웃(ms), 0이면 무제한

  - type: in
    name: example-lb # 로드밸런싱 프록시 이름
    bindPort: 8081 # 클라이언트 접속 포트
    lbHealthCheckIntervalSeconds: 5 # 헬스체크 주기(초)
    healthCheckInitialDelaySeconds: 1 # 서버 시작 후 첫 헬스체크 지연(초)
    healthCheckConnectTimeoutMillis: 2000 # 헬스체크 연결 타임아웃(ms)
    healthFailThreshold: 3 # 연속 실패 N회 시 UNHEALTHY 전환
    healthSuccessThreshold: 2 # 연속 성공 N회 시 HEALTHY 전환
    forwardConnectTimeoutMillis: 5000 # 타깃 연결 타임아웃(ms)
    transferTimeoutSeconds: 0 # 전송 최대시간(초), 0이면 무제한
    lb: # 라운드로빈 대상 목록
      - name: lb1 # 타깃 식별자(로그용)
        forwardHost: 10.1.3.201 # 타깃1 호스트
        forwardPort: 80 # 타깃1 포트
      - name: lb2
        forwardHost: 10.1.3.202 # 타깃2 호스트
        forwardPort: 80 # 타깃2 포트
    allowedCountries: [Any] # Any면 모든 원본 허용

  - type: out
    name: example-out # out 모드 프록시 이름
    bindPort: 8082 # 로컬 바인드 포트
    forwardHost: api.example.com # 고정 전달 대상
    forwardPort: 443 # 고정 전달 대상 포트
    # out은 allowedCountries 미설정 시 전체 허용 (설정 시 in과 동일 규칙 적용)
```

## 모듈 구조
- `ProxyServer`: 설정 로딩/시작
- `ProxyMain`: 연결 수락/전송 오케스트레이션
- `ForwardTargetSelector`: 라운드로빈 후보 선택
- `TargetHealthTracker`: 헬스 상태 전이(임계치 기반)
- `ConnectionPolicy`: 국가/내부망/루프백 허용 정책

## 설정 키 설명
| 키 | 설명 | 기본값 |
|---|---|---|
| `type` | 프록시 타입 (`in`/`out`) | 없음(필수) |
| `name` | 프록시 식별 이름 | 없음(필수) |
| `bindPort` | 로컬 바인드 포트 | 없음(필수) |
| `forwardHost` | 단건 전달 대상 호스트 (`lb` 미사용 시) | 없음(`lb` 없을 때 필수) |
| `forwardPort` | 단건 전달 대상 포트 (`lb` 미사용 시) | 없음(`lb` 없을 때 필수) |
| `lb` | 로드밸런싱 대상 배열 (`name`, `forwardHost`, `forwardPort`) | 미사용 |
| `allowedCountries` | 허용 원본 (`Any`, 국가코드, `private`, `localhost`) | `in`: 빈값이면 전부 차단, `out`: 빈값이면 전부 허용 |
| `lbHealthCheckIntervalSeconds` | LB 헬스체크 주기(초) | `10` |
| `healthCheckInitialDelaySeconds` | 시작 후 첫 헬스체크 지연(초) | `1` |
| `healthCheckConnectTimeoutMillis` | 헬스체크 연결 타임아웃(ms) | `2000` |
| `healthFailThreshold` | 연속 실패 N회 시 `UNHEALTHY` 전환 | `3` |
| `healthSuccessThreshold` | 연속 성공 N회 시 `HEALTHY` 전환 | `2` |
| `forwardConnectTimeoutMillis` | 타깃 서버 연결 타임아웃(ms) | `5000` |
| `clientSoTimeoutMillis` | 클라이언트 소켓 read 타임아웃(ms), `0`은 무제한 | `0` |
| `forwardSoTimeoutMillis` | 타깃 소켓 read 타임아웃(ms), `0`은 무제한 | `0` |
| `transferTimeoutSeconds` | 전체 전송 최대시간(초), `0`은 무제한 | `0` |
| `executorCorePoolSize` | 워커 코어 스레드 수 | `CPU 코어 수` |
| `executorMaxPoolSize` | 워커 최대 스레드 수 | `core*2` |
| `executorKeepAliveSeconds` | 워커 유휴 스레드 유지시간(초) | `60` |
| `executorQueueCapacity` | 워커 대기 큐 크기 | `500` |
| `shutdownAwaitSeconds` | 종료 시 워커 종료 대기시간(초) | `10` |
