#!/usr/bin/env bash
# evidence-to-operations G3 · 图片开关「开启通道」预检/守望脚本（只读，零真外呼）
#
# 做什么：把 docs/multimodal-gray-rollout.md §9 的人工核对单变成可判定输出。读入四键快照
#         （--snapshot FILE 的 TSV/JSON，或 --base-url/--token 直连管理端只读接口
#         GET /platform/config/items?namespace=rag.retrieval），逐键回显 + 给出 GO/NO-GO 与原因码。
#
# 四键（真值源 = DynamicConfigKeyRegistry / ConfigKeyTierPolicy，本脚本不改任何值）：
#   A   = rag.retrieval.image-vector-ingest.enabled      （真视觉路，默认 false）
#   B   = rag.retrieval.image-slice-ingest.enabled       （方案 B 总开关，默认 false）
#   AL  = rag.retrieval.image-slice-ingest.kb-allowlist  （KB 灰度白名单，空 = 全库，默认空）
#   CAP = rag.retrieval.image-slice-ingest.max-images-per-document（单文档图帽 1~20，默认 5）
#
# 判定规则（按序短路；依据 §2 结构事实与 §12 实证）：
#   1. 键缺失 / 值不合法（布尔非 true|false、图帽越界 1~20、快照不可解析、接口不可达）→ BAD_INPUT
#   2. A=true 且 B=false → A_WITHOUT_B（**链路完全不动（§2/§12 已实证）**——真视觉路嵌在方案 B 门控之内）
#   3. A=true 且 B=true  → ALREADY_ON（通道已开，重复打开无意义；变更走 §11 回滚或扩面=改白名单；
#                          若白名单为空会附带「全库生效未限定灰度面」提示）
#   4. B=true 且白名单空（A=false）→ NO_ALLOWLIST（§3 第 1 步：灰度期不要留空，先限定 KB 再谈开 A）
#   5. B=false（A=false）→ B_OFF（方案 B 总开关未开，含「四键全关」的原始态）
#   6. 其余（B=true + 白名单非空 + A=false）→ GO（= §3 第 3 步已就位，下一步才是第 4 步开 A）
#
# 退出码语义（这就是"判定"）：0=GO；1=NO-GO（**合法结论，不是故障**）；2=输入错误。
#
# 用法：
#   image-vector-ingest-preflight.sh preflight --snapshot F
#   image-vector-ingest-preflight.sh preflight --base-url http://host --token <JWT>
#   image-vector-ingest-preflight.sh watch --snapshot F --interval 30 --times 5
#
# 快照格式（--snapshot）：TSV（每行 `key<TAB>value`，# 开头为注释）或扁平 JSON（{"key":"value",...}）。
# 零真外呼：--base-url 只在显式传参时访问用户给定的管理端；selftest 只用 fixture 文件。
set -uo pipefail

EXIT_GO=0
EXIT_NO_GO=1
EXIT_BAD_INPUT=2

KEY_A="rag.retrieval.image-vector-ingest.enabled"
KEY_B="rag.retrieval.image-slice-ingest.enabled"
KEY_AL="rag.retrieval.image-slice-ingest.kb-allowlist"
KEY_CAP="rag.retrieval.image-slice-ingest.max-images-per-document"

usage() {
  cat <<'USAGE'
用法:
  image-vector-ingest-preflight.sh preflight --snapshot FILE | --base-url URL --token TOKEN
  image-vector-ingest-preflight.sh watch --snapshot FILE | --base-url URL --token TOKEN [--interval 秒] [--times 次数]
退出码: 0=GO, 1=NO-GO(合法结论), 2=输入错误
USAGE
}

bad_input() {
  echo "PREFLIGHT verdict=NO-GO reason=BAD_INPUT detail=$1"
  exit "$EXIT_BAD_INPUT"
}

# ---- 输入解析 ------------------------------------------------------------

SNAPSHOT=""
BASE_URL=""
TOKEN=""
MODE="${1:-}"
shift || true
INTERVAL=30
TIMES=0   # watch 模式默认无限轮询，Ctrl-C 结束

while [ $# -gt 0 ]; do
  case "$1" in
    --snapshot) SNAPSHOT="${2:-}"; shift 2 ;;
    --base-url) BASE_URL="${2:-}"; shift 2 ;;
    --token) TOKEN="${2:-}"; shift 2 ;;
    --interval) INTERVAL="${2:-}"; shift 2 ;;
    --times) TIMES="${2:-}"; shift 2 ;;
    *) bad_input "未知参数 $1" ;;
  esac
done

case "$MODE" in
  preflight) TIMES=1 ;;
  watch) : ;;
  *) usage >&2; bad_input "子命令必须是 preflight 或 watch" ;;
esac

case "$INTERVAL" in
  ''|*[!0-9]*) bad_input "--interval 必须是非负整数秒" ;;
esac
case "$TIMES" in
  ''|*[!0-9]*) bad_input "--times 必须是非负整数" ;;
esac

# ---- 取值：快照文件或管理端只读接口 --------------------------------------

VAL_A=""; VAL_B=""; VAL_AL=""; VAL_CAP=""

if [ -n "$SNAPSHOT" ]; then
  if [ ! -f "$SNAPSHOT" ]; then
    bad_input "快照文件不存在: $SNAPSHOT"
  fi
  first_char=$(head -c 1 "$SNAPSHOT" | tr -d '[:space:]')
  if [ "$first_char" = "{" ]; then
    # 扁平 JSON：{"key":"value",...}（值必须是 JSON 字符串）
    extract_json() {
      sed 's/[[:space:]]//g' "$SNAPSHOT" \
        | tr ',' '\n' | tr -d '{}"' \
        | awk -F':' -v key="$1" 'index($0, key":")==1 { sub(/^([^:]*):/, "", $0); print $0; exit }'
    }
    VAL_A=$(extract_json "$KEY_A")
    VAL_B=$(extract_json "$KEY_B")
    VAL_AL=$(extract_json "$KEY_AL")
    VAL_CAP=$(extract_json "$KEY_CAP")
  else
    # TSV：key<TAB>value，# 注释
    read_tsv() {
      awk -F'\t' -v key="$1" '
        /^[[:space:]]*#/ { next }
        NF >= 2 && $1 == key { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $2); print $2; exit }
      ' "$SNAPSHOT"
    }
    VAL_A=$(read_tsv "$KEY_A")
    VAL_B=$(read_tsv "$KEY_B")
    VAL_AL=$(read_tsv "$KEY_AL")
    VAL_CAP=$(read_tsv "$KEY_CAP")
  fi
elif [ -n "$BASE_URL" ]; then
  [ -n "$TOKEN" ] || bad_input "--base-url 必须与 --token 成对"
  command -v curl >/dev/null 2>&1 || bad_input "curl 不可用，无法访问管理端"
  RESP=$(curl -fsS -m 10 -H "Authorization: Bearer $TOKEN" \
    "$BASE_URL/platform/config/items?namespace=rag.retrieval") || bad_input "管理端接口不可达（HTTP 失败）"
  [ -n "$RESP" ] || bad_input "管理端返回空响应"
  extract_api() {
    printf '%s' "$RESP" | awk -v key="$1" '
      BEGIN { RS = "\"key\"" }
      NR > 1 && index($0, ":\"" key "\"") == 1 {
        if (match($0, /"value"[[:space:]]*:[[:space:]]*"[^"]*"/)) {
          v = substr($0, RSTART, RLENGTH)
          sub(/^"value"[[:space:]]*:[[:space:]]*"/, "", v)
          sub(/"$/, "", v)
          print v
          exit
        }
      }'
  }
  VAL_A=$(extract_api "$KEY_A")
  VAL_B=$(extract_api "$KEY_B")
  VAL_AL=$(extract_api "$KEY_AL")
  VAL_CAP=$(extract_api "$KEY_CAP")
else
  bad_input "必须提供 --snapshot FILE 或 --base-url URL --token TOKEN"
fi

# ---- 校验（BAD_INPUT 域）-------------------------------------------------

for pair in "A=$VAL_A" "B=$VAL_B"; do
  case "${pair#*=}" in
    true|false) : ;;
    *) bad_input "键 ${pair%%=*} 缺失或不是 true/false（快照必须覆盖四键）" ;;
  esac
done
case "$VAL_CAP" in
  ''|*[!0-9]*) bad_input "键 CAP 缺失或不是整数: '$VAL_CAP'" ;;
esac
if [ "$VAL_CAP" -lt 1 ] || [ "$VAL_CAP" -gt 20 ]; then
  bad_input "CAP 越界（注册表范围 1~20）: $VAL_CAP"
fi

# ---- 判定（按序短路）-----------------------------------------------------

emit() {
  echo "PREFLIGHT key=$KEY_A value=$VAL_A"
  echo "PREFLIGHT key=$KEY_B value=$VAL_B"
  echo "PREFLIGHT key=$KEY_AL value=[$VAL_AL]"
  echo "PREFLIGHT key=$KEY_CAP value=$VAL_CAP"
}

run_once() {
  echo "PREFLIGHT ---- $(date '+%Y-%m-%dT%H:%M:%S%z') ----"
  emit
  if [ "$VAL_A" = "true" ] && [ "$VAL_B" = "false" ]; then
    echo "PREFLIGHT verdict=NO-GO reason=A_WITHOUT_B"
    echo "PREFLIGHT note=只开 A 而方案 B 总开关仍 false ⇒ 链路完全不动（§2/§12 已实证）——零外呼、零落盘，不要把它当作已灰度"
    return "$EXIT_NO_GO"
  fi
  if [ "$VAL_A" = "true" ]; then
    echo "PREFLIGHT verdict=NO-GO reason=ALREADY_ON"
    if [ -z "$VAL_AL" ]; then
      echo "PREFLIGHT note=通道已开且白名单为空（全库生效，灰度面未限定）——变更先走 §11 回滚，扩面=改白名单不是改开关"
    else
      echo "PREFLIGHT note=通道已开（A=true），重复打开无意义；变更走 §11 回滚或扩面=改白名单"
    fi
    return "$EXIT_NO_GO"
  fi
  if [ "$VAL_B" = "true" ] && [ -z "$VAL_AL" ]; then
    echo "PREFLIGHT verdict=NO-GO reason=NO_ALLOWLIST"
    echo "PREFLIGHT note=方案 B 已开但 KB 白名单为空（全库生效）——按 §3 第 1 步先限定灰度 KB，再谈开 A"
    return "$EXIT_NO_GO"
  fi
  if [ "$VAL_B" = "false" ]; then
    echo "PREFLIGHT verdict=NO-GO reason=B_OFF"
    echo "PREFLIGHT note=方案 B 总开关为 false（四键全关时即原始态）：链路不会动作；按 §3 灰度步骤从第 1 步开始，打开动作属 Owner 运行期决定（§9 边界）"
    return "$EXIT_NO_GO"
  fi
  echo "PREFLIGHT verdict=GO reason=GO"
  echo "PREFLIGHT note=方案 B 已开且白名单已限定、真视觉路未开：§3 第 1~3 步已就位，下一步（第 4 步开 A）属 Owner 运行期决定"
  return "$EXIT_GO"
}

status=0
if [ "$MODE" = "preflight" ]; then
  run_once || status=$?
else
  round=0
  while [ "$TIMES" -eq 0 ] || [ "$round" -lt "$TIMES" ]; do
    run_once || status=$?
    round=$((round + 1))
    if [ "$TIMES" -eq 0 ] || [ "$round" -lt "$TIMES" ]; then
      sleep "$INTERVAL"
    fi
  done
fi
exit "$status"
