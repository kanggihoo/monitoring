# 실행 방법

> 로컬 기동/종료. `backend/` 에서 실행.

## 1. 환경 변수

```bash
cp .env.example .env
```

- DB, Redis 기본값 동작.
- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` 필수 입력. 빈 값 시 기동 실패.
- Google Console "승인된 리디렉션 URI"와 `GOOGLE_REDIRECT_URI` 일치 필수.

## 2. 로컬 개발 (기본)

```bash
docker compose up -d      # postgres, redis
./gradlew bootRun         # Spring 호스트 기동
```

## 3. 전체 컨테이너 (배포 이미지 검증)

```bash
docker compose --profile app up -d --build
docker compose --profile app down
```

## 4. 최초 관리자 승격

대상자 1회 로그인 선행 필요 (`users` 행 생성). DB 수정 후 재로그인 필요.
환경당 1회. `docker compose down` 은 볼륨을 유지하므로 다시 할 필요 없다 (`down -v` 는 필요).

```bash
sh scripts/promote-admin.sh            # 최근 가입자 목록에서 대상의 식별자를 확인
sh scripts/promote-admin.sh <식별자>   # 그 한 건만 승격
```

이메일이 아니라 식별자로 지정한다. 이메일에는 유일성이 없어 같은 값을 가진 User 가 둘이면
둘 다 바뀌고, 이메일이 없는 사용자는 지정할 수단 자체가 없다.
목록에는 테스트용 사용자가 나오지 않는다.

## 5. 시세 적재 (마켓 리플레이)

앱이 한 번 기동해 V18 이 시즌 1 을 편성한 **뒤에** 실행한다. 환경당 1회이고 멱등이라 다시 돌려도
무해하다. 리포 루트에서:

```bash
# 편의 스크립트 (어디서나 실행 가능)
./backend/scripts/load-replay.sh verify       # DB 없이 검증만
./backend/scripts/load-replay.sh test         # 로컬 / dev (mode=test)
./backend/scripts/load-replay.sh prod         # 운영 (mode=production)
./backend/scripts/load-replay.sh rollback     # 데이터 롤백

# Windows PowerShell:
# .\backend\scripts\load-replay.ps1 [verify|test|prod|rollback]

# 직접 uv 호출 시:
LOADER="backend/scripts/replay-loader"
uv run --directory $LOADER load_replay_data.py --verify
uv run --directory $LOADER load_replay_data.py --mode test
uv run --directory $LOADER load_replay_data.py --mode production
```

접속 정보는 `backend/scripts/replay-loader/.env` 를 읽는다 (`.env.example` 복사). Spring 의
`backend/.env` 와 별개 파일이다 — 앱 설정과 적재 대상은 다른 것이다.

원격 DB 에 넣을 때는 환경변수가 파일을 이긴다. 다른 터미널에서 터널을 열고 다섯 개를 지정한다:

```bash
ssh -N -L 55432:localhost:5432 <사용자>@<배포 서버>
```

적재 직전에 `적재 대상 <user>@<host>:<port>/<db>` 가 찍히므로 실행 전에 그 줄을 확인한다.
다섯 개 중 하나라도 빠지면 로더 `.env` 의 로컬 값이 그 자리를 메꾼다.

## 6. 테스트

```bash
./gradlew test               # Docker 불필요
./gradlew integrationTest    # Docker 필요 (Testcontainers)
```

## 7. 종료

```bash
docker compose down       # 컨테이너만. 데이터 유지
docker compose down -v    # 볼륨까지 삭제
```
