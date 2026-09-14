#!/usr/bin/env bash
# 답변 부하 테스트를 Smoke -> Warm -> Load 순서로 돌린다. Load(10)까지만 진행한다
# (scenarios.json의 answer_load_cap 참고 — LLM은 공유 GPU를 쓰고 무겁다).
#
# 사용 예:
#   ./run_answer_load.sh http://127.0.0.1:8090
set -uo pipefail
# 주의: -e를 쓰지 않는다 — k6는 threshold(에러율 등)를 넘기면 실패 종료 코드를 반환하는데,
# threshold 초과는 다음 단계로 계속 진행하며 관찰해야 할 "결과"이지 스크립트를 중단시킬
# "오류"가 아니다. -e가 있으면 한 단계만 threshold를 넘겨도 이후 단계가 전혀 실행되지 않는다.

BASE_URL="${1:-http://127.0.0.1:8090}"
STAGES=(smoke warm load)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PERF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$PERF_DIR/results/answer"
mkdir -p "$RESULTS_DIR"

for stage in "${STAGES[@]}"; do
  echo "=== [$stage] 답변 부하 시작 ==="
  docker run --rm -i --network host \
    -v "$PERF_DIR:/perf:ro" \
    -v "$RESULTS_DIR:/results" \
    -w /perf/load \
    -e BASE_URL="$BASE_URL" -e STAGE="$stage" \
    grafana/k6 run --summary-export="/results/summary_${stage}.json" answer-load.js
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "=== [$stage] threshold 초과 또는 오류 발생 (exit $status) — 다음 단계로 계속 진행 ==="
  else
    echo "=== [$stage] 완료 ==="
  fi
done
