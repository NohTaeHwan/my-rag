#!/bin/bash
# Stop 훅: 작업 완료 전 컴파일·테스트 자동 검증 및 코드 검수 분석
#
# 동작 방식:
#   1. 이번 턴에 Claude가 수정한 파일 목록을 마커 파일에서 읽음
#   2. 마커 없으면 즉시 통과 (코드 수정 없는 턴)
#   3. 컴파일 실패 시 exit 2로 차단
#   4. 수정된 파일에 대응하는 테스트 클래스 실행 (없으면 상태 기록 후 검수로 계속)
#   5. 테스트 실패 시 exit 2로 차단
#   6. review_guide.py로 코드 검수 분석 실행
#   7. reviewRequired=true이면 분석 결과·지시를 Stop hook feedback으로 전달 후 exit 0
#   8. reviewRequired=false이면 exit 0
#   9. stop_hook_active=true이면 재시작된 세션이므로 즉시 통과 (무한 루프 방지)

INPUT=$(cat)

# 무한 루프 방지 — 훅으로 재시작된 세션은 즉시 통과
HOOK_ACTIVE=$(echo "$INPUT" | python3 -c \
    "import sys,json; d=json.load(sys.stdin); print(d.get('stop_hook_active', False))" \
    2>/dev/null || echo "False")
[ "$HOOK_ACTIVE" = "True" ] && exit 0

# ── 코드 변경 마커 확인 ───────────────────────────────────────────────────────
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
MARKER_FILE="$PROJECT_ROOT/.claude/.code_changed"

[ ! -f "$MARKER_FILE" ] && exit 0

MODIFIED_FILES=$(cat "$MARKER_FILE")
rm -f "$MARKER_FILE"

# 동일 파일에 여러 번 Edit/Write가 발생해도 최초 등장 순서로 한 번만 처리
MODIFIED_FILES=$(printf '%s\n' "$MODIFIED_FILES" | python3 -c '
import sys
seen = set()
for line in sys.stdin:
    path = line.rstrip("\r\n")
    if path and path not in seen:
        seen.add(path)
        print(path)
')

cd "$PROJECT_ROOT"

# ── 빌드 도구 감지 ───────────────────────────────────────────────────────────
if [ -f "./gradlew" ]; then
    COMPILE_CMD="./gradlew compileJava compileTestJava"
else
    exit 0
fi

# ── 컴파일 검증 ──────────────────────────────────────────────────────────────
COMPILE_OUTPUT=$($COMPILE_CMD 2>&1)
COMPILE_EXIT=$?

if [ $COMPILE_EXIT -ne 0 ]; then
    echo "" >&2
    echo "❌ [stop-build-check] 컴파일 실패 — 작업 완료가 차단되었습니다." >&2
    echo "" >&2
    echo "$COMPILE_OUTPUT" | grep -E "error:|^\s+\^|ERROR" | head -40 >&2
    echo "" >&2
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
    echo "▶  컴파일 오류를 수정한 뒤 다시 완료하세요." >&2
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
    exit 2
fi

COMPILE_RESULT="passed"

# ── 테스트 대상 결정 (수정 파일 → 대응 테스트 클래스) ──────────────────────────
TEST_CLASSES=""
NOT_FOUND_LOG=""

while IFS= read -r abs_file; do
    rel_file="${abs_file#$PROJECT_ROOT/}"
    test_file=""

    if echo "$rel_file" | grep -qE "^src/main/java/.*\.(java|kt)$"; then
        candidate=$(echo "$rel_file" \
            | sed 's|^src/main/java/|src/test/java/|' \
            | sed 's|\.java$|Test.java|' \
            | sed 's|\.kt$|Test.kt|')
        if [ -f "$candidate" ]; then
            test_file="$candidate"
        else
            NOT_FOUND_LOG="$NOT_FOUND_LOG\n    $rel_file\n    → 탐색: $candidate (없음)"
        fi
    elif echo "$rel_file" | grep -qE "^src/test/java/.*\.(java|kt)$"; then
        [ -f "$rel_file" ] && test_file="$rel_file"
    fi

    if [ -n "$test_file" ]; then
        class_name=$(echo "$test_file" \
            | sed 's|^src/test/java/||' \
            | sed 's|\.java$||' \
            | sed 's|\.kt$||' \
            | tr '/' '.')
        TEST_CLASSES="$TEST_CLASSES $class_name"
    fi
done <<< "$MODIFIED_FILES"

# 서로 다른 변경 파일이 같은 테스트로 연결되는 경우에도 한 번만 실행
TEST_CLASSES=$(printf '%s\n' "$TEST_CLASSES" \
    | tr ' ' '\n' \
    | python3 -c '
import sys
seen = set()
for line in sys.stdin:
    test_class = line.rstrip("\r\n")
    if test_class and test_class not in seen:
        seen.add(test_class)
        print(test_class)
' \
    | tr '\n' ' ')

# ── 테스트 실행 ──────────────────────────────────────────────────────────────
TEST_STATUS="not-found"
EXECUTED_TESTS=""

if [ -n "$TEST_CLASSES" ]; then
    echo "" >&2
    echo "🔍 [stop-build-check] 대응 테스트 실행 중..." >&2
    echo "$TEST_CLASSES" | tr ' ' '\n' | grep -v '^$' | sed 's/^/    /' >&2
    echo "" >&2

    TEST_ARGS=$(echo "$TEST_CLASSES" | xargs -n1 echo "--tests" | tr '\n' ' ')
    TEST_CMD="./gradlew test $TEST_ARGS"

    TEST_OUTPUT=$($TEST_CMD 2>&1)
    TEST_EXIT=$?

    if [ $TEST_EXIT -ne 0 ]; then
        echo "" >&2
        echo "❌ [stop-build-check] 테스트 실패 — 작업 완료가 차단되었습니다." >&2
        echo "" >&2
        echo "$TEST_OUTPUT" | grep -E "FAILED|> Task|tests were|Exception" | head -30 >&2
        echo "" >&2
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        echo "▶  실패한 테스트를 수정한 뒤 다시 완료하세요." >&2
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
        exit 2
    fi

    echo "" >&2
    echo "✅ [stop-build-check] 테스트 통과" >&2
    echo "" >&2
    echo "$TEST_CLASSES" | tr ' ' '\n' | grep -v '^$' | sed 's/^/    /' >&2
    echo "" >&2

    TEST_STATUS="passed"
    EXECUTED_TESTS=$(echo "$TEST_CLASSES" | tr ' ' ',' | sed 's/^,//')
else
    echo "" >&2
    echo "ℹ️  [stop-build-check] 컴파일 통과. 대응 테스트 파일 없음." >&2
    echo "" >&2
    printf "%b\n" "$NOT_FOUND_LOG" >&2
    echo "" >&2
fi

# ── 코드 검수 분석 ────────────────────────────────────────────────────────────
REVIEW_SCRIPT="$PROJECT_ROOT/.claude/review/review_guide.py"
RULES_FILE="$PROJECT_ROOT/.claude/review/review-rules.json"

if [ ! -f "$REVIEW_SCRIPT" ] || [ ! -f "$RULES_FILE" ]; then
    exit 0
fi

REVIEW_OUTPUT=$(python3 "$REVIEW_SCRIPT" \
    --project-root "$PROJECT_ROOT" \
    --changed-files "$MODIFIED_FILES" \
    --compile-result "$COMPILE_RESULT" \
    --test-result "$TEST_STATUS" \
    --executed-tests "$EXECUTED_TESTS" \
    --rules-file "$RULES_FILE" 2>&1)

REVIEW_EXIT=$?

if [ $REVIEW_EXIT -ne 0 ]; then
    echo "" >&2
    echo "⚠️  [stop-build-check] 검수 분석 실패 — 수동 검수가 필요합니다." >&2
    echo "" >&2
    echo "$REVIEW_OUTPUT" | head -20 | sed 's/^/    /' >&2
    echo "" >&2
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
    echo "코드를 추가로 수정하지 마세요." >&2
    echo "검수 분석 스크립트 실행에 실패했습니다. 사용자에게 수동 검수가 필요하다는 사실과 위 실패 원인을 알려주세요." >&2
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >&2
    exit 2
fi

REVIEW_REQUIRED=$(echo "$REVIEW_OUTPUT" | python3 -c \
    "import sys,json; d=json.load(sys.stdin); print(d.get('reviewRequired', False))" \
    2>/dev/null || echo "False")

HIGHEST_RISK=$(echo "$REVIEW_OUTPUT" | python3 -c \
    "import sys,json; d=json.load(sys.stdin); print(d.get('highestRisk', 'none'))" \
    2>/dev/null || echo "none")

if [ "$REVIEW_REQUIRED" = "True" ]; then
    REVIEW_CONTEXT=$(cat <<'EOF'
코드를 추가로 수정하지 마세요.
아래 검수 분석 결과를 바탕으로 사용자에게 코드 검수 리포트만 출력하세요.
리포트는 반드시 [Hook 자동 탐지 결과]와 [Agent 추가 검토 의견]으로 구분하세요.
[Hook 자동 탐지 결과]에는 JSON findings와 validation에 있는 내용만 옮기세요.
Hook 결과의 파일·라인·심볼·테스트 결과를 만들거나 변경하지 마세요.
[Agent 추가 검토 의견]은 선택 사항이며, 각 항목에 'Hook 탐지 결과가 아닌 Agent의 추가 의견'이라고 명시하세요.
추가 의견이 없으면 [Agent 추가 검토 의견] 아래에 '추가 의견 없음'이라고 출력하세요.
전체 검수 포인트는 최대 3~7개이며 위치·이유·확인 내용을 포함하세요.
이상징후를 확정 오류로 단정하지 마세요.
후속 수정 여부는 사용자가 결정합니다.
EOF
)
    if [ "$HIGHEST_RISK" = "critical" ] || [ "$HIGHEST_RISK" = "high" ]; then
        REVIEW_CONTEXT="${REVIEW_CONTEXT}
리포트 마지막에 '/code-review 실행을 권장합니다' 한 줄을 추가하세요."
    fi
    REVIEW_CONTEXT="${REVIEW_CONTEXT}

검수 분석 결과:
${REVIEW_OUTPUT}"

    FEEDBACK_JSON=$(printf '%s' "$REVIEW_CONTEXT" | python3 -c \
        "import json,sys; print(json.dumps({'hookSpecificOutput': {'hookEventName': 'Stop', 'additionalContext': sys.stdin.read()}}, ensure_ascii=False))")
    FEEDBACK_EXIT=$?
    if [ $FEEDBACK_EXIT -ne 0 ] || [ -z "$FEEDBACK_JSON" ]; then
        echo "⚠️  [stop-build-check] feedback JSON 생성 실패 — 수동 검수가 필요합니다." >&2
        exit 2
    fi

    printf '%s\n' "$FEEDBACK_JSON"
    exit 0
fi

# reviewRequired=false: 간략 위험도 표시
case "$HIGHEST_RISK" in
    critical|high) RISK_ICON="🔴" ;;
    medium)        RISK_ICON="🟡" ;;
    *)             RISK_ICON="🟢" ;;
esac
EXTRA=""
[ "$TEST_STATUS" = "not-found" ] && EXTRA=" | 대응 테스트 없음"
echo "" >&2
echo "${RISK_ICON} [stop-build-check] 검수 완료 — 위험도: ${HIGHEST_RISK}${EXTRA}" >&2
echo "" >&2

exit 0
