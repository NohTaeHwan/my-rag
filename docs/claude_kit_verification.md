# claude-kit 적용·동작 검증

> 목적: claude-kit이 이 프로젝트에 올바르게 설치되었고, Claude Code의 실제 개발 흐름에서 Context·Hook·Verification·Human-in-the-loop가 동작하는지 확인한다.
>
> 검증 결과는 실행한 명령과 실제 출력에 근거해 기록한다. 실행하지 않은 항목은 `미검증`으로 둔다.

## 1. 적용 파일 확인

### 확인 명령

```bash
cd /Users/nohtaehwan/dev/my-rag

test -f CLAUDE.md
test -f .claude/settings.json
test -x .claude/hooks/pre_tool_use_mark_code_change.sh
test -x .claude/hooks/stop_build_check.sh
test -f .claude/review/review_guide.py
test -f .claude/review/review-rules.json
test -f .claude/skills/spring-api-create/SKILL.md
python3 -m json.tool .claude/settings.json >/dev/null
```

### 기대 결과

- 모든 `test` 명령이 성공한다.
- `settings.json`이 JSON 오류 없이 읽힌다.
- 프로젝트의 `.claude/` 아래에 Hook·review·Spring API Skill이 존재한다.

## 2. claude-kit 자체 회귀 테스트

```bash
python3 -m pytest -q /Users/nohtaehwan/dev/claude-kit/tests
```

### 기대 결과

- 모든 테스트 통과
- 실패 시 프로젝트 적용 전에 claude-kit 원본 원인을 확인한다.

## 3. 프로젝트 기본 검증

```bash
./gradlew test
```

### 기대 결과

```text
BUILD SUCCESSFUL
```

## 4. 실제 Health Check 검증

터미널 1:

```bash
./gradlew bootRun
```

터미널 2:

```bash
curl -fsS http://127.0.0.1:8080/health
```

### 기대 결과

```json
{"status":"UP","database":"UP"}
```

## 5. Claude Code 실제 Hook 흐름 검증

새 Claude Code 세션에서 다음처럼 요청한다.

```text
현재 프로젝트의 CLAUDE.md를 읽고, 검증 절차를 지켜서
Health Check 응답에 version 필드를 추가해줘.
테스트를 먼저 작성하고 구현한 뒤 검증 결과를 보고해줘.
```

### 확인할 항목

| 항목 | 기대 동작 | 결과 |
|---|---|---|
| Context | 프로젝트 `CLAUDE.md`와 관련 Skill을 읽고 규칙을 따른다 | 미검증 |
| TDD | 대응 테스트를 작성·수정한다 | 미검증 |
| PreToolUse Hook | Java 변경 시 `.claude/.code_changed` 마커를 기록한다 | 미검증 |
| Stop Hook | `./gradlew compileJava compileTestJava`를 실행한다 | 미검증 |
| 대응 테스트 | 변경 파일에 대응하는 테스트를 실행한다 | 미검증 |
| Review | 변경 내용에 대한 검수 결과를 출력한다 | 미검증 |
| Human-in-the-loop | 위험 변경이 있으면 사람이 확인할 지점을 안내한다 | 미검증 |

> 검증이 끝나면 위 표의 `미검증`을 실제 결과로 바꾸고, 변경된 API가 있으므로 개발 체크리스트와 문서도 함께 갱신한다.

## 6. 검증 기록

| 실행일 | 검증 범위 | 명령·시나리오 | 실제 결과 | 확인자 |
|---|---|---|---|---|
| YYYY-MM-DD | 예: 설치 파일 | 예: `python3 -m json.tool ...` | 통과/실패/미검증 | 태환 |

## 주의사항

- `application*.yml`, `.env`, `.claude/settings.json`, Hook 파일 수정은 보호 대상이므로 승인 절차를 따른다.
- DB 데이터를 삭제하거나 초기화하는 검증은 운영 데이터에 사용하지 않는다.
- `bootRun`이 이미 실행 중이면 포트 충돌이 발생하므로 기존 프로세스 또는 포트를 먼저 확인한다.
