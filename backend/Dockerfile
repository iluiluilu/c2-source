# ---- Build stage ----
# This repo has no Maven wrapper (no mvnw/.mvn), so the build runs from a
# maven image rather than ./mvnw. Java 21 matches <java.version> in pom.xml.
# --platform=$BUILDPLATFORM: the jar is platform-independent, so Maven runs natively on the build machine even when
# the image targets another arch (stg: amd64 built on an arm64 Mac). Under amd64 emulation javac crashed the JVM
# (SIGSEGV in G1, exit 134); only the runtime stage below needs to match the target platform.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS builder
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

# export-reporting (JasperReports): AWT needs fontconfig + freetype (+ a base font set) even when headless,
# for rendering chart images and loading the bundled TTFs. Without these the first report export fails
# with "Fontconfig head is null" / UnsatisfiedLinkError on libfreetype.
RUN apk add --no-cache fontconfig freetype ttf-dejavu

# Run unprivileged; nothing here needs root.
RUN addgroup -S app && adduser -S -G app app

# pom.xml sets <finalName>backend</finalName>, hence target/backend.jar.
COPY --from=builder /app/target/backend.jar ./app.jar
# backend-multi-instance Task 9: shared chunk-upload volume (upload-tmp) mount point. Created and owned by `app`
# BEFORE `USER app` so a fresh named volume inherits the ownership on first mount.
RUN mkdir -p /data/upload-tmp && chown app:app /data/upload-tmp
USER app

# Matches ${PORT:3001} in application.yml. Management/health port (3002) is NOT exposed to the host.
EXPOSE 3001
# backend-multi-instance Task 16: readiness (readinessState + db); start-period covers Flyway on a cold start.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- http://localhost:3002/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
