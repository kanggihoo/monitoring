# load — k6 부하 시나리오

로컬 스택(루트 `compose.yml`)의 backend에 부하를 주는 k6 시나리오다. 결과는 터미널 요약과 Prometheus 양쪽에 남는다.

| 파일 | 목적 |
|---|---|
| `smoke.js` | **스모크.** VU 5개가 1분 동안 돈다. 각 VU는 로그인하고 시장가 매수 1주를 넣은 뒤, 조회 API 5개를 반복한다. 성능 측정용이 아니라 로그·트레이스·메트릭이 끝까지 이어지는지 확인하는 용도다 |

## 실행

```bash
# 스택과 틱이 떠 있어야 한다
docker compose --profile app --profile monitoring up -d --build
./scripts/replay-speed.sh 10            # 시즌이 끝났으면: ./scripts/replay-speed.sh 10 1000001

docker compose --profile load run --rm k6                              # testid=smoke
docker compose --profile load run --rm -e TESTID=smoke-2 k6            # 실행마다 이름 붙이기
docker compose --profile load run --rm -e VUS=20 -e DURATION=3m k6     # 부하 조절
```

threshold(실패율 1% 미만, p95 1초 미만, check 99% 이상)를 넘으면 k6가 실패 코드로 끝난다.

## 결과 보기

| 어디서 | 무엇을 |
|---|---|
| Grafana → community → **k6 Prometheus** | `testid`를 골라 실행 하나를 본다. 요청 수, 응답시간 p95·p99, 실패율, VU 수 |
| Grafana → app → **앱 개요** | 같은 시간대의 서버 쪽 모습. backend 요청 수와 p95, 컨테이너 CPU, Hikari, Tomcat 스레드 |
| Grafana → Explore → Tempo | `{span.http.route="/api/v1/orders"}`로 k6가 넣은 주문 한 건의 트레이스를 본다 |

k6 쪽 숫자(클라이언트 관점)와 backend 쪽 숫자(서버 관점)를 나란히 두고 보는 것이 이 구성의 핵심이다. 둘의 차이는 네트워크 구간과 대기열에서 생긴다.

## 설계 메모

- **k6만 Prometheus에 직접 쓴다** (`-o experimental-prometheus-rw`). k6는 긁어 갈 대상이 아니라 결과를 밀어 넣는 쪽이라서, 수집 파이프라인(Alloy)을 거치지 않는다. 설정은 `../compose.yml`의 `k6` 서비스에 있다.
- **test user 이름은 `k6-0001`~`k6-1000`(네 자리)이다.** backend 시드가 만든다. `k6-0001`은 `scripts/replay-speed.sh`가 ADMIN으로 쓰므로, 시나리오는 11번부터 쓴다.
- **세션은 `X-Auth-Token` 헤더로 보낸다.** Secure 쿠키는 http에서 전송되지 않는다. 헤더를 쓰면 CSRF 검사도 빠진다.
- 예전 부하 검증 스크립트(인덱스 전후 비교 등)는 `../backend/scripts/k6/`에 그대로 있다. 원격 테스트 서버용이다.
