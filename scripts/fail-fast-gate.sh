#!/usr/bin/env bash
# Fail-Fast 启动门禁（TASK-10 AC1/AC4）
#
# 断言三件事：关键依赖不可达时 ① 应用一定起不来（退出码非 0，不挂死）；
# ② 失败原因指名到具体 actuator 组件（db / vectorStore / minio）与中文原因；
# ③ 探测在自己的预算内收口——启动不会被 Hikari 30s 连接超时、MinIO 无超时这类
#    底层默认值拖成"半死不活地起来了"。
#
# 与 scripts/check-test-baseline.sh 同构：CI 的 fail-fast-gate job 与本地跑同一条命令。
#
# 用法：
#   bash scripts/fail-fast-gate.sh                    # 三个场景全跑
#   bash scripts/fail-fast-gate.sh db-down            # 只跑一个（db-down|qdrant-down|minio-down）
#
# 可调项（均有默认，CI 只在需要时覆盖）：
#   FAIL_FAST_PROFILE        起真进程用的 profile，默认 dev（本地由 .env 提供依赖坐标）
#   FAIL_FAST_BUDGET_MS      单次依赖探测耗时上限，默认 3000，对齐 app.dependency.health-timeout-ms
#   FAIL_FAST_PROCESS_LIMIT_S 单场景进程墙钟上限，默认 240，超过即判"启动挂死"
#
# 场景不会破坏任何东西：MySQL 断连用必然被拒绝的 127.0.0.1:1，Qdrant/MinIO 用
# RFC 2606 保留域 fail-fast.invalid；全程 --auto-table.mode=none --spring.flyway.enabled=false
# --data.init.enabled=false --permission.sync.enabled=false，应用对真库只做一次 SELECT 1 探测。
set -uo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
log_dir="$repo_root/target/fail-fast-gate"
profile=${FAIL_FAST_PROFILE:-dev}
budget_ms=${FAIL_FAST_BUDGET_MS:-3000}
process_limit_s=${FAIL_FAST_PROCESS_LIMIT_S:-240}

mkdir -p "$log_dir"

# 空格分隔：spring-boot-maven-plugin 的 arguments 按空白切分，逗号不切。
common_args="--server.port=0 --spring.flyway.enabled=false --auto-table.mode=none --data.init.enabled=false --permission.sync.enabled=false"

scenario_args() {
  case "$1" in
  db-down)
    # 唯一变量是数据库：驱动换成 MySQL、URL 指向无人监听的端口，
    # 另两项依赖走内存实现，让启动期第一次数据库访问就是 Fail-Fast 探测本身。
    printf '%s --rag.vector-store.provider=in-memory --knowledge.storage.provider=in-memory --spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver --spring.datasource.url=jdbc:mysql://127.0.0.1:1/crm_fail_fast --spring.datasource.username=gate --spring.datasource.password=gate' "$common_args"
    ;;
  qdrant-down)
    printf '%s --knowledge.storage.provider=in-memory --rag.vector-store.provider=qdrant --knowledge.qdrant.host=fail-fast.invalid --knowledge.qdrant.port=1 --knowledge.qdrant.initialize-on-startup=false' "$common_args"
    ;;
  minio-down)
    printf '%s --rag.vector-store.provider=in-memory --knowledge.storage.provider=minio --knowledge.minio.endpoint=http://fail-fast.invalid:9000 --knowledge.minio.access-key=gate-not-a-real-key --knowledge.minio.secret-key=gate-not-a-real-secret' "$common_args"
    ;;
  *) return 1 ;;
  esac
}

# "<actuator 组件名> <期望出现在启动失败日志里的中文原因>"
scenario_expect() {
  case "$1" in
  db-down) printf 'db MySQL 数据库不可达' ;;
  qdrant-down) printf 'vectorStore Qdrant 向量库不可达' ;;
  minio-down) printf 'minio MinIO 对象存储不可达' ;;
  esac
}

run_scenario() {
  local name=$1
  local args expect component phrase log
  if ! args=$(scenario_args "$name"); then
    echo "::error::未知场景 '$name'（可选 db-down|qdrant-down|minio-down）" >&2
    return 1
  fi
  expect=$(scenario_expect "$name")
  component=${expect%% *}
  phrase=${expect#* }
  log="$log_dir/$name.log"

  echo "── 场景 ${name}：期望 component=${component}，探测预算 ≤ ${budget_ms}ms"
  local start_ms code elapsed_ms probe_ms
  start_ms=$(date +%s%3N)
  (
    cd "$repo_root" &&
      timeout "$process_limit_s" mvn -B -ntp -q spring-boot:run \
        -Dspring-boot.run.profiles="$profile" \
        -Dspring-boot.run.arguments="$args"
  ) >"$log" 2>&1
  code=$?
  elapsed_ms=$(( $(date +%s%3N) - start_ms ))
  probe_ms=$(sed -n 's/.*fail-fast validation failed elapsedMs=\([0-9]*\).*/\1/p' "$log" | head -1)

  local failed=0
  if [ "$code" -eq 0 ]; then
    echo "   ✗ 退出码 0：依赖不可达却启动成功（Fail-Fast 失效）"
    failed=1
  fi
  if [ "$code" -eq 124 ]; then
    echo "   ✗ 被 timeout ${process_limit_s}s 杀掉：启动挂死而非快速失败"
    failed=1
  fi
  if ! LC_ALL=C grep -qF 'fail-fast validation failed' "$log"; then
    echo "   ✗ 日志里没有 fail-fast validation failed 标记（上下文可能死在别处）"
    failed=1
  fi
  if ! LC_ALL=C grep -qF "component=${component}" "$log"; then
    echo "   ✗ 失败原因未指名 component=${component}；实际归因："
    LC_ALL=C grep -aoE 'component=[A-Za-z]+' "$log" | sort -u | sed 's/^/      /'
    if [ "$component" != "db" ]; then
      echo "   提示：${profile} profile 下该场景要求 MySQL 可连（qdrant/minio 检查排在 MySQL 之后）"
      echo "        本地可先 docker compose -f deploy/docker-compose.yml up -d mysql，或用 FAIL_FAST_PROFILE 指定其它 profile"
    fi
    failed=1
  fi
  if ! LC_ALL=C grep -qF "$phrase" "$log"; then
    echo "   ✗ 缺少原因文案：${phrase}"
    failed=1
  fi
  if [ -z "$probe_ms" ]; then
    echo "   ✗ 解析不到探测耗时（elapsedMs=）"
    failed=1
  elif [ "$probe_ms" -gt "$budget_ms" ]; then
    echo "   ✗ 探测耗时 ${probe_ms}ms 超过预算 ${budget_ms}ms"
    failed=1
  fi

  echo "   实测：退出码=${code} 进程墙钟=${elapsed_ms}ms 探测耗时=${probe_ms:-n/a}ms 日志=${log#"$repo_root"/}"
  if [ "$failed" -eq 0 ]; then
    echo "   PASS"
  fi
  return "$failed"
}

cases=("$@")
if [ "${#cases[@]}" -eq 0 ]; then
  cases=(db-down qdrant-down minio-down)
fi

rc=0
for case_name in "${cases[@]}"; do
  run_scenario "$case_name" || rc=1
done

if [ "$rc" -eq 0 ]; then
  echo "Fail-Fast 门禁通过（${#cases[@]} 个场景，预算 ${budget_ms}ms/依赖，profile=${profile}）"
else
  echo "::error::Fail-Fast 门禁失败，日志目录 ${log_dir}" >&2
fi
exit "$rc"
