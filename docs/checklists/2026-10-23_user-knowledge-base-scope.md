# 작업 체크리스트 — 사용자·Knowledge Base DB 범위 전환 (V3)

> 공통 서식: `docs/claude_kit_development_checklist.md`
> 기준 문서: `.hermes/plan_part1/2026-10-23_180211-personal-rag-chatbot-implementation-part1.md`,
> `docs/personal_rag_chatbot_design.md` §0, §2, §5~6

## 작업 정보

- 작업명: 사용자·Knowledge Base DB 범위 전환 (Part 1 — DB 저장 구조·재색인 범위만)
- 작업 일자: 2026-10-23
- 관련 문서: `.hermes/plan_part1/2026-10-23_180211-personal-rag-chatbot-implementation-part1.md`, `docs/personal_rag_chatbot_design.md`
- 담당: Claude Code(단독 구현·문서 편집자, Orca 배정)

## 1. 작업 시작 전

- [x] 요청 범위와 완료 기준을 정리했다 — 포함: V3 migration, MyBatis 저장·재색인 계층, 해당 테스트, 관련 문서. 제외: 검색 SQL 범위 제한, KB API, 업로드, UI, commit/push/실DB 적용.
- [x] 요구사항이 모호한 부분을 먼저 확인했다 — 기본 KB id 고정 방식, 업로드 전용 컬럼 NULL 정책 등은 설계 초안에 없어 이번에 직접 결정하고 `docs/personal_rag_chatbot_design.md` §6에 "구현 결정" 블록으로 남겼다(사용자 재확인 질문은 하지 않음 — 가역적이고 Hermes/Codex 검증 단계에서 재검토 가능).
- [x] `CLAUDE.md`를 읽었다(전역 + 프로젝트).
- [x] 참고 문서 이정표에서 관련 문서를 읽었다 — `docs/personal_rag_chatbot_design.md` §2·§5~6, V1/V2 migration, `DocumentMapper`/`DocumentRepository`/`DocumentIndexService`/`DocumentPersistenceIntegrationTest`.
- [x] 변경 대상의 기존 구조와 사용처를 확인했다 — `grep`으로 `deleteBySource`/`reindex(`/`DocumentInsertParameter(` 전체 호출부를 찾아 함께 수정(아래 "변경 파일" 참조).
- [ ] 신규 API면 `spring-api-create` 등 Skill 확인 — 해당 없음(신규/변경 API 없음, 이번 단위는 DB·MyBatis 계층만).

## 2. 설계·구현

- [x] 기존 Controller → Service → 데이터 접근 구조를 유지했다 — Controller/Service 구조는 바꾸지 않음(Controller 변경 없음).
- [x] 프로젝트의 Java·Spring Boot·Gradle 버전을 따랐다(변경 없음).
- [x] MyBatis(annotation SQL, XML 없음) 구조를 유지했다 — 새 mapper 메서드도 `@Delete`/`@Insert` annotation만 사용.
- [x] 클래스·메서드 역할과 필요한 파라미터·예외 주석을 작성했다 — `DocumentMapper`/`DocumentRepository`/`DocumentIndexService`/migration SQL 주석 갱신.
- [x] Service 레이어의 주요 흐름과 경고 로그를 작성했다 — `DocumentRepository.reindex()` 로그에 userId/knowledgeBaseId 추가.
- [x] 비즈니스 예외가 있으면 프로젝트의 단일 예외 체계를 사용했다 — `RagException` 재사용(새 null 검증 포함).
- [x] 민감 정보와 자격 증명을 코드·설정·로그에 기록하지 않았다 — `.env`/DB 자격 증명 미접근.

## 3. 테스트·검증

- [x] 변경 계층에 대응하는 테스트를 작성하거나 수정했다 — `DocumentRepositoryTest`, `DocumentIndexServiceTest`, `DocumentPersistenceIntegrationTest`(KB 간 격리 테스트 2건 추가), `SearchPersistenceIntegrationTest`(호출부만 수정, 검색 로직 자체는 변경 없음), 신규 `V3UserKnowledgeBaseScopeMigrationTest`(빈 DB / 기존 V2 데이터 두 경로 + KB 간 UNIQUE 경계 2건).
- [ ] `./gradlew test`를 실행했다 — **실행하지 않음(의도적)**. 이 작업 지시 자체가 "격리 DB 연결 대상을 Hermes가 사전 승인하기 전에는 실행하지 말 것"을 명시하고, `./gradlew test`는 `.env`/기본 `DB_URL`로 실제 DB(로컬/Spark일 수 있음)에 붙을 수 있어 Claude가 직접 실행하지 않는다. Hermes가 격리 DB 연결값을 확인한 뒤 실행해 출력을 전달해야 한다.
- [ ] 테스트 결과를 실제 출력으로 확인했다 — 위와 같은 이유로 **미확인**. `./gradlew compileJava compileTestJava`(DB 비연결, 컴파일만)는 실제로 실행해 BUILD SUCCESSFUL을 확인했다(아래 "완료 기록" 참조). 신규 `V3UserKnowledgeBaseScopeMigrationTest`는 추가 안전장치로 `-DisolatedMigrationTest=true`가 없으면 스스로 막힌다(코드 리뷰로만 확인, 실행 안 함).
- [ ] 필요하면 Health Check·DB·외부 호출 등 변경된 경계를 별도로 검증했다 — DB 경계 자체가 미실행 상태라 미검증.
- [ ] claude-kit Stop Hook의 컴파일·대응 테스트 결과를 확인했다 — 이 세션(Orca 단독 작업자 디스패치)에서 Stop Hook이 실행됐는지 확인할 수 있는 출력을 받지 못했다. 실행 여부 자체를 **미확인**으로 남긴다(통과로 표시하지 않음).
- [ ] 테스트·빌드 실패를 남긴 채 완료 처리하지 않았다 — 컴파일은 성공했지만 DB 연결 테스트는 "미완료(실행 금지)"이지 "실패"는 아니다. 통과로 표기하지 않고 아래 "미완료 항목"에 명시한다.

## 4. 문서·사람 검수

- [x] API 경로·메서드·요청·응답이 바뀌면 README API 목록을 갱신했다 — 해당 없음(API 변경 없음). `README.md`의 `tb_document.source` UNIQUE 설명 한 줄은 스키마 사실이 바뀌어 최소 수정(API 목록 표 자체는 건드리지 않음).
- [x] 프로젝트에서 관리하는 API 명세가 있으면 갱신했다 — 해당 없음(API 변경 없음, Swagger annotation 미변경).
- [x] DB 테이블·컬럼·인덱스가 바뀌면 migration과 관련 문서를 함께 갱신했다 — `docs/README.md` 이정표 표에 V3 행 추가, `docs/personal_rag_chatbot_design.md` §6에 구현 결정 블록 추가.
- [x] 설계 결정이나 구현 현황이 바뀌면 참고 문서를 갱신했다 — 위와 동일.
- [ ] claude-kit Review Hook의 검수 결과를 확인했다 — 실행 여부를 확인할 출력을 받지 못해 **미확인**.
- [ ] Critical·High 위험 변경은 사람이 검수했다 — migration·UNIQUE 제약 변경은 위험도가 높은 변경이다. 이 작업 계획상 Codex 독립 검증과 태환님의 최종 승인이 이 체크 항목을 채운다(Claude 단계에서는 아직 미검수).
- [x] 외부 부작용·권한·트랜잭션·동시성·삭제·시크릿 변경 여부를 직접 확인했다 — `@Transactional` 유지 확인, 삭제 범위를 `userId+knowledgeBaseId+source`로 좁힘, 시크릿 미접근.

## 5. 완료 기록

- 변경 파일:
  - `src/main/resources/db/migration/V3__add_user_knowledge_base_scope.sql` (신규)
  - `src/main/java/com/nohtaehwan/rag/indexing/mapper/DocumentInsertParameter.java`
  - `src/main/java/com/nohtaehwan/rag/indexing/mapper/DocumentMapper.java`
  - `src/main/java/com/nohtaehwan/rag/indexing/DocumentRepository.java`
  - `src/main/java/com/nohtaehwan/rag/indexing/DocumentIndexService.java`
  - `src/test/java/com/nohtaehwan/rag/indexing/DocumentRepositoryTest.java`
  - `src/test/java/com/nohtaehwan/rag/indexing/DocumentIndexServiceTest.java`
  - `src/test/java/com/nohtaehwan/rag/indexing/DocumentPersistenceIntegrationTest.java`
  - `src/test/java/com/nohtaehwan/rag/indexing/V3UserKnowledgeBaseScopeMigrationTest.java` (신규)
  - `src/test/java/com/nohtaehwan/rag/retrieval/SearchPersistenceIntegrationTest.java` (호출부만, 검색 로직 미변경)
  - `docs/README.md`, `docs/personal_rag_chatbot_design.md`, `README.md` (문서)
- 실행한 검증 명령: `./gradlew compileJava compileTestJava` (DB 비연결)
- 실제 검증 결과: `BUILD SUCCESSFUL` — 전체 메인/테스트 소스 컴파일 성공(최초 1회는 기존 3개 테스트 파일이 옛 메서드 시그니처를 참조해 28개 컴파일 오류 발생 → 수정 후 재실행해 통과).
- 미완료 항목과 사유:
  - `./gradlew test`(DB 연결 테스트) 미실행 — Hermes의 격리 DB 승인 전 실행 금지 지시에 따름.
  - 신규 `V3UserKnowledgeBaseScopeMigrationTest`의 "빈 DB"/"기존 V2 데이터" 두 경로 및 `DocumentPersistenceIntegrationTest`의 KB 간 격리 테스트 2건을 포함해, **이번 작업에서 작성한 모든 DB 연결 테스트는 실제로 한 번도 실행되지 않았다.** 로직은 기존 테스트 패턴·Flyway/MyBatis API 문서를 근거로 작성했지만 실행 결과로 입증되지 않았다.
  - claude-kit Stop/Review Hook 실행 여부 미확인.
  - Critical 위험 변경(migration, UNIQUE 제약 교체)에 대한 사람 검수 미완료 — Codex 검증과 태환님 승인 대기.
- 사람 검수가 필요한 사항:
  - V3 migration의 두 경로(빈 DB / 기존 V2 데이터) 실제 적용 결과.
  - 격리 DB 연결 대상이 실제로 로컬/Spark DB가 아님을 확인하는 절차.
  - `index_status`를 insert 시 SQL 리터럴 `'INDEXED'`로 고정한 결정(비동기 색인 전용 상태는 다음 단위에서 다룸)이 적절한지.

## 6. 후속 보강 (2026-10-23) — 문서·KB 소유자 일치 강제(V4), 통합 테스트 cleanup 격리

### 구현 완료 (코드·파일 기준)

- [x] 신규 forward migration `V4__enforce_document_knowledge_base_owner.sql` — V3는 수정·재실행하지 않음.
  - `tb_knowledge_base`에 `UNIQUE (id, user_id)`(`uq_tb_knowledge_base_id_user`) 추가.
  - `tb_document (knowledge_base_id, user_id)` → `tb_knowledge_base (id, user_id)` 복합 FK(`fk_document_knowledge_base_owner`) 추가. 문서가 참조 중인 KB의 `user_id` 변경도 막는다.
  - 기존 불일치 데이터 처리: 적용 전 `DO` 블록이 불일치 건수를 세어 1건 이상이면 `RAISE EXCEPTION`으로 migration 전체를 실패(롤백)시킨다. 데이터를 자동 수정·삭제하지 않는다.
  - V3의 단일 FK(`fk_document_user`, `fk_document_knowledge_base`)는 유지(중복이지만 무해).
- [x] 기존 소스의 소유자 불일치 가능성 점검(코드 리뷰): `DocumentIndexService`(user 1 / KB 1), V3 기본 귀속(user 1 / KB 1), `DocumentPersistenceIntegrationTest`의 두 번째 KB(user 1 소유에 user 1 문서), `SearchPersistenceIntegrationTest`(user 1 / KB 1), `V3UserKnowledgeBaseScopeMigrationTest`(KB 2 = user 1 소유, 문서 user 1) — 모두 일치. 불일치를 만드는 코드 경로는 없다. 실제 기존 문서 row는 조회하지 않았다. 별도 승인된 read-only Flyway history 조회로 `rag_db` V3 적용(2026-10-23 19:46:47) 및 V4 미적용만 확인했다.
- [x] 신규 `V4DocumentKnowledgeBaseOwnerMigrationTest`(`./gradlew isolatedMigrationTest` 전용 task 및 실제 JDBC identity Guard): 불일치 insert 거부 / 일치 insert 성공 / 참조 중인 KB 소유자 변경 거부 / 기존 불일치 데이터가 있으면 V4 실패 + 데이터·제약 미변경 4건.
- [x] `V3UserKnowledgeBaseScopeMigrationTest`: `migrationsExecuted`를 검증하는 두 테스트의 migrate target을 `latest` → `"3"`으로 고정(V4 추가로 건수가 바뀌지 않도록). V3 SQL 자체는 미변경.
- [x] `DocumentPersistenceIntegrationTest` cleanup 격리: source를 테스트 인스턴스마다 UUID 기반(`integration-test/{uuid}/doc-a.md`)으로 바꾸고, 두 번째 KB는 id 지정 없이 `INSERT ... RETURNING id`로 만들어 생성 id를 캡처한다. cleanup은 이 실행의 source 문서와 캡처한 KB id만 삭제한다(고정 source·고정 KB id=2 삭제 제거).
- [x] `SearchPersistenceIntegrationTest`: 이미 UUID source + 해당 source만 삭제하는 cleanup이라 변경하지 않음.

### 실행한 검증

- `./gradlew compileJava compileTestJava` → `BUILD SUCCESSFUL`.
- `./gradlew test --tests com.nohtaehwan.rag.indexing.DocumentRepositoryTest --tests com.nohtaehwan.rag.indexing.DocumentIndexServiceTest` → `BUILD SUCCESSFUL`, DocumentRepositoryTest 5건·DocumentIndexServiceTest 7건 모두 통과(실패·스킵 0, DB 비연결 Mockito 테스트).
- `git diff --check` → 통과.

### 실행 결과와 미검증 항목

- [x] 태환님이 V4를 실제 `rag_db`에 적용. Flyway 이력 read-only 확인: `current_database=rag_db`, version `4`, `success=t`, `installed_on=2026-10-27 16:27:29.231521`; `fk_document_knowledge_base_owner` 존재. 적용은 사용자가 수행했고 에이전트는 DB를 변경하지 않았다.
- [x] `./gradlew --no-daemon isolatedMigrationTest` — `rag_migration_test`(PostgreSQL 17.11, `127.0.0.1:55432`, tmpfs/no volumes)에서 실행. V3 migration test 4건 + V4 migration test 4건 통과(실패·오류·skip 0). XML에서 V4 성공 및 불일치 데이터 migration 거부/rollback 확인.
- [x] `./gradlew --no-daemon isolatedIntegrationTest` — 별도 `rag_integration_test`(PostgreSQL 17.11, `127.0.0.1:55432`, tmpfs/no volumes)에서 V1~V4 자동 적용 후 DocumentPersistence 7건 + SearchPersistence 3건 통과(실패·오류·skip 0). XML의 JDBC URL은 `rag_integration_test`; 테스트 cleanup fixture는 UUID source/생성 KB.

- [ ] 전체 `./gradlew test` — 미실행. 기본 `Test` task에 `.env`가 주입되므로 이 작업에서는 전체 suite를 실행하지 않고, DB 비연결 Guard·두 전용 isolated task와 Hook probe만 검증했다.
- [x] `docs/README.md` migration 이정표에 V4 행 추가 — `docs/README.md:39` 확인.
- [x] Critical 위험 변경(제약 추가 migration) 사람 검수·적용 완료 — 태환님이 별도 격리 검증 후 실제 `rag_db`에 적용하고 성공 이력/FK 존재를 확인했다.

## 2026-10-24 Codex 1차 리뷰 차단 항목 보완 (격리 DB Guard·Stop Hook)

### 변경

- [x] `IsolatedDatabaseGuard` 연결 전 검사 강화: Hikari에 직접 주입된 `DataSource`, `dataSourceJNDI`, `dataSourceClassName`, `dataSourceProperties`, PostgreSQL 외 `driverClassName` 중 하나라도 있으면 `getConnection()` 전에 거부(HikariCP 6.3.3 `HikariConfig` API를 `javap`로 확인).
- [x] 대상 고정: host는 `docker-compose.isolated-test.yml` bind와 같은 `127.0.0.1`, 포트는 `55432`만 허용(`localhost`·`[::1]`·다른 loopback 포트 거부). DB 이름은 모드별 고정값 `rag_migration_test`/`rag_integration_test`만 허용.
- [x] `.claude/hooks/stop_build_check.sh`(보호 파일, 코디네이터 경유 승인): 대응 테스트가 `@Tag("isolated-…")` 클래스면 `--tests` 선택에서 빼고 전용 task로 안내. 기본 `test`의 `excludeTags`와 Guard는 그대로.

### 실행한 검증 (DB 비연결)

- `./gradlew compileJava compileTestJava test --tests com.nohtaehwan.rag.testing.IsolatedDatabaseGuardTest` → `BUILD SUCCESSFUL`, 10건 통과(실패·스킵 0). 신규: direct DataSource/JNDI Hikari·알 수 없는 DataSource와 5432·55433·59513·localhost·`[::1]`·`other_migration_test`·`rag_migration_test2`가 `getConnection()` 미호출로 거부됨.
- 수정 전 Hook 동작 probe: `./gradlew test --tests …V4DocumentKnowledgeBaseOwnerMigrationTest --tests …SearchPersistenceIntegrationTest` → `No tests found for given includes`, `BUILD FAILED`(exit 1). tag 제외로 DB 테스트 0건, 연결 없음.
- 수정 후 Hook 직접 실행(마커 파일 주입, `echo '{}' | .claude/hooks/stop_build_check.sh`):
  - tagged-only(V4·SearchPersistence) → exit 0, "대응 테스트 파일 없음" + 격리 task 안내, Gradle test 미실행.
  - 혼합(GuardTest + DocumentPersistence·V3 tagged) → exit 0, `IsolatedDatabaseGuardTest`만 실행·통과. 결과 XML도 GuardTest 1개뿐.
- `bash -n .claude/hooks/stop_build_check.sh` → 통과. `git diff --check` → 통과.

### 격리 DB 검증 결과 / 미검증 항목

- [x] `./gradlew --no-daemon isolatedMigrationTest` — `127.0.0.1:55432/rag_migration_test`(PostgreSQL 17.11, tmpfs/no volume): V3 4건 + V4 4건 통과, 실패·오류·skip 0.
- [x] `./gradlew --no-daemon isolatedIntegrationTest` — 별도 `127.0.0.1:55432/rag_integration_test`(PostgreSQL 17.11, tmpfs/no volume): V1~V4 적용, DocumentPersistence 7건 + SearchPersistence 3건 통과, 실패·오류·skip 0.
- [x] 두 테스트 Compose project 종료 후 `docker ps`에서 해당 컨테이너가 없고 기존 `rag-postgres`가 계속 healthy인 것을 확인했다.
- [ ] 전체 `./gradlew test`는 실행하지 않음. 일반 `Test` task에 `.env` 주입이 남아 있어 실제 DB 영향 가능성을 배제하기 위해 격리 task만 실행했다.
- [x] `bootRun`·`/health` 검증: 태환님이 로컬에서 수행해 `/health` UP을 보고했다. 최초 원인은 `rag_app` 인증 비밀번호 불일치였고, 계정 비밀번호와 `DB_APP_PASSWORD`를 일치시킨 뒤 해결됐다고 보고함(비밀번호 값은 기록하지 않음).
- [ ] Codex Low 잔여: `dataSourceProperties` 및 비-PostgreSQL `driverClassName` 분기의 직접 연결 미호출 unit test가 없다(코드 검사 분기는 존재).
- [x] Critical V4 제약 migration의 실제 DB 적용 — 태환님이 승인·수행하고 Flyway 이력 및 FK 생성으로 확인했다.

### Codex 2차 재리뷰

- [x] 2차 독립 read-only 리뷰에서 1차 2 High·1 Medium이 해결되고, 이번 safety scope의 새 High/Medium은 없다고 판정했다.
- [x] 남은 Low 단위 테스트 커버리지 공백을 위 미검증 항목에 기록했다. Codex는 Docker/DB/test/build을 실행하지 않았다.
