# ADR-0002: globals.css 를 DESIGN.md 에서 생성한다

- 상태: 승인
- 결정일: 2026-08-31
- 관련: [S15P21A605-27](https://ssafy.atlassian.net/browse/S15P21A605-27),

## 문맥

- `globals.css`는 shadcn 기본값 유지. 시안-코드 색 괴리 발생.
- 수동 색 이전 시 대비 검증 누락. 시안 상승 뱃지(`#D0333F` on `#FBEDEE`) 4.36:1로 WCAG AA 미달 발견.
- `@google/design.md@0.4.0` 도입: 단일 파일 토큰/근거 관리, lint(구조/참조/대비) 자동 검사, Tailwind v4 export.

## 결정

1. **`frontend/DESIGN.md` 단일 진실 원천**: 색, 타이포, radius 값/근거 단일 관리.
2. **`src/app/globals.css` 생성물 규정**: `pnpm theme` 자동 생성. 수동 수정 금지 (덮어씌워짐).
3. **토큰 3층 구조**:
   - 1층 primitive (raw hex, 시안 어휘): CSS 미추출 (코드 사용 차단).
   - 2층 semantic (shadcn 계약명 참조): CSS 커스텀 프로퍼티 변환.
   - 3층 `components` (참조): 대비 검사 전용.
4. **라이트/다크 단일 파일 관리**: 다크 토큰은 `-dark` 접미사로 `colors` 맵 정의. 빌드 스크립트가 `.dark` 블록 변환.
5. **shadcn 정규형 출력**: `:root`/`.dark` bare 변수 + `@theme inline` 간접 참조 + `--radius`.
6. **Lint 경고 실패 처리**: 경고 발생 시 빌드 실패 (exit 0 차단, CSS 미생성).
7. **CLI 버전 고정**: `@google/design.md@0.4.0` 고정.

## 검토 대안

### `@theme` 기본 export 사용

- 장점: 후처리 스크립트 간소화.
- 단점: `:root`/`.dark` bare 변수 누락. `npx shadcn add` 및 `var(--chart-1)` 직접 참조 컴포넌트 호환 실패.
- 기각: shadcn 추가 시 수기 보정 발생.

### 라이트/다크 2파일 분리

- 장점: 파일별 독립 lint, 단순 스크립트.
- 단점: 라이트/다크 짝 누락 미감지. 다크 대비 검사 위해 lint 중복 실행 필요.
- 기각: 단일 파일 시 1회 lint로 다크 대비 및 짝 누락 검증 가능.

### 1층 primitive CSS 추출

- 장점: `bg-ink` 등 임의 사용 가능.
- 단점: 1층 `.dark` 오버라이드 부재로 다크 모드 깨짐 발생.
- 기각: 다크 미적용 원천 차단 위해 CSS 노출 금지.

### `globals.css` 직접 관리

- 장점: 도구/스크립트/훅 불필요.
- 단점: 대비 검증 수동 의존, 값 근거 기록 불가.
- 기각: 4.36:1 미달 등 인간 실수 방지 불가.

## 이유

- **실물 검증**: `contrast-ratio` 린트는 3층 `components` bg/text 쌍 기반 동작. 커밋마다 라이트/다크 대비 자동 검증.
- **shadcn 호환**: 2층 semantic이 shadcn 표준 따름. `button.tsx`(`bg-primary` 등) 코드 수정 없이 팔레트 반영.
- **단일 원천 확보**: 수동 수정 제한 대가로 시안-코드 괴리 구조적 차단.

## 결과

- `src/app/globals.css` 직접 수정 금지. `npx shadcn add` 패치 발생 시 `DESIGN.md`로 수동 이관 필수.
- pre-commit 훅(`pnpm theme:check`)이 `DESIGN.md`/`globals.css`/`build-theme.mjs` 변경 시 동기화 검증.
- 새 색상 추가 시 `components` 항목 필수 등록 (미등록 시 `orphaned-tokens` 경고로 빌드 차단).
- `dials` 및 확장 섹션은 CSS 미포함 (Overview 프로즈로 기술).
- CLI 버전 갱신 시 `globals.css` diff 확인 필수.

## 후속 결정

- **CI drift 검사**: CI 파이프라인 구축 시 `pnpm theme:check` 추가.
- **소스코드 하드코딩 검사**: 컴포넌트 내 hex 하드코딩 탐색 정적 분석 추가.
- **`## Motion` 토큰**: 화면 연출 확정 후 `intent` 토큰 추가.
- **그림자 토큰**: 스키마 지원 전까지 `## Elevation & Depth` 프로즈 관리 (별도 추출 경로 검토).
