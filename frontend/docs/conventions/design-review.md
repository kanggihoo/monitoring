# 디자인 점검 루프

실제 렌더 화면을 보고 컴포넌트·토큰을 고치는 절차. 기록은 [../../design-review/log.md](../../design-review/log.md) 에 라운드로 쌓는다.

## 준비

```bash
cd ../backend && docker compose up -d && ./gradlew bootRun   # DB·Redis·API
pnpm dev
pnpm test:e2e:auth        # 최초 1회 — 열리는 Chrome 에서 로그인하면 세션이 저장된다
```

- 세션은 `e2e/.auth/user.json` (gitignore). 관리자 화면까지 보려면 ADMIN 계정으로 로그인한다.
  DB 에서 권한을 올렸다면 **다시 로그인**해야 세션 권한이 따라온다.
- 배포 서버를 볼 때는 모든 명령 앞에 `BASE_URL=https://...` 를 붙인다.

## 한 라운드

1. `pnpm shots` — `design-review/shots/*.desktop.png` 를 덮어쓴다. (모바일·다크까지 보려면 `pnpm shots --all`)
2. 이미지를 **직접 열어 본다.** 코드만 읽고 판단하지 않는다 — 이 루프의 존재 이유다.
3. 로그에 라운드를 추가하고 발견을 적는다. 화면 이름과 무엇이 어긋났는지를 쓴다.
4. 고친다. 색·타이포·radius 는 `DESIGN.md` 가 진실이므로 거기서 고치고 `pnpm theme` 로 `globals.css` 를 재생성한다 (직접 수정 금지, [ADR-0002](../adr/0002-design-md-token-pipeline.md)).
5. `pnpm shots` 재캡처 → 눈으로 확인 → 로그의 "결과" 를 채운다.
6. `pnpm test` · `pnpm theme:check` 로 마감한다.

## 캡처

- `design-review/shots/` 는 항상 최신만 둔다 (gitignore). 라운드마다 쌓지 않는다 — 무엇이 언제 것인지 못 찾는다.
- 처음 상태는 `OUT=design-review/shots/baseline pnpm shots` 로 한 벌만 남긴다. 비교가 필요한 건 결국 "처음 vs 지금" 하나다.
- **기본 동작**: `pnpm shots` 는 빠른 피드백을 위해 **Desktop + Light** 모드만 캡처한다.
- **플래그 옵션**:
  - `pnpm shots --all`: Desktop + Mobile, Light + Dark 전체 캡처 (최종 점검 시)
  - `pnpm shots --mobile`: Mobile 뷰포트 포함
  - `pnpm shots --dark`: Dark 모드(`*.dark.png`) 포함
- 찍는 화면 목록은 **`scripts/routes.mjs`** 의 `ROUTES` 에 선언한다 (`perf.mjs` 와 공유). 새 화면을 추가했으면 여기에 넣는다.

## 성능

`pnpm perf` — 화면마다 TTFB · DCL · LCP · CLS · 요청 수 · 전송량을 한 표로 낸다.
개발자 도구 네트워크 탭을 화면 수만큼 여는 대신 쓴다. 실패한 요청과 콘솔 오류도 함께 뜬다.

```bash
pnpm build && PORT=3100 pnpm start
BASE_URL=http://localhost:3100 pnpm perf
```

- **프로덕션 빌드에 대고 재야 한다.** `next dev` 는 번들이 최적화돼 있지 않고 라우트를 처음
  열 때 컴파일까지 섞이므로 프로덕션과 무관한 숫자가 나온다 (개발 서버를 감지하면 경고한다).
- 화면마다 브라우저 컨텍스트를 새로 판다 — 전부 **첫 방문** 기준이라 화면끼리 비교할 수 있다.
  하나를 공유하면 폰트·청크가 캐시돼 첫 화면만 무겁게 보인다.
- 찍는 화면은 `scripts/routes.mjs` 로 `pnpm shots` 와 공유한다.
