# 작업 체크리스트 — 프런트엔드 3단계(실제 답변 API 연결)

> 공통 서식: `docs/claude_kit_development_checklist.md`
> 기준: `.hermes/plan_part1/2026-10-29_frontend_stage3_api_agent_brief.md`, `.hermes/plan_part1/2026-10-29_frontend-chat-ui-implementation-plan.md` §3 단계 3

## 작업 정보

- 작업명: 2단계 채팅 UI를 같은 출처 `POST /api/answers`에 연결(성공·빈 출처·형식 오류·400/404/500·네트워크·취소·늦은 응답)
- 작업 일자: 2026-10-29
- 담당: Claude Code(단독 편집, Orca 배정)

## 1. 작업 시작 전

- [x] 브리프 전체, 계획 §3 단계 3, `CLAUDE.md`, 2단계 코드, 백엔드 `AnswerController`(`POST /api/answers`, 400/404/500), `AnswerRequest`(`question` NotBlank, `knowledgeBaseId` 정수 ≥1), `AnswerResponse`(`answer`, `sources`), `AnswerSource`(`documentId`, `title`, `source`, `chunkIndex`, `distance`)를 확인했다.
- [x] 2단계 미커밋 변경(`App.tsx`·`App.test.tsx`·`styles.css`·`components/`·stage2 체크리스트) 보존 — 되돌리거나 무관 부분 재작성 없음.
- [x] 백엔드·DB·OpenAPI·README·CI·Docker·의존성 변경 없음 → `spring-api-*` Skill 비해당. Java/Gradle 파일 미수정.

## 2. 설계·구현

- `frontend/src/api/answers.ts`(신규) `requestAnswer(question, signal)`:
  - `fetch('/api/answers', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: {question, knowledgeBaseId: 1}, signal })`. `userId`·이전 대화·서버 주소는 보내지 않음. `DEFAULT_KNOWLEDGE_BASE_ID = 1`.
  - `response.ok`가 아니면 `AnswerError` — 400 `bad-request`, 404 `not-found`, 그 외 `server`. 서버 오류 본문은 읽지도 싣지도 않음.
  - fetch 실패 → `network`, 단 `signal.aborted`면 원래 AbortError를 다시 던짐.
  - 200이어도 JSON 아님·`answer` 비문자열·`sources` 비배열·출처 `documentId`/`chunkIndex` 비정수·`title` 비문자열이면 `format`.
  - 정상 응답은 출처를 `{documentId, title, chunkIndex}`로 축소(`source`·`distance` 버림).
- `App.tsx`:
  - 제출 시 질문을 `loading` turn으로 추가하고 `AbortController`를 `requestRef`에 둔다. `requestRef`가 있으면 제출 무시(렌더 전 연속 Enter 포함 중복 방지). 기존 `busy`로 버튼·Enter·submit도 비활성.
  - 응답·오류는 `requestRef.current === 같은 controller`일 때만 마지막 turn에 반영. 새 대화는 `abort()` 후 `requestRef`를 비워 신호를 무시한 늦은 응답·오류를 버린다. 언마운트 시에도 abort.
  - 오류 시 입력창이 비어 있으면 원 질문을 되돌려 Enter로 재전송 가능.
- `ChatView.tsx`:
  - `Turn`에서 `unconnected` 제거, error에 `kind` 추가. 오류 종류별 고정 안내 문구(서버 본문 미표시).
  - 답변은 기존처럼 텍스트 노드로만 렌더(빈 줄 → 문단, 줄바꿈 `pre-wrap`, HTML 미해석).
  - `sources: []`면 "이 답변에 연결된 출처가 없습니다." 안내.
- 2단계 '연결 전' 문구 교체:
  - 상단 상태 → `지식 공간을 기준으로 답변`(시안 원문, 점 색도 시안의 초록).
  - 빈 화면 설명 → 시안 취지의 "…근거를 찾고, 출처와 함께 답합니다".
  - 입력 안내 → "질문마다 따로 답하며 이전 대화는 참고하지 않습니다"(다중 질문 ≠ 대화 맥락).
  - 사이드바 → "하나의 지식 공간(개발용 ID 1)"으로 실제 KB 이름 조회가 아님을 표시.
- `styles.css`:
  - `.status-dot` 색을 시안 값으로 변경.
  - `.workspace { min-height: 0 }` 추가. 2단계부터 있던 잠재 결함 수정: 대화가 길어지면 grid 행이 내용만큼 늘어나 상단바·입력창이 화면 밖으로 밀리던 문제. 3단계 브라우저 확인 중 모바일에서 발견.
- [x] 신규 의존성 없음. 민감 정보·실서버 주소 없음.

## 3. 테스트·검증

- [x] RED (API):
  - `answers.test.ts` 선작성 → `Failed to resolve import "./answers"`.
  - 구현 후 15 passed — 요청 메서드·경로·헤더·본문·signal, 정상(source·distance 제거), 빈 sources, 형식 오류 6종, 400/404/500/503(본문 미포함), 네트워크, 취소 시 AbortError.
- [x] RED (App):
  - 2단계 '연결 전' 가정 테스트 2건을 연결 동작으로 바꾸고 연결 시나리오를 추가 → `Tests 12 failed | 26 passed (38)`.
  - 모든 App 테스트는 `beforeEach`에서 끝나지 않는 모의 `fetch`로 stub → 실제 요청 불가.
- [x] GREEN: `npm test -- --run` → `Test Files 2 passed`, `Tests 38 passed (38)`.
  - App 시나리오:
    - 정상: 평문·줄바꿈 보존·`<b>` 문자 그대로, 출처 제목·`문서 #7 · 구간 2`만 표시, source 경로·distance 미표시.
    - 빈 출처 안내.
    - 형식 오류·400·404·500 고정 안내 alert, 서버 본문 미노출, 로딩 종료, 질문이 입력창에 복원되고 버튼 활성.
    - 네트워크 오류 후 같은 질문 재전송 성공.
    - 대기 중 Enter·클릭·submit 반복 → fetch 1회.
    - 새 대화 → signal aborted, 신호를 무시한 늦은 성공·늦은 500이 새 화면에 미표시.
- [x] `npm run build`(`tsc` + vite build) 성공. 번들에 `/api/answers` 1회(상대 경로).
- [x] 브라우저(Vite dev `127.0.0.1:5179`, 시스템 Chrome + Playwright):
  - 모의 응답만 사용. `page.route('**/api/answers')`로 브라우저 안에서 가로채 응답 → Vite 프록시·백엔드·DB로 나간 요청 없음(Vite 로그에 proxy/ECONNREFUSED 0건).
  - 데스크톱 1280×800·모바일 390×844 공통:
    - 요청 본문은 `{"question":…,"knowledgeBaseId":1}`만.
    - 로딩 표시·전송 버튼 비활성.
    - 답변: 출처 2건, 비밀 경로 미표시, `<script>` 문자 그대로(삽입 0), 가로 넘침 0.
    - 빈 출처 안내.
    - 500: 고정 문구 alert, 입력창 복원.
    - 지연 응답 중 새 대화(모바일은 메뉴 열고) → 늦은 답변 0건, 빈 화면.
    - 수정 후 문서 높이 = 창 높이(`scrollHeight-innerHeight=0`), 입력창 화면 안, 상단바 top 0.
  - 스크린샷 육안 확인(데스크톱 답변, 모바일 오류) — 시안 배치 유지.
  - 참고: 관찰 스크립트의 '/api/ 포함 기타 요청 1건'은 Vite 모듈 `/src/api/answers.ts` 요청으로 URL 필터 오탐.
- [x] `./gradlew test`(실행 전 `127.0.0.1:1/blocked`·Flyway false·isolated 태그 제외 확인) → exit 0, 204 tests / 0 failures / 0 errors. 실DB 미사용.
- [x] `git diff --check` exit 0. 미추적 신규 파일(`api/*.ts`, `components/*.tsx`)은 `git diff --no-index --check`로 공백 문제 없음 확인.
- [x] Stop Hook — 변경이 `frontend/` TS·CSS·`docs/`뿐이라 마커 패턴(Java/Gradle) 비해당, `.claude/.code_changed` 없음 → 스크립트상 즉시 통과. 이 세션에서 Hook 출력 자체는 관찰되지 않음.
- [ ] 실제 백엔드 연동(실서버 `POST`, KB ID 1의 실제 유효성), Spark, CI — 금지·범위 밖, 미검증.
- [ ] 스크린리더·Safari·실기기 — 미실행.

## 4. 문서·사람 검수

- [x] README·API 명세·DB 변경 없음(API 경로 변경 없음).
- [x] 기존 시안·계획 파일 미수정.
- [ ] Codex 읽기 전용 리뷰, 태환님 제품 검토·실제 연결 승인 — 대기.

## 5. 완료 기록

- 변경 파일:
  - `frontend/src/api/answers.ts`(신규), `frontend/src/api/answers.test.ts`(신규)
  - `frontend/src/App.tsx`, `frontend/src/App.test.tsx`, `frontend/src/components/ChatView.tsx`, `frontend/src/components/Composer.tsx`(안내 문구), `frontend/src/styles.css`
  - 본 체크리스트
- 남은 위험:
  - 실제 서버의 오류 응답이 JSON이 아니어도 상태 코드로만 판별하므로 영향 없음. 단 프록시 등이 200으로 HTML을 주면 `format` 오류로 표시됨(의도).
  - `answer` Markdown은 렌더하지 않고 평문으로 보여줌. 실제 LLM 출력 형식은 실연동 후 판단.
  - ~~요청 타임아웃은 없음~~ → 6절에서 120초 타임아웃 추가.

## 6. Codex 리뷰 후속 수정(타임아웃·언마운트·재시도, 2026-10-29)

### 수정 내용

1. 타임아웃(`api/answers.ts`)
   - `ANSWER_TIMEOUT_MS = 120_000`(LLM 60초 + 검색·네트워크 여유).
   - `requestAnswer`는 내부 `AbortController`로 fetch를 보내고 `Promise.race([fetch 처리, guard])`로 끝낸다. guard는 120초 타이머 또는 호출 측 abort.
     - 타이머가 먼저 끝나면 `AnswerError('timeout')`으로 끝내고 내부 요청을 중단한다.
     - 호출 측 abort면 fetch가 신호를 무시해도 즉시 AbortError로 끝난다.
   - 정상 완료·오류·취소 모두 `finally`에서 타이머와 abort 리스너를 제거한다.
   - 취소·타임아웃 뒤 늦게 온 응답은 본문을 읽지 않는다.
   - 화면 안내(고정 문구, 서버 본문 미노출): "응답 시간이 초과되었습니다(120초). 서버에서 처리가 계속될 수 있으니 잠시 후 다시 시도해 주세요." 기존 오류와 같이 로딩 종료, 질문 입력창 복원, 재시도 가능.
   - **한계: 클라이언트의 abort·타임아웃은 브라우저가 기다림을 멈출 뿐, 서버의 검색·LLM 처리 취소를 보장하지 않는다.** 백엔드는 연결이 끊겨도 처리를 끝까지 진행할 수 있고, 그동안 같은 질문을 다시 보내면 서버에 중복 작업이 생길 수 있다. 서버 측 취소는 이번 범위(프런트)가 아니다.
2. 언마운트(`App.tsx`)
   - cleanup에서 `abort()`와 함께 `requestRef.current = null`.
   - 응답·오류 처리는 `requestRef.current === 같은 controller`이고 `signal.aborted`가 아닐 때만 setState한다.
3. 재시도 중복 오류 카드(`App.tsx`)
   - 마지막 turn이 오류이고 같은 질문(trim 후 동일)을 다시 보내면 그 오류 turn을 로딩으로 대체하고 결과로 이어서 교체한다.
   - 질문을 바꿔 보내면 기존 오류 turn은 그대로 두고 새 turn을 추가한다.

### 검증

- [x] RED: 테스트 8건 추가(API 3, App 5) → `Tests 5 failed | 41 passed (46)`.
  - 실패: App 타임아웃, App 언마운트, App 같은 질문 재시도, API 120초 timeout, API 신호 무시 fetch의 호출 측 취소.
  - 처음부터 통과(회귀 보호용): API 정상 완료 타이머 제거, App 새 대화 후 타임아웃 미발생, App 바꾼 질문 문맥 유지.
- [x] GREEN: `npm test -- --run` → `Test Files 2 passed`, `Tests 46 passed (46)`.
  - API(가짜 타이머, 신호 무시 모의 fetch):
    - 119.999초까지 내부 신호 유지, 120초에 `timeout`으로 끝나고 내부 신호 aborted, 타이머 0개.
    - 정상 완료 후 타이머 0개.
    - 호출 측 abort → 즉시 AbortError, 내부 신호 aborted, 타이머 0개.
  - App:
    - 120초 무응답 → 로딩 종료, 타임아웃 alert, 질문 복원, 요청 신호 aborted, 늦은 성공 미표시, Enter 재전송 시 fetch 2회.
    - 새 대화 후 120초 경과해도 alert 없음.
    - 언마운트 → 신호 aborted, 늦은 성공 Response의 `json()` 미호출, 늦은 실패 후 `console.error` 0회.
    - 같은 질문 재시도: 500 → 500 → 성공 과정에서 질문 말풍선 1개, alert 최대 1개, 최종 성공 시 alert 0개.
    - 바꾼 질문: 이전 오류 1개 유지 + 새 답변 추가.
  - API 첫 테스트의 신호 단언은 `toBe(signal)`에서 "AbortSignal 전달·초기 미중단"으로 변경(내부 controller 사용). 취소 전파는 취소 테스트에서 확인.
  - 한계: React 19는 언마운트된 컴포넌트의 setState를 관찰할 수단(경고 등)이 없다. "setState 안 함"은 테스트에서 간접 근거로만 확인했다(요청 취소, 늦은 본문 미읽음, 콘솔 오류 없음). 직접 근거는 코드의 참조 무효화와 aborted 검사다.
- [x] `npm run build`(`tsc` + vite) 성공.
- [x] `./gradlew test`(실행 전 `127.0.0.1:1/blocked`·Flyway false·isolated 태그 제외 확인) → exit 0, 204 tests / 0 failures / 0 errors. 실DB 미사용.
- [x] `git diff --check` exit 0. 미추적 신규 파일은 `git diff --no-index --check`로 공백 문제 없음.
- [x] 실제 서버 POST 없음 — 모든 검증은 모의 fetch·가짜 타이머(브라우저 재확인은 이번 수정에서 미실행).
- [x] Stop Hook — 변경이 `frontend/` TS·`docs/`뿐이라 마커 패턴(Java/Gradle)에 해당하지 않음. `.claude/.code_changed` 없음 → 스크립트상 즉시 통과. Hook 출력 자체는 관찰되지 않음.
- 변경 파일(이번 수정): `frontend/src/api/answers.ts`, `frontend/src/api/answers.test.ts`, `frontend/src/App.tsx`, `frontend/src/App.test.tsx`, `frontend/src/components/ChatView.tsx`(timeout 문구), 본 체크리스트.

## 7. Codex 신규 Low — 진입 전 취소된 신호(2026-10-29)

- 결함: 호출 시점에 이미 `signal.aborted`이면 guard는 reject했지만, 그와 별도로 `fetchAnswer`가 그대로 실행돼 실제 `fetch` 1회와 120초 타이머가 만들어졌다.
- 수정(`frontend/src/api/answers.ts`): 함수 진입 직후 `if (signal.aborted) throw signal.reason;`를 추가해 내부 controller·타이머·fetch를 만들지 않게 했다. 이에 따라 불필요해진 `if (signal.aborted) onAbort(); else …` 분기는 리스너 등록 한 줄로 단순화했다.
- [x] RED: `answers.test.ts`에 "진입 전에 이미 취소된 신호" 테스트 추가 → `Tests 1 failed | 18 passed (19)`, 실패 사유 `expected "vi.fn()" to not be called at all, but actually been called 1 times`.
- [x] GREEN: `npm test -- --run` → `Test Files 2 passed`, `Tests 47 passed (47)`.
  - 새 테스트: rejection 값이 원래 `signal.reason`(AbortError)과 동일, fetch 0회, 가짜 타이머 0개.
  - 기존 정상 취소·120초 timeout·200·오류·App 시나리오 회귀 없음.
- [x] `npm run build`(`tsc` + vite) 성공.
- [x] `./gradlew test`(실행 전 `127.0.0.1:1/blocked`·Flyway false·isolated 태그 제외 확인) → exit 0, 204 tests / 0 failures / 0 errors.
- [x] `git diff --check` exit 0. 미추적 `frontend/src/api/*.ts`는 `git diff --no-index --check`로 공백 문제 없음.
- [x] 실제 서버 POST·DB 접속 없음(모의 fetch·가짜 타이머만 사용).
- [x] Stop Hook — `frontend/` TS·`docs/`만 변경되어 마커가 없음(`.claude/.code_changed` 없음). 스크립트상 즉시 통과하며, Hook 출력 자체는 관찰하지 못했다.
- 변경 파일: `frontend/src/api/answers.ts`, `frontend/src/api/answers.test.ts`, 본 체크리스트.
