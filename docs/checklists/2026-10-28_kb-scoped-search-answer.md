# 작업 체크리스트 — 기존 검색·답변 API의 사용자·Knowledge Base 범위 격리

> 공통 서식: `docs/claude_kit_development_checklist.md`
> 기준 문서: `.hermes/plan_part1/2026-10-28_185037-v4-existing-api-impact-orchestration-plan.md`

## 작업 정보

- 작업명: `GET /api/search`·`POST /api/answers` KB 범위 격리(기존 경로 유지, `knowledgeBaseId` 필수)
- 작업 일자: 2026-10-28
- 관련 문서: 위 계획서, `docs/personal_rag_chatbot_design.md`, `README.md`, `docs/rag_backend_developer.md` §9
- 담당: Claude Code(단독 구현, Orca 배정)

## 1. 작업 시작 전

- [x] 요청 범위와 완료 기준을 정리했다 — 포함: 검색·답변 KB 필수 입력, 개발 사용자 1 고정, 없는/타인 KB 동일 404, SQL 범위 필터, 테스트·명세·문서. 제외: 인증/인가, KB 목록·관리 API, 403 정책, KB 선택 색인, 응답 KB ID 추가, migration 변경.
- [x] 요구사항이 모호한 부분을 먼저 확인했다 — API 정책은 계획서 §3·게이트 A에서 승인됨. 추가 질문 없음.
- [x] `CLAUDE.md`를 읽었다.
- [x] 참고 문서 이정표에서 관련 문서를 읽었다 — 계획서, `spring-api-update` Skill과 공통 문서 4종, 본 체크리스트 서식, 관련 코드·테스트.
- [x] 변경 대상의 기존 구조와 사용처를 확인했다 — `SearchService.search` 호출자는 `SearchController`, `AnswerService`뿐. 저장소 내 외부 호출자: `docs/dgx_spark_mvp_deployment.md` 스모크 명령(갱신), `perf/load/*.js`, `perf/evaluation/*.py`(**미수정**, 아래 미완료 참조).
- [x] 기존 API 변경이므로 `spring-api-update` Skill을 확인했다.

## 2. 설계·구현

- [x] 기존 Controller → Service → MyBatis mapper 구조를 유지했다.
- [x] 프로젝트의 Java·Spring Boot·Gradle 버전을 따랐다.
- [x] 다른 영속성 프레임워크를 추가하지 않았다 — 새 `KnowledgeBaseMapper`도 annotation SQL(`@Select`).
- [x] 클래스·메서드 역할, 파라미터, 예외 주석을 작성했다.
- [x] Service 레이어의 주요 흐름·경고 로그 — `SearchService`에 KB 거부 `warn`, 완료 로그에 userId/knowledgeBaseId. `AnswerService` 완료 로그에 knowledgeBaseId.
- [x] 예외 체계 — 400은 기존 `InvalidRequestException`/Bean Validation, 500은 기존 `RagException`. 404는 계획서대로 새 도메인 예외 없이 Spring `ResponseStatusException(NOT_FOUND)`를 쓰고 각 Controller의 지역 handler가 중립 `{message}`로 변환한다.
- [x] 민감 정보 — 404 메시지는 존재·소유를 구분하지 않음. 400 응답에 Spring/Jackson 예외 상세 미노출(테스트로 확인).

구현 결정:
- 사용자: `CurrentUserProvider`(인터페이스 없이 `@Component` 하나, 항상 1 반환). Controller가 resolve해 Service 인자로 전달. 요청의 `userId`는 DTO에 없으므로 무시된다.
- 검색 요청: `SearchRequest` record + `@Valid @ParameterObject`(GET 파라미터 2개 → Request 객체 규칙). `BindException` handler가 필드별 고정 메시지로 변환.
- 답변 요청: `AnswerRequest.knowledgeBaseId`에 `@NotNull @Positive`와 필드 전용 정수 deserializer — Jackson 기본 `ACCEPT_FLOAT_AS_INT`가 `1.5`를 `1`로 잘라 다른 KB를 가리키는 것을 막는다(전역 Jackson 설정은 바꾸지 않음). 형식 오류는 `HttpMessageNotReadableException` handler로 400 `{message}`.
- 순서: 입력 검증 → KB 소유 확인(`existsByIdAndUserId`) → embedding → 범위 SQL → (답변) context/LLM. KB 실패 시 embedding·LLM 미호출.
- SQL: `WHERE d.user_id = #{userId} AND d.knowledge_base_id = #{knowledgeBaseId} AND d.index_status = 'INDEXED'`가 `ORDER BY ... LIMIT` 앞. KB `status` 필터는 정책 미정이라 넣지 않음.
- `POST /api/documents/index`, `DocumentRepository`/`DocumentMapper`, migration은 변경하지 않음.

## 3. 테스트·검증

- [x] 변경 계층에 대응하는 테스트를 작성·수정했다 — `SearchControllerTest`, `SearchServiceTest`, `AnswerControllerTest`, `AnswerServiceTest`, `AnswerDtoTest`, `SwaggerDocumentationTest`, `SearchPersistenceIntegrationTest`(격리 전용).
- [x] RED 확인 — 테스트 선작성 후 `./gradlew compileTestJava`: 새 시그니처(`search(userId, kbId, query)`/`answer(...)`)와 `CurrentUserProvider`/`KnowledgeBaseMapper` 부재로 컴파일 실패(예상 사유). 구현 중 `AnswerControllerTest`의 `knowledgeBaseId=1.5` 케이스가 실제 동작 차이(1로 잘림, 200)로 실패 → 정수 전용 deserializer로 GREEN.
- [x] `./gradlew test` 실행 — 기본 test task(DB `127.0.0.1:1/blocked`, Flyway 비활성, `isolated-*` tag 제외): 당시 Claude Code가 **199 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL을 보고. 이번 격리 DB 실행 후 기본 테스트 재실행 결과는 아래 8절 참조.
- [ ] 당시 테스트 건수·결과 XML의 독립 확인은 사용자 거부로 하지 않았다. 작업자 보고와 직접 검증을 구분한다.
- [x] **격리 DB 통합 테스트 실행 완료(2026-10-29)** — 당시에는 미실행이었으나 아래 8절의 별도 폐기형 DB에서 `SearchPersistenceIntegrationTest`와 `DocumentPersistenceIntegrationTest`를 실행했다. `isolatedMigrationTest`(별도 DB)는 이번에 실행하지 않았다.
- [ ] claude-kit Stop Hook 결과 — 턴 종료 시 실행되므로 이 기록 작성 시점에는 미관찰.
- [x] 테스트·빌드 실패를 남긴 채 완료 처리하지 않았다(격리 DB 항목은 대기로 명시).

## 4. 문서·사람 검수

- [x] README — 경로·메서드는 그대로라 API 목록 표·`_last update`는 유지하고 검색·답변 정책(요청 예시, `knowledgeBaseId`, 400/404, 범위)만 갱신.
- [x] API 명세(Springdoc) — `SearchRequest`/`AnswerRequest` `@Schema`(description, example, requiredMode), 404 `@ApiResponse`, `SwaggerDocumentationTest`에 `knowledgeBaseId` 필수·`userId` 미노출·404 검증.
- [x] DB 변경 없음 — migration 미수정.
- [x] 참고 문서 — `docs/rag_backend_developer.md` §9 현재 구현 메모, `docs/rag_mvp_development_plan.md` 검색·답변 흐름 갱신 메모, `docs/personal_rag_chatbot_design.md` 실제 V4(소유자 FK) 정정, `docs/dgx_spark_mvp_deployment.md` 스모크 명령에 `knowledgeBaseId=1`.
- [ ] claude-kit Review Hook 검수 결과 확인 — Stop Hook과 함께 턴 종료 후.
- [ ] Critical·High 위험 변경 사람 검수 — 클라이언트 호환성 깨짐(구요청 400)과 접근 범위 로직이므로 태환님 검수 필요.
- [x] 외부 부작용·권한·트랜잭션 — 읽기 전용 흐름, 트랜잭션 변경 없음, DB 쓰기 없음.

## 5. 완료 기록

- 변경 파일:
  - main: `retrieval/SearchController.java`, `retrieval/SearchService.java`, `retrieval/SearchRequest.java`(신규), `retrieval/mapper/SearchMapper.java`, `answer/AnswerController.java`, `answer/AnswerRequest.java`, `answer/AnswerService.java`, `knowledgebase/mapper/KnowledgeBaseMapper.java`(신규), `user/CurrentUserProvider.java`(신규)
  - test: `SearchControllerTest`, `SearchServiceTest`, `SearchPersistenceIntegrationTest`, `AnswerControllerTest`, `AnswerServiceTest`, `AnswerDtoTest`, `SwaggerDocumentationTest`
  - docs: `README.md`, `docs/rag_backend_developer.md`, `docs/rag_mvp_development_plan.md`, `docs/personal_rag_chatbot_design.md`, `docs/dgx_spark_mvp_deployment.md`, 본 체크리스트
  - `build.gradle`: 기존 사용자 변경(기본 test DB 차단·Flyway 비활성) 그대로 보존, 이번 작업에서 수정하지 않음
- 실행한 검증 명령: `./gradlew compileJava compileTestJava`, 대상 테스트 `./gradlew test --tests ...`(Search*/Answer*/Swagger), `./gradlew test`, `git diff --check`
- 실제 검증 결과: 위 3절 참조(기본 test 199건 통과)
- 미완료 항목과 사유:
  - 격리 DB 통합 테스트 — 당시 대기, 2026-10-29 실행(아래 8절)
  - ~~perf 호출자 4곳 `knowledgeBaseId` 누락~~ → 아래 6절에서 보완
  - 저장소 밖 외부 클라이언트 호환성은 확인 불가
- 사람 검수가 필요한 사항: 구요청 400 전환(호환성), 404 매핑 방식(`ResponseStatusException`), 인증 부재로 개발 사용자 1 고정, 기존 색인 POST의 인증 없는 노출 위험(후속)

## 6. 리뷰 지적 보완 (2026-10-28, Codex Medium 2·Low 1)

> 실행 단위: `.hermes/plan_part1/2026-10-28-kb-scope-review-findings-fix.md`. API·DB·migration·설계 변경 없음.

- [x] **Medium ① perf 호출자 KB 필수 입력** — `perf/load/search-load.js`·`answer-load.js`는 `KNOWLEDGE_BASE_ID` 환경변수(필수, `^[1-9][0-9]*$`)를 init 단계에서 검증해 요청 전에 실패하고 각각 query 파라미터·JSON 본문(숫자)으로 전달. `run_search_load.sh`·`run_answer_load.sh`는 같은 변수를 검증(실패 시 exit 2, docker 미호출)하고 `-e KNOWLEDGE_BASE_ID`로 k6 컨테이너에 전달. `evaluate_retrieval.py`·`evaluate_answers.py`는 `--knowledge-base-id`(필수, 양의 정수) — argparse 단계에서 실패. 기본 KB 1 암묵 대입 없음. 저장소 내 다른 호출자는 검색으로 없음 확인. `perf/README.md`에 입력 방법과 "gold 문서가 선택 KB에 INDEXED로 있어야 정확도 해석 유효" 조건 기록.
  - 오프라인 테스트 `perf/test_knowledge_base_id.py`(7 tests): Python은 `urlopen` mock, 래퍼는 PATH 가짜 `docker`로 인자 기록, k6는 누락·오류 입력의 init 실패 + loopback(127.0.0.1) 임시 stub에 1회 요청으로 URL·본문 확인. 외부 네트워크·Spark·DB 미사용.
  - RED: 구현 전 실행 `FAILED (failures=35, errors=4)` → GREEN: `Ran 7 tests ... OK`.
- [x] **Medium ② 격리 통합 테스트 양성 답변 경로 추가** — `SearchPersistenceIntegrationTest#answerService_범위_밖_Chunk가_더_가까워도_prompt와_sources에는_KB_A_근거만_담긴다`: 사용자 1의 KB A(distance 1)·KB B(distance 0), 다른 사용자 KB C(distance 0)에 서로 다른 content/title/source를 넣고 실제 DB 검색을 거친 `AnswerService.answer(1, kbA, ...)`가 LLM mock을 1회 호출, 전달된 prompt에 KB A content·source만 있고 B/C는 없으며, 응답 answer·sources(`documentId`, `source`)가 KB A만인지 검증. 기존 `IsolatedDatabaseGuard`·UUID/생성 id cleanup 그대로 사용(헬퍼에 title/source 지정 overload만 추가).
  - [x] **격리 DB 실행 완료(2026-10-29)** — 당시에는 127.0.0.1:55432/rag_integration_test 격리 컨테이너를 확인하지 못해 컴파일만 수행했다. 이후 별도 실행 결과는 아래 8절 참조.
- [x] **Low ③ 개발 계획서 정정** — `docs/rag_mvp_development_plan.md` 6단계의 요청 예시를 `query`+`knowledgeBaseId`로, 파라미터 설명을 현재 설계(SearchRequest, 400/404, 사용자 1 고정)로 바꾸고 최초 구현은 "당시 기록"으로 구분. 검색 SQL 예시를 현재 `SearchMapper`와 동일한 `WHERE user_id/knowledge_base_id/index_status='INDEXED'`로 교체하고 KB 소유 확인이 SQL 전 Service에서 수행됨을 명시. 처리 흐름·테스트 공백·통합 테스트 문단에 당시 기록 표시.
- 실행한 검증: `python3 perf/test_knowledge_base_id.py`(OK, 7 tests), `bash -n` 래퍼 2개, `node --check perf/load/search-load.js`, `./gradlew test`(BUILD SUCCESSFUL — 기본 test task, DB 차단 URL·`isolated-*` tag 제외), `git diff --check`.
- 미확인: 이번 `./gradlew test`의 테스트 건수는 콘솔에 표시되지 않아 별도 확인하지 않음(결과 XML 직접 조회는 하지 않음). Stop Hook 결과는 턴 종료 시 실행되어 이 기록 시점 미관찰. 실제 Spark 부하·평가 실행 안 함.

## 7. 재리뷰 Low 2건 보완 (2026-10-29, Codex 재리뷰)

- [x] **Low — 답변 API 계획서 정정**: `docs/rag_mvp_development_plan.md` 7단계 요청 예시에 `knowledgeBaseId` 추가, 필수·양의 정수 검증(400), `userId` 서버 결정(개발용 1), 없는/타인 KB 404(embedding·LLM 미호출)를 현재 설계로 명시하고 `{"question"}` 단독은 "당시 기록(현재 400)"으로 구분. 처리 흐름을 `AnswerController.answer(AnswerRequest)` → `AnswerService.answer(userId, knowledgeBaseId, question)` → `SearchService.search(userId, knowledgeBaseId, question)` 범위 흐름으로 교체(`AnswerController`/`AnswerService`/`AnswerRequest` 코드와 대조).
- [x] **Low — k6 테스트 자기완결화**: `perf/test_knowledge_base_id.py`의 k6 테스트가 임시 디렉터리에 `search-load.js`·`answer-load.js`·`scenarios.json` 사본과 4개 카테고리 최소 fixture(`dataset/queries_m.resolved.jsonl`)를 만들어 실행한다. 운영 스크립트의 데이터셋 경로·실제 데이터셋 파일은 변경·이동·삭제하지 않음.
  - RED: fixture 질의 사용 assert를 먼저 추가 → 실제 데이터셋 질의가 전송되어 `FAILED (failures=1)`. GREEN: 임시 사본 실행으로 `Ran 7 tests ... OK`.
  - 새 clone 모사: `git ls-files perf` + 새 테스트 파일만 scratchpad로 복사(실제 데이터셋 부재 확인)한 뒤 실행 → `Ran 7 tests ... OK`.
- 실행한 검증: `python3 perf/test_knowledge_base_id.py`(OK, 7), `./gradlew test`(기본 blocked DB·`isolated-*` 제외), `git diff --check`.
- [x] 격리 DB 통합 테스트(`SearchPersistenceIntegrationTest` 포함) — 이 보완 시점에는 **미실행**이었고 이후 2026-10-29 실행(아래 8절). 7절 보완 자체에는 Java 코드 변경 없음.
- Stop Hook: 이 기록 시점 미관찰.

## 8. 폐기형 격리 DB 실행 및 실패 수정 (2026-10-29, Hermes)

- [x] `docker-compose.isolated-test.yml` 전용 프로젝트 `rag-kb-scope-isolated`를 새로 기동해 `healthy`, `127.0.0.1:55432` 바인딩, `/var/lib/postgresql/data` tmpfs, 실제 `current_database()=rag_integration_test` 확인. 자격 증명은 실행 프로세스에서만 생성·전달하고 파일·명령줄에 기록하지 않았다. 기존 `rag-postgres`에는 연결·변경하지 않음. 실행 후 전용 컨테이너·네트워크와 임시 실행 스크립트 정리 확인.
- [x] 첫 실제 `SearchPersistenceIntegrationTest` 실행: 9건 중 8건 통과, 기존 `search_query_벡터_차원이_다르면_실제_DB_오류가_발생한다` 1건 실패. 테스트가 비교할 색인 청크를 넣지 않아 0건 조회에서는 pgvector 거리 연산이 실행되지 않는 것이 원인(Gradle HTML 보고서의 `Expecting code to raise a throwable`). 새 답변 KB A/B/C 프롬프트·출처 격리 테스트는 이 실행에서도 통과.
- [x] 위 기존 테스트에 자기 KB의 색인 청크 1건만 추가하고 동일한 격리 DB 절차에서 `./gradlew --no-daemon isolatedIntegrationTest --console=plain` 재실행: **16건(검색 9, 문서 7)·실패 0·무시 0**, `BUILD SUCCESSFUL`. 결과 건수·새 답변 테스트 성공은 Gradle HTML 보고서에서 직접 확인(XML 미조회). Flyway V1~V4는 이 폐기형 DB의 테스트 컨텍스트 기동 시 적용됐으며 운영 DB migration은 실행하지 않음.
- [x] `./gradlew test --rerun-tasks --console=plain` 재실행: `BUILD SUCCESSFUL`, 4개 작업 실행. `git diff --check` 통과. 기본 테스트 건수는 별도로 확인하지 않음.
- [ ] 별도 `isolatedMigrationTest`와 실제 Spark API·LLM 호출은 이번 범위 밖으로 미실행. claude-kit Stop/Review Hook 결과도 미관찰; 접근 범위·구요청 400 전환은 태환님 검수 대기. commit·push·배포 없음.
