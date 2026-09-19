# 容器化部署（全栈：app + MySQL + Qdrant + MinIO）

1. 先配环境：`cp .env.example .env`，填 `SLZ_DATASOURCE_*`、`SLZ_JWT_SECRET_KEY`、`SLZ_ATTACH_TOKEN_KEY`、`SLZ_ADMIN_PASSWORD`、`MINIO_ACCESS_KEY/SECRET_KEY`、`DASHSCOPE_API_KEY`（prod profile 有 fail-fast 校验，留空起不来；`QDRANT_API_KEY` 可选，设置后应用与 Qdrant 两侧自动同步）。
2. 在项目根构建并启动：`docker compose -f deploy/docker-compose.yml --env-file .env up -d --build`。
3. 初始化全自动：库结构由应用启动时 Flyway 迁移（`SLZ_FLYWAY_ENABLED=true`，auto-table 在 prod 强制 none），MinIO 桶由一次性 `minio-setup` 服务创建，无需手工建表建桶。
4. 访问：后端 `http://localhost:8080`，MinIO 控制台 `http://localhost:9001`，Qdrant REST `http://localhost:6333`。
5. 健康检查：`curl http://localhost:8080/actuator/health/liveness`（免鉴权探针；readiness 含 db/vectorStore/minio，需登录才能看明细）。
6. 宿主机端口冲突时设环境变量改映射（不影响容器内互访）：`APP_PORT / MYSQL_PUBLISH_PORT / QDRANT_HTTP_PORT / QDRANT_GRPC_PORT / MINIO_API_PORT / MINIO_CONSOLE_PORT`。
7. 看日志 / 停止：`docker compose -f deploy/docker-compose.yml logs -f app` / `down`（加 `-v` 会删数据卷，慎用）。
