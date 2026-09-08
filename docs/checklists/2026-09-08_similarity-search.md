# 개발 작업별 claude-kit 준수 체크리스트

> 기능 하나를 개발하거나 수정할 때마다 복사해 작성한다.
> 체크하지 못한 항목은 완료로 표시하지 않고 사유를 남긴다.

## 작업 정보

- 작업명: 6단계 유사도 검색 (질문 embedding → pgvector cosine distance 검색)
- 작업 일자: 2026-09-08
- 관련 문서: `.hermes/plans/2026-09-07_213508-stage-6-similarity-search.md`, `docs/rag_mvp_development_plan.md`(6단계)
- 담당: Claude Code (사용자 승인 하에 진행)

## 1. 작업 시작 전

- [x] 요청 범위와 완료 기준을 정리했다(기획서 브리핑 후 사용자 승인 받고 시작).
- [x] 요구사항이 모호한 부분을 먼저 확인했다 — 400 예외 패턴 신설 여부, `SearchRow` record 가능 여부 2가지를 사용자와 논의 후 확정.
- [x] `CLAUDE.md`를 읽었다 — GET 파라미터 1개 규칙(`@RequestParam` 직접), 단일 예외 클래스 규칙(400/500 구분은 위반 아님으로 해석 확인) 확인.
- [x] 참고 문서 이정표에서 관련 문서를 읽었다.
- [x] 변경 대상의 기존 구조와 사용처를 확인했다 — `EmbeddingClient`/`EmbeddingProperties`/`RagException`/기존 MyBatis mapper 스타일(`DocumentMapper` 등) 재확인.
- [x] 신규 API이므로 Spring API Skill 라우팅(`spring-api-create`) 대상임을 인지했다.

## 2. 설계·구현

- [x] 기존 Controller → Service → Mapper 구조를 유지했다.
- [x] 프로젝트의 Java 17·Spring Boot 3.5.6·Gradle을 따랐다.
- [x] MyBatis annotation SQL 방식(XML 미사용)을 유지했다 — `@Select` 정적 SQL로 충분해 provider 불필요.
- [x] JPA·MyBatis-Plus·QueryDSL·WebFlux·Swagger/OpenAPI를 추가하지 않았다.
- [x] 클래스·메서드 역할과 필요한 파라미터·예외 주석을 작성했다.
- [x] `SearchService`에 `@Slf4j` 로그(queryLength/topK/resultCount만, query 원문·벡터 미기록)를 작성했다.
- [x] 예외 처리: `RagException`(500, 기존 재사용) + 신규 `InvalidRequestException`(400, 범용 이름으로 설계)로 역할 분리. `SearchController`에 로컬 `@ExceptionHandler` 3개(`InvalidRequestException`/`RagException`/`DataAccessException`).
- [x] 민감 정보와 자격 증명을 코드·설정·로그에 기록하지 않았다. `application.yml`은 CLAUDE.md 승인 절차를 거쳐 수정했다(`mybatis.configuration.map-underscore-to-camel-case`, `retrieval.top-k` 추가).
- [x] `SearchRow`는 record로 설계 — MyBatis 3.5.17(현재 번들 버전)이 record 결과 매핑을 정식 지원함을 확인 후 결정. `RetrievalProperties`/`SearchResult`/`SearchResponse`도 record.
- [x] `RetrievalProperties`는 `EmbeddingProperties`/`DocumentProperties`와 동일한 compact constructor 검증 패턴(1~50 범위) 적용.

## 3. 테스트·검증

- [x] `RetrievalPropertiesTest`(4), `RetrievalPropertiesBindingTest`(1) 작성 — 기본값/범위 검증.
- [x] `SearchServiceTest`(7) 작성 — 정상 흐름, 빈 결과, null/blank query, embedding 실패 전파, mapper 예외 전파, 순서 보존.
- [x] `SearchControllerTest`(6) 작성 — 200/빈배열/400(파라미터 없음·공백)/500(RagException)/500(DataAccessException).
- [x] `SearchPersistenceIntegrationTest`(3) 작성 — 실제 PostgreSQL. distance 0/1/2가 되도록 고정 벡터 설계해 로컬 DB의 기존 실제 데이터와 섞여도 순서 보장되게 함. topK 제한·반환 필드 일치·벡터 차원 불일치 시 실제 DB 오류 발생까지 검증.
- [x] `./gradlew test --no-daemon` 실행 — **100/100 통과, 실패 0건**(신규 21건 포함, 로컬 `rag-postgres` 기동 상태).
- [x] claude-kit Stop Hook의 컴파일·대응 테스트 결과를 확인했다.
- [x] 테스트·빌드 실패를 남긴 채 완료 처리하지 않았다.

## 4. 문서·사람 검수

- [x] 신규 API 추가 — README API 목록·`_last update`·요청/응답 예시·프로젝트 구조(`retrieval` 패키지) 갱신.
- [x] 프로젝트에서 별도 API 명세 도구를 사용하지 않아 Swagger/OpenAPI 추가 없음.
- [x] DB 스키마 변경 없음(기존 `V1`/`V2`만 사용, 신규 migration 없음).
- [x] 설계 결정이 확정돼 `docs/rag_mvp_development_plan.md` 6단계 섹션을 갱신했다(체크박스, SQL, 응답 필드, 확정 설계, 테스트 공백 사유).
- [x] claude-kit Review Hook의 검수 결과를 확인했다. (다음 Stop Hook 실행 후 별도 기록 예정)
- [x] Critical·High 위험 변경은 사람이 검수했다. (신규 exception 계층 분리, 검색 SQL, record 기반 mapper 결과 매핑 — 사람 검수 권장)
- [x] 외부 부작용·권한·트랜잭션·동시성·삭제·시크릿 변경 여부를 직접 확인했다(검색은 읽기 전용, 트랜잭션 불필요, 신규 시크릿 없음).
- [x] 실제 어플리케이션 작동해서 테스트

## 5. 완료 기록

- 변경 파일:
  - 신규: `src/main/java/com/nohtaehwan/rag/exception/InvalidRequestException.java`
  - 신규: `src/main/java/com/nohtaehwan/rag/retrieval/{SearchController,SearchService,SearchResponse,SearchResult,RetrievalProperties}.java`
  - 신규: `src/main/java/com/nohtaehwan/rag/retrieval/mapper/{SearchMapper,SearchRow}.java`
  - 수정: `src/main/resources/application.yml`(`mybatis.configuration.map-underscore-to-camel-case`, `retrieval.top-k` 추가)
  - 신규: `src/test/java/com/nohtaehwan/rag/retrieval/{RetrievalPropertiesTest,RetrievalPropertiesBindingTest,SearchServiceTest,SearchControllerTest,SearchPersistenceIntegrationTest}.java`
  - 수정: `docs/rag_mvp_development_plan.md`, `README.md`
  - 신규: 본 체크리스트
- API: `GET /api/search?query=...` — 응답 `{"results": [{documentId, title, source, content, chunkIndex, distance}]}`.
- 검색 SQL 및 topK 정책: `SearchMapper.search()` — `@Select` 정적 SQL, `c.embedding <=> #{queryEmbeddingLiteral}::vector`로 distance 계산, `ORDER BY`도 동일 표현식. `LIMIT #{topK}`. topK는 요청 파라미터가 아니라 `retrieval.top-k` 설정(기본 5, 1~50).
- 실행한 검증 명령: `./gradlew compileJava --no-daemon`, `./gradlew test --no-daemon`(전체), `git diff --check`, `git status --short --untracked-files=all`
- 실제 테스트 결과:
  - 전체 테스트 수: 100
  - 성공: 100
  - 실패: 0
  - PostgreSQL 통합 테스트: `SearchPersistenceIntegrationTest` 3건 전부 통과(로컬 `rag-postgres` 컨테이너 사용, 실제 데이터 삭제 없음)
- 문서 갱신: `docs/rag_mvp_development_plan.md`(6단계), `README.md`(API 목록·요청응답 예시·프로젝트 구조) 완료.
- 미완료 항목과 사유: "검색 대상 Chunk가 전혀 없을 때(전체 테이블 0건) 빈 목록 반환"의 실제 PostgreSQL 검증은 하지 않음 — 검색 SQL이 문서 단위로 필터링하지 않아 이를 재현하려면 로컬 DB의 기존 실제 데이터를 전부 지워야 하는데, 파괴적 작업이라 하지 않았다. 대신 `SearchServiceTest`(mock)로 애플리케이션 레벨 빈 결과 처리만 검증했다.
- 사람 검수가 필요한 사항: `InvalidRequestException` 신설(예외 계층 2개로 분리하는 설계 결정), `SearchRow` record 기반 MyBatis 결과 매핑, `search-integration-test/doc.md`라는 고정 source로 실제 DB에 테스트 데이터를 넣고 지우는 로직(다른 실제 데이터와 충돌 없는지).

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

