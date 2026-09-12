# Build stage — Maven image (Java 21).
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
# Tests are skipped here on purpose: they need Docker (Testcontainers) and this IS the Docker build.
# Run `mvn test` before building the image — the deploy runbook says so.
RUN mvn -q -B clean package -DskipTests
RUN java -Djarmode=layertools -jar target/*-1.0.0-SNAPSHOT.jar extract

# Runtime stage — slim JRE, non-root.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./
RUN chown -R appuser:appgroup /app
USER appuser
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8083/actuator/health || exit 1
EXPOSE 8083
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
