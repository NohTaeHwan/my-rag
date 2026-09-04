# 개발 작업별 claude-kit 준수 체크리스트

> 기능 하나를 개발하거나 수정할 때마다 복사해 작성한다.
> 체크하지 못한 항목은 완료로 표시하지 않고 사유를 남긴다.

## 작업 정보

- 작업명: 5단계 문서 색인 (Markdown → Chunking → Embedding → PostgreSQL 저장)
- 작업 일자: 2026-09-04
- 관련 문서: docs/rag_mvp_development_plan.md, docs/rag_backend_developer.md, db/migrations/001_init_rag_schema.sql
- 담당: Claude Code (사용자 승인 하에 진행)

## 1. 작업 시작 전

- [x] 요청 범위와 완료 기준을 정리했다.
- [x] 요구사항이 모호한 부분을 먼저 확인했다. (신규 설정 불필요함을 확인 후 브리핑에 포함, 별도 질문 없이 진행)
- [x] `CLAUDE.md`를 읽었다.
- [x] 참고 문서 이정표에서 관련 문서를 읽었다.
- [x] 변경 대상의 기존 구조와 사용처를 확인했다. (`document`/`embedding` 패키지, `RagException`, `HealthController`)
- [x] 신규 API이므로 `spring-api-create` 관점(Controller→Service→데이터 접근 구조)을 따랐다.

## 2. 설계·구현

- [x] 기존 Controller → Service → 데이터 접근 구조를 유지했다.
- [x] 프로젝트의 Java 17·Spring Boot 3.5.6·Gradle을 따랐다.
- [x] JPA·MyBatis·WebFlux·Swagger/OpenAPI를 추가하지 않았다.
- [x] 클래스·메서드 역할과 필요한 파라미터·예외 주석을 작성했다.
- [x] Service 레이어(`DocumentIndexService`)와 `DocumentRepository`에 `@Slf4j` 로그를 작성했다(내용/벡터는 로그에 남기지 않음).
- [x] 비즈니스 예외는 기존 `RagException`을 재사용했다(신규 예외 클래스 추가 없음).
- [x] 민감 정보와 자격 증명을 코드·설정·로그에 기록하지 않았다. 신규 환경변수도 추가하지 않았다.

## 3. 테스트·검증

- [x] 변경 계층(Service/Repository/Controller)에 대응하는 테스트를 작성했다.
- [x] `./gradlew test --no-daemon`을 실행했다.
- [x] 테스트 결과를 실제 출력으로 확인했다.
- [x] (최초 구현 시점) Docker/Postgres 미기동으로 `@SpringBootTest` 2건이 실패했으나 이번 변경과 무관한 환경 문제임을 확인했다. **이후 코드리뷰 후속 수정 단계에서 로컬 `rag-postgres`(pgvector) 컨테이너가 기동되어 전체 테스트가 통과하는 것까지 재확인했다** — 아래 "6. 코드리뷰 후속 수정" 참고.
- [x] claude-kit Stop Hook의 컴파일·대응 테스트 결과를 확인했다(아래 완료 기록 참고).
- [x] 테스트·빌드 실패를 남긴 채 완료 처리하지 않았다.

## 4. 문서·사람 검수

- [x] API 신규 추가이므로 README API 목록을 갱신했다(엔드포인트 목록 + 정책 bullet).
- [x] 프로젝트에서 별도 API 명세 도구를 사용하지 않아 해당 없음.
- [x] DB 테이블·컬럼·인덱스 변경 있음 — `V2__add_unique_constraint_to_document_source.sql` 추가(`tb_document.source` UNIQUE). `V1`은 수정하지 않았다. 관련 문서(`docs/rag_mvp_development_plan.md`) 함께 갱신.
- [x] 설계 결정이 바뀌어 `docs/rag_mvp_development_plan.md` 5단계 섹션을 갱신했다.
- [x] claude-kit Review Hook의 검수 결과를 확인했다 — critical 1건/high 2건, 사용자에게 리포트 형식으로 보고 완료(아래 5번 참고).
- [x] Critical·High 위험 변경은 사람이 검수했다 — 이번 코드리뷰 후속 수정 요청 자체가 그 결과물이며(source UNIQUE 추가, generated key 방어, DataAccessException 처리 등), 사용자가 직접 지시한 항목을 반영했다.
- [x] 외부 부작용·권한·트랜잭션·동시성·삭제·시크릿 변경 여부를 직접 확인했다(문서 단위 트랜잭션, source 기준 delete, UNIQUE 제약 동시성 검토, 신규 시크릿 없음).

## 5. 완료 기록

- 변경 파일:
  - 신규: `src/main/java/com/nohtaehwan/rag/indexing/{DocumentIndexController,DocumentIndexService,DocumentIndexResponse,DocumentRepository,DocumentChunkRepository,EmbeddedChunk}.java`
  - 신규: `src/test/java/com/nohtaehwan/rag/indexing/{DocumentIndexServiceTest,DocumentRepositoryTest,DocumentChunkRepositoryTest,DocumentIndexControllerTest}.java`
  - 수정: `docs/rag_mvp_development_plan.md` (5단계 체크박스·응답 예시·설계 확정 내용)
  - 수정: `README.md` (API 목록·프로젝트 구조에 `indexing`/`document` 패키지 반영)
  - 신규: `docs/checklists/2026-09-04_document-indexing.md` (본 파일)
- 실행한 검증 명령: `./gradlew test --no-daemon`, `git diff --check`, `git status --short --untracked-files=all`
- 실제 검증 결과: 신규 색인 관련 테스트 10건 전부 통과. 전체 65건 중 사전에 존재하던 `@SpringBootTest` 2건만 로컬 Postgres 미기동으로 실패(본 작업과 무관). `git diff --check` 공백 오류 없음.
- claude-kit Review Hook(Stop Hook) 자동 탐지 결과: critical 1건(`DocumentRepository.reindex` 데이터 삭제 변경), high 2건(트랜잭션 경계 변경, 공개 API 계약 변경) — 사용자에게 리포트 형식으로 보고 완료. 확정 오류 아님, 사람 확인 필요 항목으로 남김.
- ponytail-review(diff 스코프) 결과: 2건 발견(KeyHolder→Postgres RETURNING 대체안, StringBuilder→Collectors.joining 축약). 사용자 판단으로 `DocumentChunkRepository.toVectorLiteral`의 `Collectors.joining` 축약만 반영(KeyHolder 방식은 추후 MyBatis 전환 계획이 있어 보류). 반영 후 관련 테스트 재확인, 통과.
- 미완료 항목과 사유: 없음(README 갱신 완료). Review Hook 항목 중 사람 검수는 아래 항목 참고.
- 사람 검수가 필요한 사항: `DocumentRepository.reindex()`의 문서 단위 트랜잭션·재색인(delete-then-insert) 로직, `tb_document.source` 유니크 제약 부재로 인한 중복 삭제 가능성. → 아래 6번에서 이 사항 자체를 반영함.

## 6. 코드리뷰 후속 수정 (2026-09-04, 같은 날 추가 작업)

다른 코드리뷰 에이전트의 검토 결과를 받아 진행한 후속 수정. 범위: source UNIQUE 제약, 재색인 방어 로직, 부분 성공/빈 문서 정책 문서화, Controller DB 오류 처리, 테스트 보강.

- 변경 파일(추가):
  - 신규: `src/main/resources/db/migration/V2__add_unique_constraint_to_document_source.sql`
  - 수정: `DocumentRepository.java`(source null/blank 방어, generated key null 방어, JavaDoc 보강), `DocumentIndexController.java`(`DataAccessException` handler 추가), `DocumentIndexService.java`(부분 성공·빈 문서 정책 JavaDoc)
  - 수정: `DocumentIndexServiceTest.java`(+2 테스트: 2번째 문서 실패 시 1번째는 저장/2번째는 미저장, 빈 문서 처리), `DocumentRepositoryTest.java`(+3 테스트: source blank/null, generated key 없음, Chunk insert 실패 시 예외 전파), `DocumentIndexControllerTest.java`(+1 테스트: `DataAccessException` → sanitized 500)
  - 수정: `docs/rag_mvp_development_plan.md`, `README.md`, 본 체크리스트
- source UNIQUE 적용 방식: `ALTER TABLE tb_document ADD CONSTRAINT uq_tb_document_source UNIQUE (source)`. Postgres 관례상 UNIQUE 제약이 곧 유니크 btree 인덱스를 생성하므로 별도 인덱스를 추가하지 않았다. `V1`은 수정하지 않고 `V2`로 분리했다.
- 중복 source 사전 확인: 로컬 `rag-postgres`(pgvector) 컨테이너가 이번 후속 수정 도중 기동되어, 실제로 migration을 적용하고 확인했다.
  - `SELECT source, COUNT(*) FROM tb_document GROUP BY source HAVING COUNT(*) > 1;` 결과 0건(중복 없음) — 실제 source 값은 기록하지 않음.
  - `tb_document` 전체 행 수도 0건(색인을 아직 실행한 적 없는 빈 테이블).
  - `flyway_schema_history`에 `V2` 적용 성공(`success = t`) 확인, `\d tb_document`로 `uq_tb_document_source UNIQUE CONSTRAINT` 생성 확인.
  - 만약 중복이 있었다면 `ALTER TABLE ... ADD CONSTRAINT ... UNIQUE` 자체가 `unique_violation`으로 실패해 Flyway migration이 실패 처리된다(조용히 통과하지 않음) — 별도 코드 없이 Postgres 기본 동작으로 보장됨.
- 동시 재색인 시 중복 방지 검토: 같은 source로 두 트랜잭션이 동시에 재색인하면, 먼저 커밋한 쪽이 성공하고 나중 트랜잭션은 insert 시점 UNIQUE 위반으로 롤백된다 — 중복 행 생성 불가. 별도 락(SELECT FOR UPDATE 등)은 추가하지 않았다(MVP 범위 판단, 필요 시 후속 작업으로 문서화).
- generated key 방어: `keyHolder.getKey()`가 null이면 `RagException`으로 변환(원인 불명 NPE 노출 방지).
- rollback 실제 검증: Testcontainers는 **추가하지 않기로 결정**했다(사용자 확인 완료 — 이 세션의 샌드박스는 Docker 데몬 접근이 기본적으로 막혀 있어 통합 테스트를 이 세션에서 실행/검증할 수 없고, 애초 5단계 원 지침도 "no Testcontainers"였음). 대신 `DocumentRepositoryTest`에 "Chunk insert 실패 시 예외를 삼키지 않고 전파한다"는 unit test를 추가해, `@Transactional`이 rollback을 트리거할 수 있는 조건(예외가 애플리케이션 코드에서 삼켜지지 않음)까지만 검증했다. 실제 PostgreSQL rollback·pgvector 저장 통합 테스트는 **테스트 공백으로 남아 있다** — 필요 시 `@Tag("integration")` + Testcontainers로 별도 소스셋을 구성해야 한다.
- 부분 성공 정책: 코드 변경 없음(기존 정책 유지), `DocumentIndexService`/`DocumentRepository` JavaDoc에 명시하고 Service 테스트로 "1번째 문서 저장 후 2번째 문서 Embedding 실패 → 1번째는 reindex 호출됨/2번째는 호출 안 됨" 시나리오를 추가 검증했다.
- 빈 문서 정책: 현재 동작(빈 문서도 저장, documentCount 포함/chunkCount 미포함) 유지 — MVP 단순성 우선 권장안을 그대로 채택. Service 테스트로 검증 추가.
- Controller 오류 처리 변경: `@ExceptionHandler(DataAccessException.class)` 추가. `RagException`과 동일하게 sanitized 500(`{"message": "문서 색인에 실패했습니다."}`)만 반환하고, 예외 상세(SQL/제약조건명 등)는 `log.warn(msg, e)`로만 남긴다. 다른 RuntimeException은 가로채지 않는다.
- 전체 테스트 실제 결과: `./gradlew test --no-daemon` 실행, **71개 테스트 전부 통과, 실패 0건**(로컬 `rag-postgres` 컨테이너가 떠 있어 `RagBackendApplicationTests`, `DocumentPropertiesBindingTest` 포함 전부 통과). 색인 관련 테스트만 보면 `DocumentIndexServiceTest` 5건, `DocumentRepositoryTest` 5건, `DocumentChunkRepositoryTest` 2건, `DocumentIndexControllerTest` 4건, 총 16건 통과.
- 통합 테스트 실제 결과: 없음(위 "rollback 실제 검증" 참고, 의도적으로 미추가 — 테스트 공백으로 명시).
- `git diff --check` 결과: 오류 없음(공백/충돌 마커 없음).
- commit/push: 실행하지 않았다.

## 완료 조건

```text
CLAUDE.md·관련 Skill 확인
→ 테스트 작성·수정
→ ./gradlew test 통과
→ Stop Hook 결과 확인
→ 문서 동기화
→ 위험 변경 사람 검수
→ 완료 기록 작성
```
