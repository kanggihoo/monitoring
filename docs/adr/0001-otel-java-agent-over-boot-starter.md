# Spring 계측은 OTel Java agent, exemplar 는 metrics-generator 단계로 미룬다

Spring Boot 4 에는 공식 `spring-boot-starter-opentelemetry`(Micrometer Tracing + OTel)가 있고, 이걸 쓰면 `/actuator/prometheus` 에 exemplar 가 바로 붙는다. 그래도 OTel Java agent 를 택했다 — 비즈니스 코드·의존성을 건드리지 않고 JDBC·Redis·executor 컨텍스트 전파까지 자동 계측되기 때문이다. agent 는 Micrometer 에 Tracer 를 주지 않으므로 Micrometer 메트릭에는 exemplar 가 없고, 메트릭→트레이스 연결은 Tempo metrics-generator 를 켜는 다음 단계에서 span 기반 RED 메트릭의 exemplar 로 얻는다.

## Considered Options

- **spring-boot-starter-opentelemetry**: exemplar 즉시 가능. 대신 JDBC·Redis span 을 위해 datasource-micrometer 등 추가 의존성과 코드 변경이 든다.
- **agent + micrometer-tracing-bridge-otel**: 트레이서가 둘이 되어 충돌한다. 기각.
- **agent + exemplar 포기**: metrics-generator 로 나중에 얻을 수 있으니 포기할 이유가 없다.

## Consequences

- 1단계 Grafana 연결은 로그↔트레이스만 동작한다. 메트릭 패널에서 트레이스로는 시간·서비스로 Tempo 검색해서 간다.
- agent 가 Spring Boot 4.1 / Spring 7 의 MVC span 을 못 잡으면 이 결정을 다시 연다(starter 로 전환).
- MVC span 은 잡힌다 (3단계에서 확인, Tomcat·JPA·JDBC·Spring Data 계측 정상). 전환하지 않는다.
- **Lettuce(Redis) span 이 나오지 않았다.** agent 의 lettuce 계측은 적용되지만 Spring Boot 의
  `LettuceObservationAutoConfiguration` 이 Lettuce tracing 자리를 Micrometer Observation 으로 덮어쓴다
  (Micrometer Tracing 이 없으니 span 이 되지 못하고 사라진다). 이 자동설정을 compose 에서
  `SPRING_AUTOCONFIGURE_EXCLUDE` 로 뺐다 — Redis span 을 얻고 `lettuce_*` Micrometer 메트릭을 잃는다.
  Redis 메트릭은 redis_exporter(서버 관점)가 대신하고, 클라이언트 관점 지연은 metrics-generator 단계에서
  span 으로부터 다시 얻는다.
