# Spring 계측은 OTel Java agent, exemplar 는 metrics-generator 단계로 미룬다

Spring Boot 4 에는 공식 `spring-boot-starter-opentelemetry`(Micrometer Tracing + OTel)가 있고, 이걸 쓰면 `/actuator/prometheus` 에 exemplar 가 바로 붙는다. 그래도 OTel Java agent 를 택했다 — 비즈니스 코드·의존성을 건드리지 않고 JDBC·Redis·executor 컨텍스트 전파까지 자동 계측되기 때문이다. agent 는 Micrometer 에 Tracer 를 주지 않으므로 Micrometer 메트릭에는 exemplar 가 없고, 메트릭→트레이스 연결은 Tempo metrics-generator 를 켜는 다음 단계에서 span 기반 RED 메트릭의 exemplar 로 얻는다.

## Considered Options

- **spring-boot-starter-opentelemetry**: exemplar 즉시 가능. 대신 JDBC·Redis span 을 위해 datasource-micrometer 등 추가 의존성과 코드 변경이 든다.
- **agent + micrometer-tracing-bridge-otel**: 트레이서가 둘이 되어 충돌한다. 기각.
- **agent + exemplar 포기**: metrics-generator 로 나중에 얻을 수 있으니 포기할 이유가 없다.

## Consequences

- 1단계 Grafana 연결은 로그↔트레이스만 동작한다. 메트릭 패널에서 트레이스로는 시간·서비스로 Tempo 검색해서 간다.
- agent 가 Spring Boot 4.1 / Spring 7 의 MVC span 을 못 잡으면 이 결정을 다시 연다(starter 로 전환).
