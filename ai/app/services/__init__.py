"""서비스 레이어 — 비즈니스 로직.

라우터가 HTTP 를 벗겨서 넘긴 값을 받아 실제 일을 한다. 프롬프트 조립, 모델 호출 오케스트레이션,
후처리가 여기 산다. `status`·`Header` 같은 HTTP 관심사를 여기로 들이지 않는다.

모듈 구성은 `docs/architecture/overview.md` 를 따른다.
"""
