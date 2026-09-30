# ---- Build stage ----
# This repo has no Maven wrapper (no mvnw/.mvn), so the build runs from a
# maven image rather than ./mvnw. Java 21 matches <java.version> in pom.xml.
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Resolve dependencies from pom.xml alone first: pom changes far less often
# than src/, so this layer survives most rebuilds and keeps them offline-ish.
COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests run in CI, not in the image build - a failing test should not be
# indistinguishable from a broken Dockerfile.
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine AS production
WORKDIR /app

# Run unprivileged; nothing here needs root.
RUN addgroup -S app && adduser -S -G app app

# pom.xml sets <finalName>backend</finalName>, hence target/backend.jar.
COPY --from=builder /app/target/backend.jar ./app.jar
USER app

# Matches ${PORT:3001} in application.yml.
EXPOSE 3001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
