#!/usr/bin/env bash
# S/M 사이즈를 순서대로 색인 벤치마크한다. Spark에서 직접 실행한다.
#
# 사용 예:
#   ./run_index_benchmark.sh http://127.0.0.1:8090 ~/services/my-rag/data/markdown ../results/index
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:8090}"
MARKDOWN_ROOT="${2:-$HOME/services/my-rag/data/markdown}"
OUT_DIR="${3:-../results/index}"

for size in s m; do
  echo "=== [$size] 색인 벤치마크 시작 ==="
  python3 index_benchmark.py \
    --base-url "$BASE_URL" \
    --markdown-root "$MARKDOWN_ROOT" \
    --size "$size" \
    --out "$OUT_DIR/index_${size}.json"
done
