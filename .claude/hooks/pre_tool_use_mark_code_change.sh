#!/bin/bash
# PreToolUse 훅: Claude가 src/ 아래 코드 파일을 수정할 때 마커 생성
# Stop 훅이 이 마커를 보고 컴파일·테스트 실행 여부를 결정

INPUT=$(cat)

TOOL_NAME=$(echo "$INPUT" | python3 -c \
    "import sys,json; d=json.load(sys.stdin); print(d.get('tool_name',''))" \
    2>/dev/null || echo "")

[ "$TOOL_NAME" != "Edit" ] && [ "$TOOL_NAME" != "Write" ] && exit 0

FILE_PATH=$(echo "$INPUT" | python3 -c \
    "import sys,json; d=json.load(sys.stdin); print(d.get('tool_input',{}).get('file_path',''))" \
    2>/dev/null || echo "")

[ -z "$FILE_PATH" ] && exit 0

if echo "$FILE_PATH" | grep -E "src/.*\.(java|kt|groovy)$|build\.gradle$|pom\.xml$|settings\.gradle$" > /dev/null 2>&1; then
    SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
    PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
    echo "$FILE_PATH" >> "$PROJECT_ROOT/.claude/.code_changed"
fi

exit 0
