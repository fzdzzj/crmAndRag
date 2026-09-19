# ================================================================
# crm-platform 生产镜像（多阶段构建）
# 产物 jar 名与 pom 实际构建一致：target/crm-platform-0.0.1-SNAPSHOT.jar
#   （artifactId=crm-platform, version=0.0.1-SNAPSHOT, spring-boot repackage）
# 端口：仓库内未配置 server.port，采用 Spring Boot 默认 8080
#   （frontend/openapi.yaml 与 vite proxy 均以 http://localhost:8080 为后端地址）
# 探针：/actuator/health/liveness 在 platform.actuator.public-paths 免鉴权白名单内
# ================================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom，利用层缓存拉取依赖（离线构建可预置 ~/.m2 或配镜像仓库）
COPY pom.xml ./
RUN mvn -B -ntp -q dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package \
    && cp target/crm-platform-0.0.1-SNAPSHOT.jar /build/app.jar

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre AS runtime
LABEL org.opencontainers.image.title="crm-platform" \
      org.opencontainers.image.description="CRM + RAG fusion platform (Spring Boot)"

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    SERVER_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# 基础镜像 eclipse-temurin:21-jre 已自带 curl / tzdata，无需再 apt 安装（离线可构建）
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && groupadd --system appgroup --gid 1001 \
    && useradd --system --uid 1001 --gid 1001 --home-dir /app --no-create-home appuser

WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar
# 附件本地回退目录（SLZ_FILE_PATH）与日志目录，需对非 root 用户可写
RUN mkdir -p /app/file /app/logs && chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health/liveness" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${SERVER_PORT} -jar /app/app.jar"]
