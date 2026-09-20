# 容器化部署（全栈：app + MySQL + Qdrant + MinIO）

1. 先配环境：`cp .env.example .env`，填 `SLZ_DATASOURCE_*`、`SLZ_JWT_SECRET_KEY`、`SLZ_ATTACH_TOKEN_KEY`、`SLZ_ADMIN_PASSWORD`、`MINIO_ACCESS_KEY/SECRET_KEY`、`DASHSCOPE_API_KEY`（prod profile 有 fail-fast 校验，留空起不来；`QDRANT_API_KEY` 可选，设置后应用与 Qdrant 两侧自动同步）。
2. 在项目根构建并启动：`docker compose -f deploy/docker-compose.yml --env-file .env up -d --build`。
3. 初始化全自动：库结构由应用启动时 Flyway 迁移（`SLZ_FLYWAY_ENABLED=true`，auto-table 在 prod 强制 none），MinIO 桶由一次性 `minio-setup` 服务创建，无需手工建表建桶。
4. 访问：后端 `http://localhost:8080`，MinIO 控制台 `http://localhost:9001`，Qdrant REST `http://localhost:6333`。
5. 健康检查：`curl http://localhost:8080/actuator/health/liveness`（免鉴权探针；readiness 含 db/vectorStore/minio，需登录才能看明细）。
6. 宿主机端口冲突时设环境变量改映射（不影响容器内互访）：`APP_PORT / MYSQL_PUBLISH_PORT / QDRANT_HTTP_PORT / QDRANT_GRPC_PORT / MINIO_API_PORT / MINIO_CONSOLE_PORT / PROMETHEUS_PORT`。
7. 看日志 / 停止：`docker compose -f deploy/docker-compose.yml logs -f app` / `down`（加 `-v` 会删数据卷，慎用）。

## 监控栈起法与验证（线程池拒绝告警链路）

四份产物都在 `deploy/prometheus/`，各自有钉住它的门禁——**指标名一律以实采清单为准，不许手抄**：

| 文件 | 作用 | 谁把它钉住 |
| --- | --- | --- |
| `expected-metric-names.txt` | `/actuator/prometheus` 实际导出名 + label 清单（真相源） | 由 `PrometheusExportTruthCaptureTest` 真起上下文采集 |
| `alert-rules.yml` | 线程池拒绝分级告警 + 指标缺失告警 | `MonitoringArtifactConsistencyTest`（名字/label/absent 条款）+ CI `promtool check rules` |
| `grafana-thread-pool-dashboard.json` | 面板查询（含 `executor_*` 族的真实族名与 `name` label） | `MonitoringArtifactConsistencyTest` |
| `prometheus.yml` | 15s 抓 `app:8080/actuator/prometheus` + 挂上告警规则 | `MonitoringArtifactConsistencyTest` + `promtool check config` |

1. 起栈（prometheus 已作为 compose 服务，随 app 一起起）：
   `docker compose -f deploy/docker-compose.yml --env-file .env up -d --build`
2. 验证告警规则已被加载并评估（rules 状态）：
   `curl -s http://127.0.0.1:9090/api/v1/rules | python -c "import json,sys;[print(g['name'],r['name'],r['health'],r['state']) for g in json.load(sys.stdin)['data']['groups'] for r in g['rules']]"`
3. 验证抓取目标状态（target up）：
   `curl -s http://127.0.0.1:9090/api/v1/targets | python -c "import json,sys;[print(t['labels'].get('job'),t['scrapeUrl'],t['health'],t.get('lastError','')) for t in json.load(sys.stdin)['data']['activeTargets']]"`
4. 离线校验两份 YAML（改完必跑，CI 里同源）：
   `docker run --rm -v "$PWD/deploy/prometheus:/t:ro" --entrypoint promtool prom/prometheus:v3.4.1 check rules /t/alert-rules.yml`
   `docker run --rm -v "$PWD/deploy/prometheus:/t:ro" --entrypoint promtool prom/prometheus:v3.4.1 check config /t/prometheus.yml`

**当前已知状态（不是 bug，是没拍板的口径）**：第 3 步里 `crm-app` 这个 target 会是 `down`，
`lastError` 是 `server returned HTTP status 401`——`/actuator/prometheus` 被
`ActuatorProtectionFilter` 列在 admin-only 名单里（要有效 JWT 且 roleId=1），
`PrometheusExportTruthCaptureTest#anonymousScrapeIsRejectedWhileProtectionIsOn` 把这条钉成了断言。
指标端点怎么暴露给监控系统是安全决策，三个候选（放开白名单 / 独立 management 端口 / 抓取侧带超管
token）与各自代价见 `deploy/prometheus/prometheus.yml` 里 `crm-app` 任务段的注释。在放开之前，
`alert-rules.yml` 的 `ThreadPoolMetricsMissing` 会显式报警，而不是让监控链路静默假绿。

改指标名的规矩（顺序不能反）：先 `mvn -B -ntp test -Dtest=PrometheusExportTruthCaptureTest`
重采 → 同步 `expected-metric-names.txt` → 再改 `alert-rules.yml` / 面板 JSON →
`mvn -B -ntp test -Dtest=MonitoringArtifactConsistencyTest` 绿 → 第 4 步两条 promtool 绿。

Grafana 默认不起（吃内存）：compose 里整段注释留档，需要看图时取消注释，
面板 JSON 用 Grafana 的 `POST /api/dashboards/db` 导入即可（名字已对上）。
