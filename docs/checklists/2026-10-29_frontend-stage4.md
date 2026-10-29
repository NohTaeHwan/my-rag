# 작업 체크리스트 — 프런트엔드 4단계(CI·패키징·검증·문서화)

> 공통 서식: `docs/claude_kit_development_checklist.md`
> 기준: `.hermes/plan_part1/2026-10-29_frontend_stage4_release_agent_brief.md`, `.hermes/plan_part1/2026-10-29_frontend-chat-ui-implementation-plan.md` §3 단계 4

## 작업 정보

- 작업명: CI에 Node 22 프런트엔드 게이트 추가, JAR·Docker 패키징 증거 확보, README·설계 문서 갱신, 배포 위험 기록
- 작업 일자: 2026-10-29
- 담당: Claude Code(단독 편집, Orca 배정)

## 1. 작업 시작 전

- [x] 브리프 전체, 계획 §3 단계 4, `CLAUDE.md`를 읽었다. 시작 시 작업 트리 clean(`a0cd4d9`, 2·3단계 커밋됨).
- [x] 추적 대상 확인: `ci.yml`, `cd.yml`, `Dockerfile`, `.dockerignore`, `build.gradle`(frontendBuild/bootJar/bootRun), `deploy/dgx-spark/deploy.sh`, `deploy/dgx-spark/docker-compose.app.yml`, `StaticFrontendRoutingTest`.
- [x] 신규/변경 API 없음 → `spring-api-*` Skill 비해당. README API 목록과 `_last update`(2026-09-08)는 변경하지 않았다.

## 2. 설계·구현

- `.github/workflows/ci.yml`: `test` job에 `actions/setup-node@v4`(Node 22, npm 캐시 키 `frontend/package-lock.json`)와 `frontend/`의 `npm ci` → `npm test -- --run` → `npm run build` 단계를 Gradle 단계 앞에 추가했다.
  - 기존 Java 17, wrapper 검증, `./gradlew clean test`, PostgreSQL 서비스, `build`의 `needs: test`·`if: push && main`·ARM64·GHCR 태그는 변경하지 않았다.
  - 프런트엔드 단계가 실패하면 `test` job이 실패하고, `build`(이미지 발행)는 `needs: test`로 실행되지 않는다. PyYAML로 파싱해 `build.needs == 'test'`와 step 순서를 확인했다.
  - Gradle `test`의 DB 차단(`build.gradle:168-169`)·isolated 태그 제외(`:93`)는 그대로다.
- `README.md`:
  - 소개에 `/` 웹 화면 한 줄을 추가했다.
  - 기술 스택에 Web UI 행을, 사전 준비에 Node 22(웹 빌드·`bootJar`·`bootRun`에만 필요)를 추가했다.
  - 테스트 절에 프런트엔드 명령을 추가했다.
  - "웹 채팅 UI" 절을 신설했다: 기능, 한계, `npm run dev`의 `/api` → 8080 프록시와 `ECONNREFUSED`, 패키징·fixture 테스트 구분. 한계는 인증 없음, 개발용 고정 사용자, KB ID 1, 출처 메타데이터만, 다중 질문 ≠ 문맥, 클라이언트 취소 ≠ 서버 취소다.
  - 배포 절: Dockerfile 3단계 설명을 고치고 CI 프런트엔드 게이트를 반영했다. `main` push → CI → 이미지 발행 → CD `prd-spark` 사람 승인 흐름을 적었다.
- `docs/personal_rag_chatbot_design.md`: 상단에 "2026-10-29 구현 상태 메모(설계 변경 아님)" 한 단락만 추가했다. Phase 0 설계와 현재 최소 UI 구현을 구분하고, 업로드·KB 선택·대화 저장은 미구현이라고 적었다.
- (초기 점검·최초 변경 시점) `build.gradle`·`Dockerfile`·`.dockerignore`·`cd.yml`·deploy 파일·Java 소스·`StaticFrontendRoutingTest`는 변경하지 않았다. 당시 추적 결과로는 최초 변경 범위에서 수정이 필요하지 않다고 판단했다.
  - **후속 최종 변경**: 이후 태환님 승인과 Codex 리뷰에 따라 §7에서 `.github/workflows/cd.yml`·`deploy/dgx-spark/deploy.sh`(SHA 고정, 신규 `deploy_test.sh`)를, §8에서 `cd.yml` concurrency를 변경했다.
  - 최종까지 미변경: `build.gradle`·`Dockerfile`·`.dockerignore`·Java 소스·`StaticFrontendRoutingTest`·`deploy/dgx-spark/docker-compose.app.yml`.
- [x] Java Controller/Service/예외/로그 항목 — 비해당(Java 변경 없음).
- [x] 민감 정보 — CI에 새 secret 없음. 번들에 자격 증명 없음. `.env` 미열람.

## 3. 테스트·검증 (변경 후 한 번에 실행)

- [x] `cd frontend && npm ci`(Node v22.23.1, 0 vulnerabilities) → `npm test -- --run` → `Test Files 2 passed`, `Tests 47 passed (47)` → `npm run build` 성공(`index-BUEkIDb4.js`, `index-DtSk6AKN.css`).
- [x] `./gradlew clean test`(실행 전 `127.0.0.1:1/blocked`·Flyway false·isolated 제외 확인) → exit 0, 204 tests / 0 skipped / 0 failures / 0 errors. 실DB 미사용.
- [x] `./gradlew bootJar` → `frontendBuild UP-TO-DATE`(직전 npm build와 소스 동일), BUILD SUCCESSFUL.
  - JAR에 `BOOT-INF/classes/static/index.html`, `assets/index-BUEkIDb4.js`, `assets/index-DtSk6AKN.css`가 있다.
  - index.html의 `src`·`href`가 이 두 파일을 가리키고, JAR의 index.html은 `frontend/dist/index.html`과 동일하다.
- [x] `docker build -t my-rag:frontend-stage4-check .` 성공(로컬 arm64, 로컬 태그만, push 없음).
  - npm은 frontend 단계에서만 실행됐고, Java 단계는 `bootJar -PprebuiltFrontendDist=frontend/dist`였다.
  - 이미지에서 꺼낸 `app.jar`에 같은 해시 파일이 있고, index.html이 로컬 dist와 같다.
  - 이미지 실행은 실DB 접속 위험 때문에 하지 않았다.
- [x] 라우팅 공존: `StaticFrontendRoutingTest`(fixture)가 `/`, `/health`, `POST /api/answers` 400, Swagger 비충돌을 위 Gradle test에서 검증한다. 실 번들 포함은 위 JAR·이미지 검사로 별도 확인했다. 패키징 JAR·이미지 실행 상태의 `/`·`/health` 응답은 이번 단계에서 확인하지 않았다(1단계에서 차단 DB로 1회 확인한 기록만 있다).
- [x] 브라우저(Vite dev `127.0.0.1:5179`, 시스템 Chrome + Playwright, `page.route`로 `/api/answers`만 모의):
  - 데스크톱 1280×800·모바일 390×844 공통: 로딩 표시와 전송 비활성, 답변과 출처 2건, 비밀 경로 미표시, `<script>` 삽입 0, 가로 넘침 0.
  - 500 고정 안내 alert, 질문 입력창 복원.
  - 문서 높이 = 창 높이, 입력창 화면 안.
  - 새 대화 후 늦은 응답 미표시.
  - Vite 로그 proxy/ECONNREFUSED 0건 → 백엔드·실서버 요청 없음.
  - 이 결과는 dev 서버 화면 기준이며 JAR·이미지 화면이 아니다.
- [x] `git diff --check` exit 0.
- [ ] GitHub Actions 실제 실행 — push 전이라 **미검증**. setup-node 캐시 키, ubuntu-latest의 npm ci 결과는 실행해야 확인된다.
- [ ] Spark 배포·`/` 실서비스 응답·Required reviewer 설정 — **미검증**(태환님 소유).
- [x] Stop Hook — 변경이 YAML·Markdown뿐이라 마커 패턴(Java/Gradle)에 해당하지 않고 `.claude/.code_changed` 없음 → 스크립트상 즉시 통과. Hook 출력은 **미관찰**.

## 4. 배포 관련 위험 — 초기 점검 결과(최초 변경에서는 수정하지 않음, 일부는 후속 §7-8에서 수정)

| 심각도 | 위험 | 근거 |
|---|---|---|
| ~~Medium~~ → 7절에서 수정 | CD가 CI가 만든 SHA가 아니라 `latest`를 배포한다. `main`에 연속 push하면 앞선 push의 CD 승인으로 뒤 push 이미지가 배포되거나, 승인 시점에 따라 승인자가 본 커밋과 다른 이미지가 올라갈 수 있다. CI의 `cancel-in-progress`로 앞선 run이 취소되면 해당 CD는 트리거되지 않는다. | `cd.yml:24-33`(workflow_run, head_sha 미사용), `deploy/dgx-spark/deploy.sh:16,24`, `deploy/dgx-spark/docker-compose.app.yml:14`, `ci.yml:12-14,79-81` |
| ~~Low~~ → 7절에서 수정 | 롤백은 Spark 로컬에서 `my-rag:previous`를 `latest`로 다시 태그한다. 다음 `pull`이 이를 덮으므로 롤백 상태가 레지스트리와 어긋난다. | `deploy.sh:18-22,34-37` |
| Low | 배포 후 확인은 `/health`만 본다. 정적 화면 `/`가 실제로 제공되는지는 배포 파이프라인이 확인하지 않는다. | `cd.yml:37,65-68`, `deploy.sh:30` |
| 사람 판단 | 인증 없는 `/`와 `/api/answers`가 같은 포트(`network_mode: host`, 8090)로 제공된다. 접근 가능한 네트워크 범위(Tailscale 한정 여부)에 따라 누구나 질문해 LLM 자원을 쓸 수 있다. 기존 API 노출과 같은 성격이지만 화면이 생겨 사용이 쉬워졌다. | `docker-compose.app.yml:16`, `cd.yml:37` |
| ~~문서 불일치~~ → 9절에서 정정 | (초기 점검 당시) 배포 문서와 README에는 "Spark 제한 계정·V3 미적용, 배포하지 않는다"는 경고가 있어 2026-10-29 사용자 확인 "Spark V4 적용"과 모순됐다. | `docs/dgx_spark_mvp_deployment.md:3`, `README.md:80` |

- (초기 점검 당시 판단, 연혁 보존) 위 Medium은 최종 배포 차단 사유로 단정하지 않았다. 사람 승인 단계가 있어 승인자가 연속 push를 피하면 회피할 수 있다고 봤다. 고치려면 CD가 `${{ github.event.workflow_run.head_sha }}` 태그를 deploy.sh에 넘기도록 바꿔야 하는데, 이는 CD·deploy 변경이라 최초 변경 범위 밖이었고 사용자 승인이 필요했다.
- **후속 결과**:
  - 태환님 승인으로 §7에서 Medium(latest 배포)과 Low(롤백 재태깅)를 수정했다.
  - 이후 Codex가 지적한 CD 동시 실행 경쟁은 §8에서 직렬화했다.
  - 위 표의 `/health`만 확인(Low), 인증 없는 노출(사람 판단)은 최종까지 **미해결**이다. 문서 불일치는 §9에서 사용자 확인 사실 기준으로 정정했다.
  - §7·§8의 "남은 위험"도 함께 검수 대상이다.

## 5. 문서·사람 검수

- [x] README: 설명 절만 수정했고, API 목록과 `_last update`는 미변경이다.
- [x] DB·API 명세 변경 없음.
- [x] 2·3단계 체크리스트·코드 보존. 최초 변경 diff는 ci.yml·README·설계 문서·본 체크리스트였고, 후속 §7-8을 포함한 최종 diff는 §6 목록과 같다.
- [ ] 사람 검수(최종 변경 기준):
  - CI 프런트엔드 게이트(이미지 발행 조건).
  - CD SHA 고정·신뢰 조건·fail-closed 검증(§7).
  - deploy.sh override·롤백(§7).
  - CD concurrency와 대기 run 취소·오래된 SHA 승인 위험(§8).
  - §4 미해결 위험과 §7·§8 남은 위험.
  - README·배포 문서·설계 문서 문구.
  - commit·push·CD 승인은 태환님이 결정한다.

## 6. 완료 기록

- 최종 변경 파일(후속 §7-8 포함):
  - `.github/workflows/ci.yml`(최초)
  - `.github/workflows/cd.yml`(§7 SHA 고정, §8 concurrency)
  - `deploy/dgx-spark/deploy.sh`(§7)
  - `deploy/dgx-spark/deploy_test.sh`(§7 신규)
  - `README.md`(최초 + §7·§8 배포 절)
  - `docs/dgx_spark_mvp_deployment.md`(§7·§8)
  - `docs/personal_rag_chatbot_design.md`(최초)
  - `docs/checklists/2026-10-29_frontend-stage4.md`(신규)
- 최종 미검증:
  - GitHub Actions CI·CD 실제 실행: Node 게이트, `workflow_run` 필드, 익명 GHCR manifest 조회, concurrency·승인 대기 상호작용.
  - Spark 실제 배포·override·롤백, Spark Compose 서비스명 `my-rag-app` 일치 여부.
  - 패키징 JAR·이미지 실행 상태의 `/` 응답.
  - Stop Hook 출력(마커 비해당, 미관찰).
- 로컬 산출물: Docker 이미지 `my-rag:frontend-stage4-check`(로컬 전용, 정리 여부는 태환님 결정). 이전 단계의 `my-rag:frontend-stage1-check`도 남아 있을 수 있다.

## 7. 후속 — 배포 이미지 SHA 고정(태환님 승인, 2026-10-29)

> 기준: `.hermes/plan_part1/2026-10-29_frontend_stage4_sha_deploy_agent_brief.md`

### 변경

- `.github/workflows/cd.yml`
  - `workflow_dispatch`에 `sha` 입력 필수(`:29-34`).
  - 자동 실행 조건을 CI 성공 + `workflow_run.event == 'push'` + `head_branch == 'main'` + `head_repository.full_name == github.repository`로 좁혔다(`:44-49`).
  - `Resolve deploy SHA`(`:57-69`): 자동은 `workflow_run.head_sha`, 수동은 입력값. 값은 env로만 받고 `^[0-9a-f]{40}$`가 아니면 실패한다. latest·기본 브랜치 SHA fallback 없음.
  - checkout을 그 SHA로 고정(`ref`, `fetch-depth: 0`).
  - `Verify checkout and image`(`:76-84`), Tailscale·SSH 이전에 실행:
    - HEAD == SHA인지 확인한다.
    - `origin/main` 이력에 있는 커밋인지 확인한다.
    - 그 커밋의 deploy.sh가 `DEPLOY_SHA`를 지원하는지 확인한다. 이전 커밋의 deploy.sh는 latest를 배포하므로 거부한다.
    - `docker manifest inspect ghcr.io/nohtaehwan/my-rag:<SHA>`로 이미지 존재를 확인한다.
  - SSH 실행 시 `DEPLOY_SHA='$SHA'`를 전달한다(`:110`, 검증된 hex라 인용이 안전하다).
  - `prd-spark` reviewer gate(`:51`), Tailscale, SSH 포트·secret, health check는 그대로다.
- `deploy/dgx-spark/deploy.sh`
  - `DEPLOY_SHA` 필수, 소문자 40자리 hex 검증(`:20-24`) → 실패 시 docker 호출 전에 종료한다.
  - 이미지는 `ghcr.io/nohtaehwan/my-rag:<SHA>`(`:27`). Spark의 `compose/docker-compose.app.yml`은 수정하지 않는다. `mktemp` 임시 override에 `my-rag-app.image` 한 항목만 써서 `-f compose -f override`로 `pull`·`up`한다(`:28-46`). override는 `trap`으로 삭제된다.
  - pull이 실패하면 `set -e`로 `up` 전에 중단한다.
  - 롤백: 직전 실행 이미지를 로컬 `my-rag:previous`로 태그해 두었다가, health 실패 시 override image를 `my-rag:previous`로 바꿔 재기동한다(`:54-56`). SHA·latest 태그는 재태깅하지 않는다.
- `deploy/dgx-spark/deploy_test.sh`(신규): 가짜 `docker`·`curl`·`sleep`을 PATH 앞에 둔 로컬 경계 테스트. 실제 docker·Spark·GHCR 미접속.
- `README.md` 배포 절, `docs/dgx_spark_mvp_deployment.md` 설계·개선 항목: SHA 이미지 배포, 수동 실행 `sha` 입력, fail-closed 조건, override 방식과 "override 없이 수동 `docker compose up` 시 latest로 바뀜" 경고를 적었다. API 목록·`_last update`는 미변경. 배포 문서 상단 V3 경고는 §7 최초 변경 당시에는 미변경이었고, 이후 §9에서 사용자 확인 V4 적용 상태로 정정했다.
- `ci.yml`의 이미지 태그(`latest`, `:<SHA>`) 발행은 그대로다. Spark Compose 파일 예시(`docker-compose.app.yml`)도 그대로다.

### 검증

- [x] RED: `deploy_test.sh`를 수정 전 deploy.sh에 실행 → 다수 FAIL.
  - SHA 미검증: 빈 값·`latest`·짧은 값·대문자·`;rm`·41자리·`../`가 모두 배포 성공.
  - `latest` pull, override 없음.
  - 롤백 시 `docker tag my-rag:previous …:latest` 재태깅.
  - 하네스 자체 수정 2건: macOS bash 3.2의 `${SHA^^}` 미지원, 케이스별 판정 로직.
- [x] GREEN: `bash deploy/dgx-spark/deploy_test.sh` → 13건 ok, `ALL PASSED`.
  - 정상 SHA: previous 태그 → `pull my-rag-app` → override image = `ghcr.io/nohtaehwan/my-rag:<SHA>`, `:latest` 없음.
  - SHA 거부 7종 + 누락: docker 호출 0.
  - pull 실패: `up` 0회.
  - health 실패: override `my-rag:previous`로 두 번째 `up`, `docker tag my-rag:previous` 없음.
  - health 실패 + previous 없음: `up` 1회 후 실패.
  - override 임시 파일 정리.
- [x] `docker compose -f <Spark Compose 사본> -f <override> config`(렌더만, 실행 없음, 더미 env 파일): image만 SHA로 바뀌고 `network_mode: host`, `restart`, `data/markdown` 읽기 전용 볼륨이 유지된다.
- [x] cd.yml `Resolve deploy SHA` run 블록을 로컬 bash로 실행:
  - 자동(head_sha) → 그 SHA.
  - 수동(입력) → 입력 SHA. run SHA가 달라도 입력값을 쓴다.
  - 수동 빈 값·`latest`·`'<SHA>' ;id #'`·자동 빈 값 → exit 1, output 없음.
- [x] 이전 커밋 거부 가드: `HEAD`(수정 전) deploy.sh는 `grep DEPLOY_SHA` 실패로 거부, 현재 파일은 통과.
- [x] `bash -n`(deploy.sh, deploy_test.sh) ok.
- [x] shellcheck(actionlint 이미지 내장, 로컬 컨테이너): 두 스크립트 exit 0. info 2건(SC2016 의도적, SC2015 → `if`로 수정)을 정리했다.
- [x] `actionlint 1.7.7`(로컬 docker, `rhysd/actionlint:1.7.7`): `cd.yml`·`ci.yml` exit 0, 지적 0건(run 블록 shellcheck 포함).
- [x] `npm ci`(0 vulnerabilities) → `npm test -- --run` 47 passed → `npm run build` 성공.
- [x] `./gradlew test`(실행 전 `127.0.0.1:1/blocked`·Flyway false·isolated 제외 확인) → exit 0, 204 tests / 0 failures / 0 errors.
- [x] `./gradlew bootJar` → UP-TO-DATE, JAR에 `static/index.html`·`index-BUEkIDb4.js`·`index-DtSk6AKN.css`.
- [x] `git diff --check` exit 0. 미추적 파일(`deploy_test.sh`, 본 체크리스트)도 `--no-index --check` 통과.
- [ ] Docker 이미지 재빌드 — Dockerfile 미변경이라 이번 후속에서는 미실행(3절 결과 유효).
- [ ] GitHub Actions CI/CD 실제 실행 — **미검증**. `workflow_run.event`·`head_repository` 필드 값, ubuntu-latest의 익명 `docker manifest inspect`(GHCR public 전제), 승인 대기 중 checkout 동작을 실행으로 확인하지 못했다.
- [ ] Spark 실제 배포·override 적용·롤백 — **미검증**. Spark의 실제 Compose 파일이 저장소 예시와 같은 서비스명(`my-rag-app`)을 쓰는지 확인하지 않았다.
- [x] Stop Hook — 변경이 YAML·셸·Markdown뿐이라 마커 패턴(Java/Gradle)에 해당하지 않고 `.claude/.code_changed`도 없다. Hook 출력은 **미관찰**.

### 남은 위험·사람 검수 지점(다음 push/배포 전)

1. `.github/workflows/cd.yml:84` — GHCR 패키지가 public이라는 전제로 러너에서 익명 manifest 조회를 한다. 비공개라면 CD가 항상 이 단계에서 실패한다(fail closed). 이 경우 로그인 단계 추가 여부를 결정해야 한다.
2. `.github/workflows/cd.yml:44-49` — 자동 트리거 신뢰 조건(push·main·같은 저장소)이 의도와 맞는지 검토한다.
3. `deploy/dgx-spark/deploy.sh:32-46` — Spark의 실제 `compose/docker-compose.app.yml` 서비스명이 `my-rag-app`인지 배포 전에 확인한다. 다르면 override가 새 서비스를 만들려다 실패하거나 의도와 다르게 동작할 수 있다.
4. 운영 수칙: Spark에서 override 없이 `docker compose up`을 직접 실행하면 Compose 파일의 `latest`로 돌아간다(README·배포 문서에 경고). Spark Compose 파일 자체를 SHA로 바꾸는 것은 이번 범위 밖이다.
5. ~~CD 동시성 제어 없음~~ → 8절에서 직렬화를 추가했다. 대기 run 교체와 오래된 SHA 승인 위험은 8절의 남은 위험을 참고.
6. 첫 적용: 이 변경이 push된 뒤 첫 자동 CD부터 SHA 고정이 적용된다. 그 이전 커밋 SHA를 수동 실행하면 4번째 검증에서 거부된다(의도).
7. ~~배포 문서 V3 경고와 사용자 확인(V4) 불일치~~ → §9에서 정정했다.

- 변경 파일(이번 후속): `.github/workflows/cd.yml`, `deploy/dgx-spark/deploy.sh`, `deploy/dgx-spark/deploy_test.sh`(신규), `README.md`, `docs/dgx_spark_mvp_deployment.md`, 본 체크리스트.

## 8. Codex 리뷰 Medium — CD 동시 실행 직렬화(2026-10-29)

- 지적: deploy job에 concurrency가 없어 두 CD가 겹치면 서로 다른 SHA의 배포·롤백(`my-rag:previous` 태그, compose up)이 경쟁한다.
- 수정(`.github/workflows/cd.yml:52-56`): deploy job에 `concurrency: { group: cd-prd-spark, cancel-in-progress: false }`를 추가했다. 기존 SHA 고정 로직, `if` 신뢰 조건, `environment: prd-spark`, Tailscale·SSH 단계는 변경 없다. workflow 단위가 아니라 job 단위라서 `if`로 스킵되는 run(예: PR CI)은 그룹을 점유하지 않는다.

### 실제로 해결되는 것

- 같은 그룹의 deploy job은 동시에 하나만 실행된다. 진행 중인 deploy.sh·롤백은 취소되지 않고 끝까지 실행된다(`cancel-in-progress: false`) → Spark에서 두 배포의 pull/up/롤백이 겹치지 않는다.

### 남은 위험(GitHub 동작에 따른 한계, 해결 안 됨)

1. **대기 1개 제한**: GitHub concurrency는 그룹당 실행 1개 + 대기 1개만 유지한다. 대기 중에 새 run이 들어오면 이전 대기 run은 취소된다.
   - 연속 push 시 중간 SHA는 배포되지 않을 수 있다(최신 SHA가 남는 쪽이라 대체로 의도와 맞음).
   - 반대로 나중에 들어온 **오래된 SHA의 수동 실행**이 대기 중인 최신 SHA run을 취소시킬 수 있다.
2. **오래된 SHA 승인**: 승인 대기 run의 SHA가 현재 배포보다 오래됐다면 승인 시 그 버전으로 되돌아간다. 수동 롤백 용도로는 의도된 동작이지만, 자동 run을 늦게 승인하면 의도치 않은 되돌림이 될 수 있다. 승인자가 run의 SHA(Resolve deploy SHA 단계 출력)를 확인해야 한다. 자동 차단은 하지 않았다.
3. **승인 대기와 concurrency의 상호작용 미검증**: environment 승인 대기 중인 job이 그룹의 "실행" 슬롯을 차지하는지, 대기 슬롯에 있는지를 실제 Actions에서 확인하지 않았다. 차지한다면 승인되지 않은 run이 뒤 run을 계속 대기시킨다.
4. Spark에서 수동으로 실행한 `deploy.sh`나 `docker compose`는 GitHub concurrency 밖이라 직렬화되지 않는다.

### 검증

- [x] `actionlint 1.7.7`(로컬 docker): `cd.yml`·`ci.yml` exit 0.
- [x] PyYAML 파싱: deploy job `concurrency = {group: cd-prd-spark, cancel-in-progress: false}`, `environment: prd-spark`, `if`의 `head_repository` 조건 유지, step 8개 유지.
- [x] `bash deploy/dgx-spark/deploy_test.sh` → `ALL PASSED`(deploy.sh 미변경 회귀 확인).
- [x] `git diff --check` exit 0.
- [ ] GitHub Actions에서 동시 CD·대기 취소·승인 대기 동작 — **미검증**(push·실행 금지).
- [ ] npm·Gradle·bootJar — 이번 변경은 YAML·Markdown뿐이라 재실행하지 않았다(7절 결과 유효).
- [x] Stop Hook — 마커 패턴 비해당(`.claude/.code_changed` 없음). Hook 출력 **미관찰**.
- 변경 파일: `.github/workflows/cd.yml`, `README.md`(배포 절), `docs/dgx_spark_mvp_deployment.md`(개선 항목), 본 체크리스트.

## 9. 후속 — Spark V4 적용 상태 문서 정정(2026-10-29)

> 기준: `.hermes/plan_part1/2026-10-29_spark_v4_document_status_brief.md`

- 근거: 태환님이 Spark DB에 V4가 적용되어 있다고 직접 재확인했다(사용자 확인 사실). 이전에 이를 반영하지 않고 V3 미적용 경고를 유지한 것을 바로잡는다.
- 변경:
  - `docs/dgx_spark_mvp_deployment.md` 상단: "제한 계정 생성·V3 적용은 아직 하지 않았다 / 빌드·배포하지 않는다" 문구를 지우고, V4 적용(태환님 확인)과 미확인 항목을 구분한 정정 메모를 추가했다. 2026-09-10 배포 이력은 그대로 뒀다.
  - `README.md` 환경 변수 절: "제한 계정·환경변수 미준비, 배포하지 않는다" 문장을 V4 적용(사용자 확인)과 배포 전 별도 확인 항목으로 바꿨다. API 목록·`_last update`는 미변경.
  - 본 체크리스트 §4 표·후속 결과, §7 남은 위험 7번의 "미해결" 기록을 정정했다.
- 미검증 경계(추정하지 않음):
  - DB에 직접 접속해 Flyway 이력·제약을 확인하지 않았다. V4 적용은 사용자 확인 사실로만 기록한다.
  - Spark 제한 계정·앱 환경변수 준비 여부 — **미확인**.
  - 최근 CI/CD 변경(§2·§7·§8)의 GitHub Actions·Spark 실제 실행 — **미실행**.
- [x] `git diff --check` exit 0, 미추적 본 체크리스트 `--no-index --check` 통과.
- [x] 세 파일에서 "V3 미적용/배포하지 않는다" 류 현재형 경고 재검사 — 남은 것은 연혁 인용뿐이다.
- DB·Spark·GitHub·테스트 실행, 커밋·푸시 없음.
