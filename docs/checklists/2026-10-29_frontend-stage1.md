# 작업 체크리스트 — 프런트엔드 1단계(프로젝트 기반·정적 배포 경계)

> 공통 서식: `docs/claude_kit_development_checklist.md`
> 기준: `.hermes/plan_part1/2026-10-29_frontend_stage1_agent_brief.md`, `.hermes/plan_part1/2026-10-29_frontend-chat-ui-implementation-plan.md` §3 단계 1

## 작업 정보

- 작업명: `frontend/` React + TypeScript + Vite 최소 화면, Spring Boot `/` 정적 제공, Docker 이미지 포함
- 작업 일자: 2026-10-29
- 담당: Claude Code(단독 편집, Orca 배정)

## 1. 작업 시작 전

- [x] 범위 정리 — 포함: 최소 화면·단위 테스트, `bootJar` 정적 포함, Dockerfile Node 단계, ignore 보완. 제외: 채팅 디자인(2단계), 답변 API 연결(3단계), CI·README(4단계), DB·배포·commit/push.
- [x] `CLAUDE.md`, 브리프, 기준 계획, 본 체크리스트 서식을 읽었다.
- [x] 기존 구조 확인 — `Dockerfile`(Java만 패키징), `.dockerignore`, `build.gradle` test 차단 guard, 빈 `src/main/resources/static/`(미추적).
- [x] 신규/변경 API 없음 → `spring-api-*` Skill 비해당.

## 2. 설계·구현

- `frontend/`: `package.json`·`package-lock.json`(react/react-dom 19, vite 8, @vitejs/plugin-react 6, typescript 5.9, vitest 5, @testing-library/react 16, jsdom), `index.html`, `tsconfig.json`, `vite.config.ts`(개발 전용 `/api` → `http://127.0.0.1:8080` 프록시, vitest jsdom), `src/main.tsx`, `src/App.tsx`(제목·`기본 지식 공간`·준비 중 안내만), `src/styles.css`(시스템 폰트, 외부 자산 없음), `src/App.test.tsx`, `.gitignore`(`node_modules/`, `dist/`).
- `build.gradle`: `processResources`가 `frontend/dist`를 `static/`으로 포함. Gradle은 npm을 실행하지 않음(dist가 없으면 정적 화면 없이 빌드 — 기존 CI Gradle 테스트에 Node 의존성 추가 없음). 생성물을 `src/main/resources/static/`에 복사·추적하지 않음.
- `Dockerfile`: `node:22-alpine` 빌드 단계(`npm ci` → `npm run build`) 추가, `dist`만 Java 빌드 단계로 복사, 런타임은 기존 `eclipse-temurin:17-jre` 하나.
- `.dockerignore`: `frontend/node_modules`, `frontend/dist`, `postgres_data`, `data`, `perf`, `**/.env`, `**/.env.*` 추가(Dockerfile이 COPY하지 않는 경로만 — 빌드 컨텍스트 18.47kB로 확인).
- [x] Java 서비스·Controller·예외·로그 항목은 변경 없음(비해당).
- [x] 민감 정보 — 번들에 자격 증명·사용자 ID·원문 경로 없음(화면 문구 3줄뿐).

## 3. 테스트·검증

- [x] 프런트엔드 RED→GREEN: `App.test.tsx` 선작성 → `./App` 미존재로 실패 → 구현 후 `Tests 1 passed`.
- [x] `cd frontend && npm ci`(0 vulnerabilities) → `npm test -- --run`(1 passed) → `npm run build`(`tsc` 타입 검사 + vite build 성공).
- [x] `StaticFrontendRoutingTest`(신규, `@SpringBootTest`+MockMvc, `src/test/resources/static/index.html` fixture, HealthMapper mock): `/` → `index.html` forward, `/index.html` 200, `POST /api/answers {}` → 400·`AnswerController` 처리, `/health` → `HealthController` 처리, `/swagger-ui.html` 3xx. Java 테스트는 fixture와 함께 작성해 별도 RED 증거 없음.
- [x] `./gradlew test` — 실행 전 `build.gradle` test task의 DB 차단(`127.0.0.1:1/blocked`)·Flyway 비활성·`isolated-*` tag 제외 확인. `--tests StaticFrontendRoutingTest` 및 전체 모두 BUILD SUCCESSFUL. (테스트 건수는 콘솔에 나오지 않아 별도 집계하지 않음.)
- [x] `./gradlew bootJar` → JAR에 `BOOT-INF/classes/static/index.html`, `static/assets/*.js|css` 포함(실제 화면, fixture 아님 확인).
- [x] `docker build -t my-rag:frontend-stage1-check .` 성공(로컬 arm64). 이미지에서 꺼낸 `app.jar`에도 static 파일 포함 확인.
- [x] 이미지 실행 검증 — DB 차단 URL(`127.0.0.1:1`)·더미 비밀번호·Flyway 비활성·`.env` 미사용, `127.0.0.1:18091`에 바인딩 후 종료: `/` 200(`<title>my-rag</title>`), 정적 JS 200, `/swagger-ui.html` 302, `/v3/api-docs` 200, `POST /api/answers {}` 400, `/health` 503 DOWN(DB 차단으로 예상된 결과).
- [ ] CI(GitHub Actions ARM64 러너)에서의 Node 단계 동작 — 로컬 arm64 빌드만 확인, CI는 미실행.
- [ ] 브라우저 수동 확인(데스크톱/390px) — 2단계 화면 구현 이후 대상, 미실행.
- [ ] 격리 DB 통합 테스트·`bootRun` — 범위 밖, 미실행.
- [ ] claude-kit Stop Hook — 턴 종료 시 실행되므로 이 기록 시점 미관찰.

## 4. 문서·사람 검수

- [x] README — 새 엔드포인트 없음, API 목록·`_last update` 미변경(프런트엔드 실행 안내는 4단계).
- [x] DB 변경 없음.
- [ ] 사람 검수 — Dockerfile·`.dockerignore`·`build.gradle` 패키징 변경, 로컬 이미지 `my-rag:frontend-stage1-check` 정리 여부는 태환님 결정.

## 5. 완료 기록

- 변경 파일: `frontend/*`(신규), `build.gradle`, `Dockerfile`, `.dockerignore`, `src/test/java/com/nohtaehwan/rag/StaticFrontendRoutingTest.java`(신규), `src/test/resources/static/index.html`(신규), 본 체크리스트
- 남은 위험: ~~dist 없이 `./gradlew bootJar`하면 화면 없는 JAR가 조용히 만들어짐~~ → 아래 6절에서 해소. CI 워크플로에 프런트엔드 테스트 미포함(4단계).

## 6. 패키징 리뷰 후속 수정(Codex High·Medium·Low, 2026-10-29)

> 기준: `.hermes/plan_part1/2026-10-29_frontend_stage1_packaging_review_fix.md`

- 설계: Gradle은 계속 npm을 실행하지 않는다(`test`·Stop Hook·CI `clean test`에 Node 불필요, Docker Node 단계와 중복 없음). 대신 `build.gradle`의 `bootJar`에 검증을 붙였다.
  - High — `doFirst`: `frontend/dist/index.html` 없으면 실패.
  - Medium — `doFirst`: `frontend/src/**`, `index.html`, `package.json`, `package-lock.json`, `tsconfig.json`, `vite.config.ts` 중 `dist/index.html`보다 새 파일이 있으면 실패(mtime 기준). Docker Java 단계엔 소스가 없어 존재만 확인 — dist는 같은 빌드의 `node:22-alpine` 단계가 막 만든 것.
  - Low — `doLast`: 만든 JAR의 `BOOT-INF/classes/static/` 파일 목록이 `dist`와 정확히 같고, index.html 내용이 같고, `assets/*.js`·`assets/*.css`가 있는지 확인. 매 `bootJar`·`docker build`가 실제 번들 회귀 검증이 된다. `StaticFrontendRoutingTest` javadoc에 fixture(라우팅 전용)와 이 검증의 구분을 명시.
- 변경 파일: `build.gradle`, `src/test/java/com/nohtaehwan/rag/StaticFrontendRoutingTest.java`(javadoc만), 본 체크리스트. `Dockerfile`·`.dockerignore`·프런트엔드 소스·CI·README 미변경.
- [x] 변경 전 확인 — `test` task DB 차단(`127.0.0.1:1/blocked`)·Flyway 비활성·`isolated-*` 제외, Stop Hook(`compileJava compileTestJava` + 대응 테스트) 모두 그대로.
- [x] RED — `frontend/dist` 치운 뒤 `./gradlew clean bootJar` → exit 0, JAR엔 빈 `static/` 디렉터리뿐.
- [x] GREEN 재현 단계(누구나 반복 가능):
  1. dist 치우고 `./gradlew clean bootJar` → `bootJar: frontend/dist/index.html이 없다` 실패.
  2. `touch frontend/src/App.tsx` 후 `./gradlew bootJar` → `bootJar: frontend/dist가 소스보다 오래됐다(App.tsx)` 실패.
  3. `cd frontend && npm ci && npm test -- --run && npm run build` (1 passed, build 성공) → `./gradlew clean bootJar` 성공, JAR에 `static/index.html`·`assets/index-C0AVrfhA.js`·`assets/index-fojSHQoe.css`.
  4. dist에 임시 asset 추가 후 bootJar → 제거 후 clean 없이 bootJar → 잔여 asset 0건(Gradle이 stale 출력 제거, doLast 집합 비교 통과).
- [x] `./gradlew clean test`(DB 차단) — BUILD SUCCESSFUL, 204 tests / 0 failures / 0 errors. dist 없는 상태에서 `clean test --tests StaticFrontendRoutingTest`도 성공(Node·dist 불필요 확인).
- [x] `docker build -t my-rag:frontend-stage1-check .` 성공(로컬 arm64, bootJar 검증 포함). 이미지의 `app.jar`에 동일 static 파일 확인.
- [ ] CI(GitHub Actions)·이미지 실행·bootRun·격리 DB — 범위 밖, 미실행.
- [ ] Stop Hook — 턴 종료 시 실행, 이 기록 시점 미관찰.
- 한계: mtime 비교라 소스를 되돌리거나 파일을 삭제만 한 경우는 감지하지 못한다 → 7절에서 대체.

## 7. Codex 재리뷰 잔존 Medium 수정(2026-10-29)

- 지적: 6절의 doFirst/doLast는 `bootJar`가 UP-TO-DATE/FROM-CACHE면 건너뛰고, mtime 비교는 내용 되돌림·삭제·mtime 보존 변경을 놓친다.
- RED — 6절 상태에서 dist 빌드 후 `App.tsx` 내용을 바꾸고 mtime을 과거로 설정 → `> Task :bootJar UP-TO-DATE`, JAR JS에 변경 문자열 0건(오래된 번들 통과).
- 설계(6절 doFirst mtime 검사·dist 집합 비교 제거):
  - `processResources`에서 dist 포함 제거 → `test`·Stop Hook·CI `clean test`는 Node·dist 불필요. (이로써 `bootRun` `/` 화면이 사라지는 회귀 발생 → 8절에서 보수.)
  - `frontendBuild`(Exec, `npm ci && npm run build`): 입력 = `frontend/src/**`·`index.html`·`package.json`·`package-lock.json`·`tsconfig.json`·`vite.config.ts` 내용 해시(상대 경로), 출력 = `frontend/dist`. 내용 변경·삭제·되돌림·dist 수동 변경 모두 재실행 사유가 된다.
  - `bootJar`는 `bootInf { into('classes/static') { from(frontendBuild 출력) } }` → 번들 내용이 bootJar 입력이므로 번들이 바뀌면 UP-TO-DATE/FROM-CACHE가 될 수 없다.
  - Docker: Node 단계 dist를 `-PprebuiltFrontendDist=frontend/dist`로 넘겨 Java 단계에서 npm 미실행(중복 없음).
  - `bootJar` doLast: JAR의 `static/index.html`이 있고, 그 `src`/`href`가 가리키는 `/assets/*.js`·`*.css`가 모두 JAR에 있는지 확인. 번들 실행·렌더링 자체는 검증하지 않는다(2단계 이후 브라우저 확인 대상).
- 변경 파일: `build.gradle`, `Dockerfile`(bootJar 명령에 `-PprebuiltFrontendDist`·주석), `StaticFrontendRoutingTest.java`(javadoc), 본 체크리스트.
- [x] A. dist 없이 `./gradlew clean bootJar` → `frontendBuild` 실행 후 성공, JAR에 `static/index.html`·`assets/index-C0AVrfhA.js`·`assets/index-fojSHQoe.css`.
- [x] B. 첫 빌드 후 `App.tsx` 내용만 변경 + mtime 2026-01-01로 설정 → `frontendBuild`·`bootJar` 재실행, JAR JS에 변경 문자열 1건.
- [x] C. 원래 내용·원래 mtime으로 되돌림 → 재실행, 변경 문자열 0건·원래 문구 1건.
- [x] D. `src/styles.css` 삭제 → `frontendBuild` 재실행, `UNRESOLVED_IMPORT`로 BUILD FAILED(조용히 통과 안 함). 원복 후 E·F 변경 없음 → 둘 다 UP-TO-DATE.
- [x] G/H. `-PprebuiltFrontendDist`에 JS 빠진 dist → `bootJar: index.html의 JS/CSS 참조가 JAR와 맞지 않는다 … 누락 [assets/index-C0AVrfhA.js]` 실패, 없는 경로 → `bootJar: JAR에 static/index.html이 없다` 실패.
- [x] I. dist 없이 `./gradlew clean test`(DB 차단 guard 그대로: `127.0.0.1:1/blocked`, Flyway false, isolated 태그 제외) → 204 tests / 0 failures / 0 errors, `frontendBuild` 미실행.
- [x] `cd frontend && npm test -- --run` 1 passed.
- [x] J. `docker build -t my-rag:frontend-stage1-check .` 성공 — npm은 frontend 단계에서만, Java 단계는 `bootJar -PprebuiltFrontendDist=frontend/dist`. 이미지 `app.jar`에 같은 static 파일.
- [ ] build cache(`--build-cache`)는 프로젝트에서 켜지 않아 FROM-CACHE 경로를 직접 실행하지 않음 — 번들 내용이 bootJar 입력이라 캐시 키에 포함되는 구조로 대응.
- [ ] CI·이미지 실행·bootRun·격리 DB — 범위 밖, 미실행. Stop Hook — 턴 종료 시 실행, 기록 시점 미관찰.

## 8. Codex 재리뷰 Medium — bootRun `/` 화면 회귀 보수(2026-10-29)

- 지적: 7절에서 `processResources`의 dist 포함을 빼면서 `bootRun`의 `/`가 404가 됨(1단계 "Spring Boot `/` 제공" 조건과 충돌).
- 수정(`build.gradle`만): `bootRun`에 `dependsOn frontendBuild` + 시스템 프로퍼티 `spring.web.resources.static-locations=classpath:/static/,file:<repo>/frontend/dist/`. npm은 `bootRun`·`bootJar`에서만 실행되고, `test`·Stop Hook은 여전히 Node·dist 불필요. `bootJar` 내용-입력 연결·Docker `-PprebuiltFrontendDist`·`test` DB 차단은 변경 없음.
- 검증 방식(로컬 독립 포트, 실DB 미접속): `./gradlew bootRun --args='--server.port=18093 --server.address=127.0.0.1 --spring.datasource.url=jdbc:postgresql://127.0.0.1:1/blocked --spring.datasource.username=fake --spring.datasource.password=fake --spring.flyway.enabled=false'` → curl 확인 → 포트 LISTEN 프로세스에 TERM → 포트 해제 확인. (명령줄 인자가 `.env` 주입 환경변수보다 우선. `.env` 내용은 열람하지 않음.)
- [x] RED(수정 전): `/` → 404, `POST /api/answers {}` → 400.
- [x] GREEN(수정 후): `frontendBuild UP-TO-DATE` → `/` → 200(`<title>my-rag`), `/assets/index-C0AVrfhA.js` → 200 `text/javascript`, `/assets/index-fojSHQoe.css` → 200 `text/css`, `/api/answers {}` → 400. 정상 종료·포트 해제.
- [x] 소스 변경 반영: `App.tsx` 내용 변경 + mtime 과거 → `bootRun`이 `frontendBuild` 재실행, `/`가 새 번들(`index-CdCniHY_.js`) 참조, JS에 변경 문자열 1건. 이후 원래 내용·mtime 복원.
- [x] 회귀: `npm ci`·`npm test -- --run`(1 passed)·`npm run build` 성공 / dist 없이 `./gradlew clean test` 204 tests·0 failures·0 errors, `frontendBuild` 미실행·dist 미생성 / `./gradlew bootJar` → `frontendBuild` 실행, JAR에 index.html·JS·CSS / `docker build` 성공(npm은 frontend 단계만, Java 단계 `bootJar -PprebuiltFrontendDist=frontend/dist`), 이미지 `app.jar`에 같은 static 파일.
- [x] `test` DB 차단 guard(`127.0.0.1:1/blocked`, Flyway false, isolated 태그 제외) 그대로 확인.

### 남은 Low·미검증(수정하지 않음, 사실만 기록)

- Low 1 — `-PprebuiltFrontendDist`를 수동으로 임의 경로에 지정하면 형태(index.html·참조 JS/CSS 존재)만 맞으면 현재 소스보다 오래된 번들도 통과한다. 실제 Docker 경로는 같은 빌드의 Node 단계 산출물을 복사하므로 해당 없음. 수동 사용 시 신선도는 사용자 책임.
- Low 2 — 소스 내용을 바꾼 뒤 원래 크기·mtime으로 복원한 경우, Gradle 파일 지문 캐시(경로·크기·mtime 기반 해시 재사용 가능성)에 따라 변경 감지 여부가 확정되지 않았다. 7절 B·C(내용 변경 + mtime 과거, 원래 내용·원래 mtime 복원)는 재빌드를 확인했으나 "같은 크기 + 원래 mtime의 다른 내용"은 미실행.
- 미검증 — 번들 실행·렌더링 자체(브라우저 확인은 2단계 이후), `--build-cache` FROM-CACHE 경로(프로젝트에서 미사용), CI·이미지 실행·격리 DB.
- [ ] Stop Hook — 턴 종료 시 실행, 기록 시점 미관찰.
