FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY src src
COPY ui ui
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 catalyst
COPY --from=build /workspace/build/libs/catalyst-radar-0.0.1-SNAPSHOT.jar app.jar
USER catalyst
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
