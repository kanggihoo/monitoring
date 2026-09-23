# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# 1단계: 빌드
#
# JDK 는 여기서만 필요하다. gradle 공식 이미지(349MB) 대신 temurin JDK 를 쓰고
# 리포에 이미 있는 gradlew 래퍼로 Gradle 9.5.1 을 받는다.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
COPY src ./src

# --mount=type=cache 로 Gradle 홈(의존성 캐시 + 래퍼가 받은 배포판)을 빌드 간에
# 재사용한다. 이게 없으면 소스 한 줄만 고쳐도 매번 전부 다시 받는다.
# 테스트는 여기서 돌리지 않는다 — 컨테이너 테스트는 Testcontainers 가 담당한다.
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

# ---------------------------------------------------------------------------
# 2단계: 런타임
#
# JRE 만 남긴다. 빌드 도구·소스·Gradle 캐시는 최종 이미지에 포함되지 않는다.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# root 로 돌리지 않는다.
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

COPY --from=build --chown=spring:spring /build/build/libs/*.jar app.jar

EXPOSE 8080

# 컨테이너 메모리 한도를 힙 크기에 반영시킨다 (기본 활성이지만 의도를 명시).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
