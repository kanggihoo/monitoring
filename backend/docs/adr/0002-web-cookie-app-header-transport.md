# ADR-0002: 세션 ID 웹 쿠키 / 앱 헤더 운반

- 상태: 승인
- 결정일: 2026-08-26
- 관련: [ADR-0001](0001-session-based-authentication.md), [ADR-0004](0004-stomp-realtime-transport.md)

## 문맥
- 웹: 브라우저 쿠키 자연스러움.
- 앱(Expo): 브라우저 OAuth 통과 후 시스템 브라우저 쿠키 격리됨. `fetch` 스택에 쿠키 안 넘어옴. 실기기 LAN IP 개발 시 `Secure` 쿠키 누락.

## 결정
1. **운반 분리**: 웹 쿠키(`HttpOnly`, `Secure`, `SameSite=Lax`), 앱 `X-Auth-Token` 헤더. 백엔드 Redis 세션은 동일.
2. **리졸버**: 커스텀 `HttpSessionIdResolver` 직접 구현.
3. **`CompositeHttpSessionIdResolver` 금지**: 응답마다 헤더에 세션 ID 복제 노출. XSS 취약점 발생.
4. **CSRF**: 쿠키 요청만 적용. `X-Auth-Token` 요청은 제외 (브라우저 자동 전송 불가).

## 대안 비교
- **앱 쿠키 강제**: 기각. 딥링크로 쿠키 재주입 필요. 복잡도 급증 및 실기기 오작동.
- **앱 전용 JWT 발급**: 기각. 인증 체계 이원화. 즉시 무효화 깨짐.

## 핵심 근거
- 세션 ID(자격증명)와 헤더/쿠키(운반체)는 별개 축. 헤더로 날라도 Redis 세션 이점 유지.
- 커스텀 헤더는 CSRF 성립 불가(자동 첨부 안 됨). CSRF 해제가 안전.

## 영향
- 커스텀 `HttpSessionIdResolver` 유지보수 필요.
- **WebSocket 핸드셰이크에도 이 운반 이원화가 그대로 적용된다.** 핸드셰이크는 HTTP GET이라 같은 리졸버를 타므로 WS 인증에 새 코드가 없다 ([ADR-0004](0004-stomp-realtime-transport.md)).
- 1 유저 n 세션 독립 동작 (웹/앱 기기별 독립 세션).
- 로그인 중도 이탈 시 고아 세션 발생. 유휴 만료(1일)로 정리.
