# 개발 작업별 claude-kit 준수 체크리스트

> 기능 하나를 개발하거나 수정할 때마다 복사해 작성한다.
> 체크하지 못한 항목은 완료로 표시하지 않고 사유를 남긴다.

## 작업 정보

- 작업명: 5-1단계 MyBatis 마이그레이션 (DB 접근 계층 JdbcTemplate → MyBatis)
- 작업 일자: 2026-09-04
- 관련 문서: `.hermes/plans/2026-09-04_154914-mybatis-migration-stage-5-1.md`, `docs/rag_mvp_development_plan.md`(5-1단계)
- 담당: Claude Code (사용자 승인 하에 진행)

## 1. 작업 시작 전

- [x] 요청 범위와 완료 기준을 정리했다(계획 문서 브리핑 후 사용자 승인 받고 시작).
- [x] 요구사항이 모호한 부분을 먼저 확인했다 — 4가지 판단 지점(MyBatis 버전, generated key 전략, batch insert 방식, Testcontainers 여부)에 대해 의견을 제시하고 사용자 승인받음.
- [x] `CLAUDE.md`를 읽었다 — 기존에 "JdbcTemplate 구조 사용, MyBatis 임의 추가 금지" 규칙이 있어 이번 작업으로 갱신 필요함을 확인하고 반영함(아래 참고).
- [x] 참고 문서 이정표에서 관련 문서를 읽었다.
- [x] 변경 대상의 기존 구조와 사용처를 확인했다 — `JdbcTemplate` 사용처 3곳(`HealthController`, `DocumentRepository`, `DocumentChunkRepository`) 재확인.
- [x] 기존 API 변경이 아니라 데이터 접근 계층 교체이므로 Spring API Skill 라우팅 대상 아님(API 경로·요청·응답 불변).

## 2. 설계·구현

- [x] 기존 Controller → Service → 데이터 접근 구조를 유지했다(mapper는 데이터 접근 계층 내부 구현 교체일 뿐).
- [x] 프로젝트의 Java 17·Spring Boot 3.5.6·Gradle을 따랐다.
- [x] MyBatis 버전(`3.0.4`)이 Spring Boot 3.5.6과 충돌 없이 resolve되는지 `./gradlew dependencies`로 직접 확인했다(POM은 3.4.0 기준이지만 프로젝트 BOM이 전이 의존성을 3.5.6으로 정렬).
- [x] JPA·MyBatis-Plus·QueryDSL·WebFlux·Swagger/OpenAPI를 추가하지 않았다.
- [x] `@MapperScan` 대신 `@Mapper` per-interface 방식을 선택해 `RagBackendApplication.java`를 건드리지 않았다.
- [x] 클래스·메서드 역할과 필요한 파라미터·예외 주석을 작성했다.
- [x] Service/Repository 레이어의 로그를 유지했다(내용/벡터는 로그에 남기지 않음).
- [x] 비즈니스 예외는 기존 `RagException`을 그대로 재사용했다(신규 예외 클래스 없음).
- [x] 민감 정보와 자격 증명을 코드·설정·로그에 기록하지 않았다. `application.yml`은 CLAUDE.md 승인 절차(설명 → 승인 → 토큰 생성)를 거쳐 수정했다.
- [x] `V1`/`V2` Flyway migration은 수정하지 않았다.
- [x] 문서 delete → insert → chunk insert 순서, source null/blank 방어, generated key null 방어, 부분 성공 정책을 그대로 보존했다.

## 3. 테스트·검증

- [x] 변경 계층(Repository/Controller)의 테스트를 mapper mock 경계로 교체했다(`DocumentRepositoryTest`, `DocumentChunkRepositoryTest`, `HealthControllerTest` 신규).
- [x] `./gradlew test --no-daemon`을 실행했다.
- [x] 테스트 결과를 실제 출력으로 확인했다 — **79/79 통과, 실패 0건**(로컬 `rag-postgres` 컨테이너 기동 상태).
- [x] 실제 PostgreSQL 통합 테스트(`DocumentPersistenceIntegrationTest`, 5건)를 추가하고 실제로 실행해 통과를 확인했다 — rollback, cascade 삭제, `vector_dims=1024` 저장, health mapper까지 mock이 아닌 실제 DB로 검증.
- [x] mapper annotation/Provider 등록 자체는 `RagBackendApplicationTests`(전체 context 기동)로 커버된다 — SQL 문법 오류가 있었다면 context 시작 시점에 실패했을 것. (최초엔 XML mapper로 구현했다가, 아래 "SQL 작성 방식 변경" 참고해 annotation 방식으로 다시 전환하고 재검증함)
- [x] claude-kit Stop Hook의 컴파일·대응 테스트 결과를 확인했다.
- [x] 테스트·빌드 실패를 남긴 채 완료 처리하지 않았다.

## 4. 문서·사람 검수

- [x] API 경로·요청·응답 불변 — README API 목록 갱신 불필요.
- [x] 프로젝트 구조 변경(신규 mapper 패키지) — README 프로젝트 구조 섹션 갱신.
- [x] DB 스키마 변경 없음(마이그레이션 불변) — migration 문서 갱신 대상 아님.
- [x] 설계 결정이 바뀌어 `docs/rag_mvp_development_plan.md`에 5-1단계 섹션을 신설했다.
- [x] **`CLAUDE.md` 자체가 이번 변경과 모순되는 상태였다** — "JdbcTemplate 구조 사용, MyBatis 임의 추가 금지" 문구를 실제 상태(MyBatis 사용)에 맞게 갱신했다. `docs/claude_kit_development_checklist.md` 템플릿의 관련 문구도 함께 갱신.
- [ ] claude-kit Review Hook의 검수 결과를 확인했다. (다음 Stop Hook 실행 후 별도 기록 예정)
- [ ] Critical·High 위험 변경은 사람이 검수했다. (데이터 접근 계층 전체 교체 + 실제 DB 트랜잭션 rollback 관련 — 사람 검수 권장)
- [x] 외부 부작용·권한·트랜잭션·동시성·삭제·시크릿 변경 여부를 직접 확인했다(트랜잭션 경계·재색인 정책 불변, 신규 시크릿 없음, `application.yml` 변경은 mapper 위치 설정 1줄뿐).

## 5. 완료 기록

- **MyBatis 의존성 추가**: 이번 5-1단계에서 `implementation 'org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.4'`를 `build.gradle`에 처음 추가했다(그전엔 `spring-boot-starter-jdbc`+`JdbcTemplate`만 있었음).
- **SQL 작성 방식 변경(같은 날, 사용자 요청으로 전환)**: 위 의존성을 추가한 뒤, 최초 구현은 XML mapper(`src/main/resources/mapper/**/*.xml`)였다. 이후 사용자가 "JPA `@Query`처럼 annotation에 SQL을 바로 쓰고 싶다"고 요청해, MyBatis 의존성은 그대로 두고 SQL 작성 방식만 XML → annotation(`@Select`/`@Insert`/`@Delete`/`@Options`/`@InsertProvider`)으로 전환했다 — 이 전환 자체는 새 라이브러리 추가가 아니다(이미 추가된 MyBatis의 다른 SQL 작성 방식일 뿐). XML 3개 파일과 `application.yml`의 `mybatis.mapper-locations` 설정을 삭제했다(둘 다 사용자 승인 받고 진행).
- 변경 파일:
  - 신규: `src/main/java/com/nohtaehwan/rag/indexing/mapper/{DocumentMapper,DocumentChunkMapper,DocumentChunkSqlProvider,DocumentInsertParameter,ChunkRow}.java`
  - 신규: `src/main/java/com/nohtaehwan/rag/health/mapper/HealthMapper.java`
  - 삭제: `src/main/resources/mapper/indexing/{DocumentMapper,DocumentChunkMapper}.xml`, `src/main/resources/mapper/health/HealthMapper.xml`(전환 과정에서 생성 후 삭제)
  - 수정: `build.gradle`(mybatis-spring-boot-starter:3.0.4 추가), `src/main/resources/application.yml`(mapper-locations는 추가했다가 XML 제거 후 다시 삭제 — 최종적으로 변경 없음)
  - 수정: `DocumentRepository.java`, `DocumentChunkRepository.java`, `HealthController.java` (JdbcTemplate → mapper 호출로 교체)
  - 수정: `DocumentRepositoryTest.java`, `DocumentChunkRepositoryTest.java` (mapper mock 경계로 교체)
  - 신규: `src/test/java/com/nohtaehwan/rag/HealthControllerTest.java`, `src/test/java/com/nohtaehwan/rag/indexing/DocumentPersistenceIntegrationTest.java`
  - 수정: `CLAUDE.md`, `README.md`, `docs/rag_mvp_development_plan.md`, `docs/claude_kit_development_checklist.md`, 본 체크리스트
- 실행한 검증 명령: `./gradlew dependencies --configuration runtimeClasspath --no-daemon`, `./gradlew test --no-daemon`(전체 및 개별, XML→annotation 전환 후 재실행 포함, 코드리뷰 후속 문서 수정 후 재실행 포함), `git diff --check`, `git status --short --untracked-files=all`
- 실제 검증 결과: 전체 79개 테스트 전부 통과(실패 0), annotation 전환 후·코드리뷰 후속 수정 후 모두 동일(재실행 확인). `DocumentPersistenceIntegrationTest` 5건 포함(실제 PostgreSQL, `@InsertProvider`의 동적 multi-row INSERT가 실제 DB에서 정상 동작함을 확인). `application-level JdbcTemplate` 참조 0건(재검색 확인, 테스트 셋업용 1곳만 의도적으로 남김). `git diff --check` 오류 없음.
- **코드리뷰 에이전트 검토(2026-09-04, 같은 날 후속)**: 조건부 승인 — 기능/테스트는 승인, XML→annotation 전환 후 문서·JavaDoc이 갱신 안 된 7곳을 지적받음(Critical/High/Medium 기능 결함은 0건). 지적 7건 전부 실제 파일 대조로 사실 확인 후 전부 수정함:
  - `DocumentRepository.java`/`DocumentChunkRepository.java`/`ChunkRow.java`의 JavaDoc이 삭제된 XML mapper·`<foreach>`를 참조하던 것을 실제 구현(`@Delete`/`@Insert`/`@InsertProvider`/`DocumentChunkSqlProvider`)에 맞게 수정
  - `docs/rag_mvp_development_plan.md`의 generated key 설명(`KeyHolder.getKey()` → `@Options(useGeneratedKeys=...)`)과 batch insert 설명(`<foreach>` → `@InsertProvider`) 수정
  - 5단계 시점 테스트 공백과 5-1단계 해소 내용이 섞여 있던 것을 "5단계 당시/5-1단계" 구조로 분리
  - 본 체크리스트의 "새 라이브러리 추가 없음" 문구가 문맥 없이 오해될 수 있어, MyBatis 의존성 자체는 5-1단계에서 추가했다는 사실을 별도 bullet으로 명시
  - 선택적 개선사항(Provider 단위 테스트, 대량 chunk 시 파라미터 수 제한 검토, 통합 테스트 `@Tag` 분리 등)은 이번엔 반영하지 않음 — 필요 시 별도 작업으로 진행
- 미완료 항목과 사유: 없음(문서 정합성 지적 사항 전부 반영, 재검증 통과).
- 사람 검수가 필요한 사항: 데이터 접근 계층 전체 교체(`DocumentRepository`/`DocumentChunkRepository`/`HealthController`), 실제 rollback 검증 결과(`DocumentPersistenceIntegrationTest`), `DocumentChunkSqlProvider`의 동적 SQL 생성 로직. (코드리뷰 에이전트는 "문서 정리 후 사람 검수 단계로 넘겨도 되는 상태"로 판단함.)

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
