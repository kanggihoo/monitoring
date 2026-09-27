# monitoring — 로컬 관측성 스택 학습

기존 서비스 코드(`backend/` Spring Boot, `ai/` FastAPI, `frontend/` Next.js) 위에
**Grafana · Loki · Tempo · Prometheus · Alloy** 관측 스택을 docker compose로 직접 구성한 학습용 리포다.

```bash
docker compose --profile app --profile monitoring up -d --build   # Grafana: http://127.0.0.1:3001
```

| 문서 | 내용 |
|---|---|
| [docs/HANDOFF.md](docs/HANDOFF.md) | 현황, 구현과 검증 범위, 다음 할 일 |
| [infra/README.md](infra/README.md) | 구조 그림과 설정 파일 읽는 순서 |
| [load/README.md](load/README.md) | k6 부하 시나리오 |
| [CONTEXT.md](CONTEXT.md) · [docs/adr/](docs/adr/) | 용어와 결정 기록 |
