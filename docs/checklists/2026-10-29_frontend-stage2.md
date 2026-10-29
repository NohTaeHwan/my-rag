# 작업 체크리스트 — 프런트엔드 2단계(승인 시안 기반 채팅 UI 컴포넌트)

> 공통 서식: `docs/claude_kit_development_checklist.md`
> 기준: `.hermes/plan_part1/2026-10-29_frontend_stage2_ui_agent_brief.md`, `.hermes/plan_part1/2026-10-29_frontend-chat-ui-implementation-plan.md` §3 단계 2, 승인 시안 `.hermes/plan_part1/chat-ui-design-v1.html`

## 작업 정보

- 작업명: 승인 시안의 사이드바·상단바·빈 화면·질문/답변 상태·출처·입력창·모바일 메뉴를 React 컴포넌트로 구현(API 미연결)
- 작업 일자: 2026-10-29
- 담당: Claude Code(단독 편집, Orca 배정)

## 1. 작업 시작 전

- [x] 범위 — 포함: `frontend/src/App.tsx`, `styles.css`, `components/{ChatView,Composer,SourceList}.tsx`, `App.test.tsx`. 제외: `fetch`·`/api/answers` 연결(3단계), 백엔드·DB·배포·CI·README, 대화 저장·스트리밍·다중 턴 문맥.
- [x] `CLAUDE.md`, 브리프 전체, 계획 §3 단계 2, 승인 시안, 공통 체크리스트 서식을 읽었다.
- [x] 시작 시 작업 트리 clean(main, `d84366d`) 확인 — 보존할 타 작업 변경 없음.
- [x] 신규/변경 API 없음 → `spring-api-*` Skill 비해당. Java·Gradle 파일 미변경.

## 2. 설계·구현

- `App.tsx`: 사이드바(브랜드 `문서와 대화`, `새 대화`, 질문 범위 `기본 지식 공간`, 안내 문구), 상단바(`<h1>내 문서에 질문하기</h1>` / 기본 지식 공간 / `답변 연결 전` 회색 점), 대화 영역, 입력창. 상태는 `turns`(질문+상태)·`draft`·`menuOpen` 로컬 state뿐.
  - 질문 제출 → `{ question, status: 'unconnected' }`만 추가하고 "답변 API 연결 전이라 아직 답변을 만들지 않습니다" 안내. 로딩·가짜 답변·가짜 오류로 전이하지 않음.
  - `initialTurns` prop은 테스트 주입 전용(로딩·오류·답변 상태 렌더 검증). `main.tsx`는 prop 없이 렌더 → 제품 번들에 예시 데이터 없음.
  - 새 대화: turns·입력 초기화, 메뉴 닫기, 입력창 포커스.
  - 모바일 메뉴: `aria-expanded`/`aria-controls`, 열면 `새 대화`로 포커스, Escape·배경(scrim) 클릭으로 닫고 메뉴 버튼에 포커스 복귀. 닫힌 사이드바는 `visibility:hidden`으로 탭 이동 대상에서 제외.
  - textarea 높이 자동 증가(최대 150px, CSS).
- `components/Composer.tsx`: Enter 전송, Shift+Enter 줄바꿈, 한글 조합 중(`isComposing`) Enter 무시, 공백만 입력 시 버튼 비활성·제출 무시, `busy`(마지막 질문이 loading)면 버튼·Enter·form submit 모두 무시(중복 제출 방지). 안내 `Enter로 전송 · Shift + Enter로 줄바꿈 · 답변 API 연결 전`.
- `components/ChatView.tsx`: `role="log"`·`aria-label="대화"`·`aria-live="polite"`. 빈 화면(시안 문구, 추천 질문 2개는 입력창만 채움), 질문 말풍선(`pre-wrap`·`overflow-wrap:anywhere`), 상태별 결과: unconnected 안내 / loading `role="status"` / error `role="alert"` / answer 문단(텍스트로만 렌더, HTML 미해석) + 출처.
- `components/SourceList.tsx`: 현재 API 메타데이터 `title`·`documentId`·`chunkIndex`만 `참고한 문서` 목록으로 표시. 원문 미리보기·서랍(drawer)·임의 문서명 없음.
- `styles.css`: 시안의 색 토큰·여백·타이포·760px 반응형 그대로, 시스템 폰트만, `prefers-reduced-motion` 존중. 시안 대비 변경: 미리보기 배너·상태 셀렉트·예시 대화·출처 서랍 제거, KB 카드의 `예시 · Knowledge Base #1` 줄 제거, 상태 점을 연결 전 회색으로, 빈 화면 설명을 "…답하도록 준비 중입니다"로(아직 답하지 않으므로).
- [x] 신규 의존성 없음(`package.json`·lockfile 미변경). `@testing-library/user-event` 대신 기존 `fireEvent` 사용.
- [x] 민감 정보 — 번들에 자격 증명·실제 KB/문서명·원문 없음. 빌드 JS의 `fetch(`는 Vite modulepreload polyfill 1건뿐(API 호출 아님).

## 3. 테스트·검증

- [x] RED: `App.test.tsx` 10건 선작성 → 구현 전 `Failed Tests 10`.
- [x] GREEN: `cd frontend && npx vitest run` → `Tests 10 passed (10)`. 검증 항목: 빈 화면·답변 연결 전 표시, Enter 전송·입력 비움·포커스·`fetch` 미호출·로딩/출처 미생성, Shift+Enter·조합 중 Enter 미전송, 공백 차단, loading 중 중복 제출 차단(버튼·Enter·submit), 오류 alert, 답변 본문·출처 메타데이터, 새 대화 초기화·포커스, 추천 질문은 입력만 채움, 모바일 메뉴 열기 포커스·Escape·scrim 닫기·포커스 복귀.
- [x] `npm run build`(`tsc` + vite build) 성공.
- [x] 브라우저(Vite dev `127.0.0.1:5179`, 시스템 Google Chrome headless를 Playwright 1.64.1로 구동, 스크립트·스크린샷은 저장소 밖 scratchpad):
  - 데스크톱 1280×800·모바일 390×844 빈 화면, 질문 제출 후 모두 `scrollWidth - innerWidth = 0`(가로 넘침 없음). 긴 무공백 문자열+줄바꿈 질문도 말풍선 안에서 줄바꿈.
  - 제출 후 말풍선 1개 + 연결 전 안내, 입력 비움, 입력창 포커스 유지.
  - 모바일: 닫힌 사이드바 비가시 → 메뉴 클릭 `aria-expanded=true`, 포커스 `새 대화` → Escape 후 `false`, 포커스 메뉴 버튼, 사이드바 비가시, 다음 Tab은 `질문 입력`.
  - 네트워크: 로컬 Vite 외 요청·`/api/` 요청 0건.
  - 브라우저 확인 중 발견·수정: 처음엔 메뉴를 열어도 포커스가 `새 대화`로 가지 않음(visibility 전환 지연) → 열 때 visibility 즉시 전환하도록 CSS 수정 후 재확인 통과. jsdom 테스트는 CSS를 적용하지 않아 이 문제를 잡지 못한다.
  - 스크린샷 육안 확인: 데스크톱 빈 화면, 모바일 질문 제출 후, 모바일 메뉴 열림 — 시안의 색·배치와 일치.
- [x] `./gradlew test`(실행 전 DB 차단 `127.0.0.1:1/blocked`·Flyway false·isolated 태그 제외 확인) → exit 0, 204 tests / 0 failures / 0 errors.
- [x] Stop Hook — 이번 변경은 `frontend/` TS/CSS뿐이라 `pre_tool_use_mark_code_change.sh`(Java/Gradle 패턴만)가 마커를 만들지 않음(`.claude/.code_changed` 없음 확인). 따라서 Stop Hook은 컴파일·테스트 없이 즉시 통과 예정 — 실제 실행 결과는 턴 종료 시점이라 미관찰.
- [ ] 로딩·오류·답변·출처 상태의 실제 브라우저 렌더 — 제품 화면에서 만들 수 없는 상태라(테스트 주입 전용) jsdom 테스트로만 확인. 3단계 API 연결 후 브라우저 확인 대상.
- [ ] 실제 스크린리더·Safari·실기기 확인 — 미실행.
- [ ] `bootJar`/Docker/`bootRun` 재검증·CI — 이번 UI 범위 밖, 미실행.

## 4. 문서·사람 검수

- [x] README — API 경로 변경 없음, 미변경.
- [x] DB·API 명세 변경 없음.
- [x] 기존 시안·계획 파일 미수정.
- [ ] 사람 검수 — 2단계 화면 최종 디자인 승인은 태환님 결정(이 기록은 승인이 아님).

## 5. 완료 기록

- 변경 파일: `frontend/src/App.tsx`, `frontend/src/App.test.tsx`, `frontend/src/styles.css`, `frontend/src/components/ChatView.tsx`(신규), `frontend/src/components/Composer.tsx`(신규), `frontend/src/components/SourceList.tsx`(신규), 본 체크리스트(신규)
- 실행한 검증 명령: `npx vitest run`, `npm run build`, Vite dev + Playwright(system Chrome) 관찰 스크립트, `./gradlew test`
- 남은 위험:
  - ~~모바일 메뉴가 열린 동안 포커스 트랩이 없어 Tab으로 배경 요소에 갈 수 있다~~ → 6절에서 `inert`로 해소.
  - 답변 문단 분리는 빈 줄 기준 단순 텍스트 렌더 — Markdown 렌더링은 3단계에서 API 응답 형태를 보고 결정.
  - 질문 목록은 로컬 state라 새로고침 시 사라진다(브리프상 영구 저장 범위 밖).

## 6. Codex 독립 리뷰 후속 수정(Medium 1·Low 1, 2026-10-29)

- Medium — 모바일 메뉴가 열린 동안 Tab이 가려진 workspace의 입력/버튼으로 샘.
  - `App.tsx`: 메뉴 열림 동안 `<main className="workspace" inert={menuOpen}>`(React 19 boolean prop). 메뉴 버튼도 workspace 안이라 열린 동안 함께 비활성 → 닫기는 사이드바 `새 대화`·Escape·scrim.
  - 닫을 때 포커스 복구: 닫기와 동시에 `focus()`하면 inert가 DOM에 남아 있어 실패하므로 `closeMenu`·`startNewChat`에서 `flushSync`로 inert를 먼저 해제한 뒤 메뉴 버튼/입력창에 포커스.
  - 리사이즈: 메뉴가 열린 동안 `matchMedia('(max-width: 760px)')` change에서 모바일 폭을 벗어나면 메뉴를 닫아 workspace inert가 남지 않게 함(jsdom처럼 `matchMedia`가 없으면 생략).
- Low — 답변이 여러 개면 `SourceList`의 `id="references-title"`이 중복. `useId()`로 인스턴스별 id, `aria-labelledby`도 같은 id.
- [x] RED: 테스트 3건 추가 후 `Tests 3 failed | 10 passed (13)` — inert 미적용(`expected false to be true`), 리사이즈 후 메뉴 열림 유지(`expected 'true' to be 'false'`), 출처 제목 id 1종(`expected 1 to be 2`). (리사이즈 테스트는 처음에 jsdom에 `matchMedia`가 없어 `vi.spyOn`이 실패해 `vi.stubGlobal`로 고친 뒤 위 RED 확인.)
- [x] GREEN: `npm test -- --run` → `Tests 13 passed (13)`. `npm run build` 성공.
- [x] 브라우저(Vite dev `127.0.0.1:5179`, 시스템 Chrome + Playwright, 390×844):
  - 메뉴 열림: workspace `inert=true`, 포커스 `새 대화`. Tab 6회: `새 대화 → BODY(브라우저 영역) → 새 대화 → 새 대화 → BODY → 새 대화 → 새 대화`, Shift+Tab → BODY. workspace 요소로 이동 0회.
  - Escape → inert false, 포커스 `메뉴 열기`. scrim 클릭 → inert false, 포커스 `메뉴 열기`. `새 대화` 클릭 → inert false, 포커스 `질문 입력`.
  - 메뉴 연 채 1280×800으로 리사이즈 → inert false, `aria-expanded=false`, 입력창 클릭·포커스 정상.
  - jsdom은 `inert`의 포커스 차단을 구현하지 않아, 닫을 때 포커스 복구(flushSync 필요성)는 브라우저 확인으로만 검증됨. 수정 전 상태의 브라우저 누수 재현(RED)은 별도로 캡처하지 않음(Codex 리뷰 보고 기준).
- [x] `./gradlew test`(DB 차단 `127.0.0.1:1/blocked`·Flyway false·isolated 태그 제외 확인) → exit 0, 204 tests / 0 failures / 0 errors.
- [x] `git diff --check` exit 0, 신규(미추적) 파일은 `git diff --no-index --check`로 별도 확인 — 공백 문제 없음.
- [x] Stop Hook — 변경이 `frontend/` TS·`docs/`뿐이라 마커 생성 패턴(Java/Gradle)에 해당하지 않고 `.claude/.code_changed`도 없음 → 스크립트상 즉시 통과(컴파일·테스트 미실행). 이 세션에서 Hook 출력 자체는 관찰되지 않음.
- 변경 파일(이번 수정): `frontend/src/App.tsx`, `frontend/src/App.test.tsx`, `frontend/src/components/SourceList.tsx`, 본 체크리스트. `styles.css`·의존성 미변경.
- [ ] 스크린리더·Safari·실기기 — 미실행.
