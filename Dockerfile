# syntax=docker/dockerfile:1.7
# jar 预构建模式：本地/CI 先执行 mvn -DskipTests package，再 docker build。
# 镜像内不再跑 Maven，服务器更新时构建只需数秒。
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk upgrade --no-cache && \
    addgroup -S appgroup && \
    adduser -S -G appgroup appuser && \
    mkdir -p /app/data /app/logs && \
    chown -R appuser:appgroup /app && \
    chmod -R 750 /app

USER appuser

COPY --chown=appuser:appgroup target/enterprise-agent-platform-*.jar app.jar

# Read-only root filesystem support
VOLUME ["/app/data", "/app/logs"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health >/dev/null || exit 1

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/heapdump.hprof -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT exec java $JAVA_OPTS -jar app.jar
