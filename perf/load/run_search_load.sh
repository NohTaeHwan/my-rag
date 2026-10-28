#!/usr/bin/env bash
# 검색 부하 테스트를 Smoke -> Warm -> Load -> Stress -> Soak 순서로 돌린다.
# Saturation은 1차에서 의도적으로 제외한다(scenarios.json 참고, 별도 승인 후 진행).
#
# 사용 예:
#   KNOWLEDGE_BASE_ID=1 ./run_search_load.sh http://127.0.0.1:8090
#
# KNOWLEDGE_BASE_ID(필수, 양의 정수)는 데이터셋을 색인한 KB여야 한다 — 기본값 없음.
set -uo pipefail
# 주의: -e를 쓰지 않는다 — k6는 threshold(에러율 등)를 넘기면 실패 종료 코드를 반환하는데,
# threshold 초과는 다음 단계로 계속 진행하며 관찰해야 할 "결과"이지 스크립트를 중단시킬
# "오류"가 아니다. -e가 있으면 한 단계만(특히 Stress) threshold를 넘겨도 이후 단계(Soak
# 포함)가 전혀 실행되지 않는다.

BASE_URL="${1:-http://127.0.0.1:8090}"
KNOWLEDGE_BASE_ID="${KNOWLEDGE_BASE_ID:-}"
if ! [[ "$KNOWLEDGE_BASE_ID" =~ ^[1-9][0-9]*$ ]]; then
  echo "KNOWLEDGE_BASE_ID는 필수 양의 정수다 (입력: '$KNOWLEDGE_BASE_ID')" >&2
  exit 2
fi
STAGES=(smoke warm load stress soak)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PERF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$PERF_DIR/results/search"
mkdir -p "$RESULTS_DIR"

for stage in "${STAGES[@]}"; do
  echo "=== [$stage] 검색 부하 시작 ==="
  docker run --rm -i --network host \
    -v "$PERF_DIR:/perf:ro" \
    -v "$RESULTS_DIR:/results" \
    -w /perf/load \
    -e BASE_URL="$BASE_URL" -e STAGE="$stage" -e KNOWLEDGE_BASE_ID="$KNOWLEDGE_BASE_ID" \
    grafana/k6 run --summary-export="/results/summary_${stage}.json" search-load.js
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "=== [$stage] threshold 초과 또는 오류 발생 (exit $status) — 다음 단계로 계속 진행 ==="
  else
    echo "=== [$stage] 완료 ==="
  fi
done
