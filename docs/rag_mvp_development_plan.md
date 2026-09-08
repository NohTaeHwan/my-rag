# RAG MVP 개발 순서

## 목적

로컬 개발 환경에서 Markdown 문서를 넣고, 질문에 관련된 Chunk와 출처를 반환하는 RAG 흐름을 먼저 완성한다.

처음부터 운영 기능과 확장 기능을 모두 구현하지 않는다.

## 1. 초기 개발 구성

```text
로컬 개발 환경
├─ Spring Boot
├─ PostgreSQL + pgvector 컨테이너
└─ BGE-M3 Embedding API
    └─ 우선 DGX Spark의 API 호출
```

Embedding 서버 주소는 코드에 고정하지 않고 설정으로 분리한다.

```yaml
embedding:
  base-url: http://<dgx-spark-address>:8000
  model: BAAI/bge-m3
  dimension: 1024
```

나중에 로컬 Embedding 서버로 바꾸더라도 Spring Boot 코드는 수정하지 않고 설정만 변경할 수 있게 한다.

## 2. MVP 범위

```text
Markdown 문서
→ 문서 읽기
→ Chunk 분할
→ Embedding 생성
→ PostgreSQL 저장
→ 질문 Embedding 생성
→ 유사 Chunk 검색
→ LLM 답변 생성
→ 출처 반환
```

### 구현하지 않는 항목

초기 MVP에서는 다음을 제외한다.

- Redis
- 메시지 큐
- Scheduler
- Reranker
- Hybrid Search
- 복잡한 사용자 권한
- Streaming 응답
- 대규모 분산 처리
- 고가용성·자동 장애 전환

## 3. 개발 단계

### 1단계. PostgreSQL + pgvector 준비

- [x] PostgreSQL 컨테이너 실행 — `docker-compose.yml` (`pgvector/pgvector:pg17`)
- [x] 데이터베이스 생성 — `rag_db`
- [x] `vector` 확장 활성화 — `V1__init_rag_schema.sql`
- [x] `document` 테이블 생성 — `tb_document`
- [x] `document_chunk` 테이블 생성 — `tb_document_chunk`
- [x] `embedding vector(1024)` 컬럼 확인

기본 테이블 구조:

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    source VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE document_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES document(id),
    content TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    embedding vector(1024) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### 2단계. Spring Boot 기본 구성

- [x] Spring Boot 프로젝트 생성 — Java 17, Spring Boot 3.5.6
- [x] Web 계층 구성 — `spring-boot-starter-web`
- [x] PostgreSQL 연결 — `spring-boot-starter-jdbc` + `JdbcTemplate` (2026-09-04 5-1단계에서 MyBatis로 마이그레이션, 아래 5-1단계 참고)
- [x] Flyway 또는 초기 SQL로 테이블 관리 — `flyway-core`, `V1__init_rag_schema.sql`
- [x] 환경별 설정 파일 분리 — `application.yml` (env var 기반 기본값)
- [x] 기본 Health Check 추가 — `HealthController` (`/health`, DB 상태 포함)

권장 모듈 경계:

```text
Document
  └─ 문서와 Chunk 관리

Embedding
  └─ BGE-M3 API 호출

Retrieval
  └─ pgvector 유사도 검색

Answer
  └─ Context 구성과 LLM 호출
```

처음부터 패키지를 과도하게 나누기보다 이 책임을 기준으로 코드 위치를 정한다.

### 3단계. BGE-M3 연결

- [x] DGX Spark의 Embedding API 주소 확인 — Tailscale `http://100.90.113.121:8003/v1`
- [x] Spring Boot에서 단일 문장 호출 — `EmbeddingClient.embed(String)`
- [x] HTTP 응답 파싱 — `EmbeddingResponse`
- [x] 벡터 길이가 1,024인지 검증 — 실제 서버 호출로 확인 (`dimension=1024`)
- [x] Timeout과 오류 처리 추가 — connect/read timeout 설정, 실패·차원 불일치 시 `RagException`
- [x] 문서와 질문에 같은 모델을 사용하도록 설정 통합 — `EmbeddingProperties`/`EmbeddingClient` 단일화 (문서 색인·질문 검색 모두 재사용 예정)

요청 예시:

```http
POST /v1/embeddings
Content-Type: application/json

{
  "model": "BAAI/bge-m3",
  "input": "결제 완료 후 주문을 취소하려면?"
}
```

이 단계에서는 DB 저장보다 먼저 Embedding API 호출만 성공시키고 벡터 차원을 확인한다.

**로컬 실행 시 환경변수 설정**

`application.yml`의 `embedding.base-url` 기본값은 로컬 더미 주소(`http://127.0.0.1:8000/v1`)다. 실제 DGX Spark 등 외부 Embedding 서버를 쓰려면 `.env.example`을 복사해 `.env`를 만들고 실행 전 로드한다.

```bash
cp .env.example .env   # EMBEDDING_BASE_URL 등 실제 값으로 채우기
set -a; source .env; set +a
./gradlew bootRun
```

`.env`는 git에 커밋되지 않는다.

### 4단계. Markdown 문서와 Chunking

- [x] 지정한 Markdown 디렉터리에서 문서 읽기 — `DocumentSourceReader` (재귀 탐색, 정렬된 순서, symlink/숨김파일 제외)
- [x] 제목·본문·파일 경로 추출 — `MarkdownParser` (첫 H1 또는 파일명 fallback, heading 계층, front matter 제외)
- [x] Chunk 크기와 overlap 결정 — `MarkdownChunker` + `LengthMeasurer`(문자 수 근사치)
- [x] 너무 짧은 Chunk 제거 — 빈 Chunk는 생성하지 않고, 최소 길이 미만은 같은 섹션 내 이전(없으면 다음) Chunk에 병합. 병합 결과가 targetLength를 넘으면 병합하지 않음(아래 "짧은 Chunk 처리 정책" 참고)
- [x] 문서와 Chunk 식별자 생성 — `sourceKey`(정규화 상대경로), `chunkKey = sourceKey#index`

**결정된 수치와 정책 (tokenizer 의존성 없이 문자 수 근사치 사용, 실제 토큰 수 아님)**

`docs/`의 실제 heading 섹션 길이(평균 150~650자, 최대 ~1700자)를 분석해 결정했다.

```text
Chunk target length : 1200자 (근사치)
Overlap              : 150자
최소 길이            : 80자 (미만이면 같은 섹션의 이전 Chunk에 병합)
```

```yaml
document:
  source-directory: ${DOCUMENT_SOURCE_DIRECTORY:data/markdown}
  chunk:
    target-length: 1200
    overlap-length: 150
    min-length: 80
```

**분할 정책 (코드리뷰 반영으로 확정, 2026-09-03)**
- heading 경계를 우선 보존한다. 섹션이 target을 넘으면 문단(빈 줄 기준) 단위로 분할한다.
- fenced code block(``` / ~~~)은 항상 원자 단위로 취급하며, target을 초과해도 쪼개지 않는다(유일한 예외).
- 코드 블록이 아닌 문단 하나가 그래도 target을 넘으면 문자 단위로 최후 분할한다(문장 경계 분할은 도입하지 않음 — 한국어/영어 혼용 문서에서 신뢰도 낮은 문장 경계 탐지 로직을 추가하는 대신 결정적인 문자 단위 컷을 선택).
- **일반(코드 블록이 아닌) Chunk는 overlap을 적용한 뒤에도 항상 targetLength 이하여야 한다.** overlap을 붙였을 때 targetLength를 넘게 되면 그 overlap은 생략하고 원본 문단만 사용한다(overlap보다 길이 invariant를 우선).
- 문자 단위로 분할된 문단은 그 안에서만 overlap이 한 번 적용되며, 이후 문단 packing 단계에서 overlap을 중복 적용하지 않는다.
- overlap은 "직전 chunk 본문 끝에서부터 overlapLength만큼의 문자"를 의미하며, 그 구간에 포함된 줄바꿈도 그대로 포함한다.
- heading 텍스트는 Chunk 본문에 반복 삽입하지 않고 `headingPath`로 별도 보관한다(본문 overlap과 heading 반복을 구분).

**짧은 Chunk 처리 정책**
- minLength 미만인 Chunk는 같은 section 내 이전 Chunk에 병합을 시도한다. 이전 Chunk가 없으면(섹션의 첫 Chunk인 경우) 다음 Chunk에 병합을 시도한다.
- 병합 결과가 targetLength를 넘으면 병합하지 않고 그대로 둔다(짧더라도 targetLength invariant 우선).
- fenced code block은 병합 대상도, 병합받는 대상도 되지 않는다(항상 원자 단위 유지).
- 병합할 대상이 전혀 없으면(section에 Chunk가 하나뿐이면) 짧더라도 그대로 남긴다(의미 있는 정보 소실 방지).
- 병합은 같은 section 내에서만 일어나며, 다른 heading section의 Chunk와는 섞이지 않는다.

**fence(코드 블록) 판정 정책**
- 여는 fence의 종류(backtick/tilde)와 길이를 기억한다.
- 닫는 fence는 같은 종류이고 길이가 여는 fence 이상이어야 인정한다.
- 닫는 fence 줄에 marker 외의 텍스트가 있으면 닫는 것으로 인정하지 않는다(여는 fence의 info string, 예: ` ```java `는 허용).
- 문서/section 끝까지 닫히지 않은 fence는 끝까지 fence 내부로 취급한다.
- `MarkdownParser`와 `MarkdownChunker`가 `FenceTracker` 하나를 공유해 동일한 정책을 적용한다(중복 로직 제거).

**파일 수집 정책 보완**
- `.md` 확장자 비교는 대소문자를 구분한다(`.MD`, `.Md`는 수집하지 않음) — OS의 파일시스템 대소문자 구분 여부와 무관하게 항상 동일한 결과를 내기 위함.
- 파일 내용 맨 앞에 UTF-8 BOM이 있으면 제거한 뒤 파싱한다.
- front matter(`---`)가 닫히지 않으면 front matter로 취급하지 않고 일반 본문으로 남긴다.

문서 유형과 검색 결과를 확인하면서 조정한다. 실제 tokenizer(BGE-M3) 기준 검증은 5단계 이후 검색 품질 확인 시 별도로 진행한다.

### 5단계. 문서 색인

- [x] Markdown 파일 선택
- [x] 문서 레코드 저장
- [x] Chunk 생성
- [x] Chunk별 BGE-M3 Embedding 생성
- [x] Chunk와 벡터 저장
- [x] 색인 결과 수 반환

```text
POST /api/documents/index
```

요청 본문은 없다. 대상 파일이 하나도 없어도 오류가 아니라 0건 성공으로 처리한다.

응답 예시:

```json
{
  "documentCount": 2,
  "chunkCount": 8,
  "embeddingDimension": 1024
}
```

**확정된 설계 (5단계 구현 반영)**

- 패키지: `com.nohtaehwan.rag.indexing` — `DocumentIndexController` / `DocumentIndexService` / `DocumentIndexResponse` / `DocumentRepository` / `DocumentChunkRepository` / (패키지 전용) `EmbeddedChunk`.
- 처리 흐름: `DocumentSourceReader.readAll()` → 파일별 `MarkdownParser.parse()` → `MarkdownChunker.chunk()` → Chunk별 `EmbeddingClient.embed()` 순차 호출(트랜잭션 밖) → `DocumentRepository.reindex(title, sourceKey, chunks)`(문서 단위 트랜잭션) → 집계 후 응답.
- 재시도 정책: 재시도를 추가하지 않는다. `EmbeddingClient.embed()`가 1회 실패하면 즉시 예외를 전파하고(fail-fast) 색인 전체를 중단한다. 이미 저장된 이전 문서는 유지된다(문서 단위 트랜잭션이 각각 커밋되어 있으므로).
- 트랜잭션 경계: 문서 1건(기존 삭제 → 신규 `tb_document` insert → `tb_document_chunk` batch insert)을 하나의 트랜잭션으로 묶는다. 전체 배치를 하나의 트랜잭션으로 묶지 않는다 — 문서 A 저장 실패가 이미 성공한 다른 문서에 영향을 주지 않게 하기 위함이다. `@Transactional`은 `DocumentIndexService`가 아니라 별도 Bean인 `DocumentRepository.reindex()`에 둔다(Service가 자기 자신의 메서드를 호출하면 Spring 프록시 기반 AOP가 트랜잭션을 적용하지 못하는 self-invocation 문제 회피).
- 재색인 정책: 같은 `source`(sourceKey)로 다시 색인하면 `DELETE FROM tb_document WHERE source = ?`로 기존 문서를 지운다. `tb_document_chunk`는 FK `ON DELETE CASCADE`로 함께 삭제되므로 별도 삭제 SQL이 필요 없다. `source`는 null/빈 문자열이면 `RagException`으로 막는다.
- `tb_document.source` UNIQUE 제약(`V2__add_unique_constraint_to_document_source.sql`): 같은 source의 문서가 항상 최대 1건만 존재하도록 DB 레벨에서 보장한다. 같은 source로 동시에 두 재색인이 실행되면, 먼저 커밋한 트랜잭션은 성공하고 나중 트랜잭션은 insert 시점에 `unique_violation`으로 실패해 그 트랜잭션 전체가 롤백된다 — 중복 행은 생성되지 않고 나중 요청만 실패로 끝난다(별도 락은 두지 않음, MVP 범위에서 충분하다고 판단).
- generated key 처리(최종 구현, 5-1단계): `DocumentMapper.insert()`에 `@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")`를 붙여 생성된 id를 `DocumentInsertParameter.id`에 채운다. `getId()`가 null이면(생성된 id를 확인 못하면) 알 수 없는 NPE 대신 `RagException`을 던진다.
- pgvector 저장: embedding은 `Double` 리스트를 `[0.1,0.2,...]` 형태의 문자열로 변환해 `?::vector`로 바인딩한다(문자열 연결이 아닌 파라미터 바인딩). 저장은 5단계 당시 `JdbcTemplate.batchUpdate()`였으나, 5-1단계에서 MyBatis mapper의 `@InsertProvider`(`DocumentChunkSqlProvider`가 순수 Java로 만드는 multi-row INSERT)로 교체됐다(아래 5-1단계 참고).
- 예외 처리: 전역 예외 처리기가 없어 `DocumentIndexController`에 `@ExceptionHandler(RagException.class)`와 `@ExceptionHandler(DataAccessException.class)`를 최소로 추가했다. 둘 다 응답은 동일하게 `{"message": "문서 색인에 실패했습니다."}`이며, DB URL·SQL·문서 내용·embedding 벡터는 응답에 노출하지 않고 로그에만 남긴다. 그 외 RuntimeException은 가로채지 않는다(무분별한 예외 은폐 금지).
- 부분 성공(partial success) 정책: 색인은 문서 단위로 commit된다. 뒤에 오는 문서가 실패해도 앞서 이미 commit된 문서는 롤백되지 않는다. Embedding 생성이 실패한 문서는 저장 자체가 시도되지 않는다(`DocumentRepository.reindex()` 호출 전에 예외 발생). API 응답은 성공/실패 둘 중 하나지만, 실패 응답에도 내부적으로는 일부 문서가 이미 새로 저장된 상태로 남을 수 있다 — 어디까지 처리됐는지는 서버 로그(sourceKey 기준)로 확인한다.
- 빈 Markdown 문서 정책: Chunk가 0개인 문서도 색인 대상에서 제외하지 않는다. `tb_document` 행은 저장하고(`documentCount`에 포함), `tb_document_chunk`는 저장하지 않는다(`chunkCount`에는 미포함). 검색 단계에서는 Chunk가 없으므로 결과에 나타나지 않는다. MVP 단순성을 위한 의도된 동작이다.
- rollback 검증 상태:
  - **5단계 당시**: Spring `@Transactional`의 rollback 자체(런타임 예외 시 롤백)는 프레임워크가 보장하는 동작이라 별도로 재검증하지 않았다. 애플리케이션 코드가 DB 예외를 catch해서 삼키지 않고 그대로 전파하는지만 unit test(`DocumentRepositoryTest`)로 확인했다. 실제 rollback 여부, `vector(1024)` 컬럼 저장, 재색인 후 이전 Chunk cascade 삭제를 실제 PostgreSQL로 검증하는 통합 테스트는 없었다(테스트 공백으로 남김).
  - **5-1단계**: 로컬 `rag-postgres` 컨테이너가 기동된 상태를 활용해 `DocumentPersistenceIntegrationTest`를 추가하고, 실제 PostgreSQL에서 위 항목(rollback, pgvector 저장, cascade 삭제)을 전부 검증 완료했다. 아래 5-1단계 참고.

### 5-1단계. MyBatis 마이그레이션

- [x] MyBatis 의존성/설정 추가
- [x] `DocumentRepository`/`DocumentChunkRepository`/`HealthController`의 `JdbcTemplate` 사용을 MyBatis mapper로 교체
- [x] 실제 PostgreSQL 통합 테스트 추가

5단계에서 `JdbcTemplate`로 구현했던 DB 접근 코드를 MyBatis로 교체했다. API 동작·트랜잭션 경계·pgvector 저장 방식·재색인 정책은 그대로 유지하고, 데이터 접근 계층만 교체하는 작업이다.

**패키지 구조**

```text
indexing/mapper/DocumentMapper.java, DocumentChunkMapper.java, DocumentChunkSqlProvider.java, DocumentInsertParameter.java, ChunkRow.java
health/mapper/HealthMapper.java
```

`HealthController`는 기존 위치(`com.nohtaehwan.rag`)를 유지하고, mapper만 `health.mapper` 하위에 둔다. mapper 등록은 `@Mapper` annotation 방식(인터페이스마다 직접 부여)을 사용하고 `@MapperScan`은 쓰지 않는다 — `RagBackendApplication.java`를 건드리지 않기 위함이다.

**의존성**: `org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.4`. 이 라이브러리 자체는 Spring Boot 3.4.0 기준으로 빌드됐지만(POM 확인), 우리 프로젝트의 `io.spring.dependency-management`가 전이 의존성(`spring-boot-starter-jdbc` 등)을 3.5.6으로 강제 정렬해 충돌 없이 resolve된다(`./gradlew dependencies --configuration runtimeClasspath`로 확인).

**SQL 작성 방식(XML 미사용)**: 처음엔 XML mapper(`mapper/*.xml`)로 구현했으나, 사용자 요청으로 JPA `@Query`에 가까운 annotation 방식으로 바꿨다. `DocumentMapper`/`HealthMapper`는 `@Delete`/`@Insert`/`@Select`로 SQL을 메서드 위에 직접 적는다. `application.yml`의 `mybatis.mapper-locations` 설정도 XML이 없어져서 제거했다.

**generated key 처리**: `@Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")`를 `insert()` 메서드에 붙인다. insert 파라미터는 record가 아니라 일반 클래스(`DocumentInsertParameter`)다 — MyBatis가 생성된 id를 다시 채워 넣으려면 setter가 있는 mutable 객체여야 하기 때문이다(Lombok `@Getter`/`@Setter`/`@RequiredArgsConstructor`로 보일러플레이트 축소). id가 채워지지 않으면(`getId() == null`) 5단계와 동일하게 `RagException`을 던진다(NPE 노출 방지).

**Chunk batch insert 방식**: 문서마다 chunk 개수가 달라 정적 `@Insert` 문자열로는 표현이 안 된다. `ExecutorType.BATCH`(수동 `SqlSession` 필요)도 쓰지 않고, `@InsertProvider(type = DocumentChunkSqlProvider.class, method = "insertAll")`로 순수 Java 메서드가 `INSERT ... VALUES (...), (...), ...` SQL 문자열을 만든다. `chunks[i].content`처럼 MyBatis의 리스트 인덱스 파라미터 접근으로 각 행의 실제 값은 여전히 `#{...}` 바인딩이다(Provider는 SQL 텍스트의 자리표시자 개수만 만들 뿐, 실제 값을 문자열로 연결하지 않는다 — SQL Injection 위험 없음). `?::vector` 캐스팅도 `#{chunks[i].embeddingLiteral}::vector`로 그대로 파라미터 표현식에 건다.

**HealthController 예외 경로 변화**: 이전에는 `JdbcTemplate`이 던지는 예외가 그대로 전파됐다. MyBatis도 `SqlSessionTemplate`이 내부적으로 예외를 Spring `DataAccessException` 계열로 변환해서 던지므로, 예외 계층은 동일하게 유지된다. `HealthController`는 여전히 아무 예외 처리도 하지 않는다(전역 예외 처리기로 확장하지 않음, 관찰된 동작만 `HealthControllerTest`에 문서화).

**실제 PostgreSQL 통합 테스트(`DocumentPersistenceIntegrationTest`)**: 로컬 `rag-postgres`(pgvector) 컨테이너가 이미 떠 있어서 Testcontainers 없이 기존 관례(`RagBackendApplicationTests`처럼 DB 없으면 그대로 실패)를 따라 작성했다. 검증 내용:
1. 문서+Chunk 저장, 생성된 id로 조회
2. `vector_dims(embedding) = 1024` 확인(pgvector 실제 저장)
3. 같은 source 재색인 시 문서 교체 + 이전 Chunk가 `ON DELETE CASCADE`로 실제 삭제됨
4. `vector(1024)` 컬럼에 차원이 다른 벡터를 넣어 **실제 DB 오류**를 유발 → `DocumentRepository.reindex()`가 롤백되어 이전 문서가 그대로 남음(mock이 아닌 실제 rollback 검증)
5. `HealthMapper.selectOne() == 1`
6. 다른 source 문서는 영향받지 않음

**JdbcTemplate 제거 확인**: `src/main/java` 전체에서 `JdbcTemplate`/`NamedParameterJdbcTemplate`/`JdbcOperations` 참조 0건(재검색으로 확인). `src/test/java`에는 `DocumentPersistenceIntegrationTest`에서만 테스트 셋업/검증 전용으로 남아 있다(애플리케이션 코드 아님).

**변경하지 않은 것**: `V1`/`V2` Flyway migration, `tb_document`/`tb_document_chunk` 스키마, source UNIQUE 정책, 부분 성공 정책, embedding 정책, 검색/LLM 기능 — 전부 5단계 그대로.

### 6단계. 유사도 검색

- [x] 질문을 BGE-M3로 변환
- [x] pgvector cosine distance 검색
- [x] Top-K 개수 설정
- [x] 제목·내용·출처·거리 반환
- [x] 관련 문서가 실제로 상위에 나오는지 확인(실제 PostgreSQL 통합 테스트로 검증)

```text
GET /api/search?query=결제 취소 방법
```

요청 본문 없음, 파라미터는 `query` 하나(단일 GET 파라미터 규칙에 따라 `@RequestParam`으로 직접 받음). `query`가 null/빈 문자열/공백만 있으면 400.

응답 예시:

```json
{
  "results": [
    {
      "documentId": 1,
      "title": "주문 관리 문서",
      "source": "orders/cancel.md",
      "content": "결제 완료 후 주문을 취소하려면 ...",
      "chunkIndex": 3,
      "distance": 0.1245
    }
  ]
}
```

결과가 없으면 오류가 아니라 `results: []`를 반환한다. `distance`는 pgvector cosine distance 원본값(낮을수록 유사)이며 similarity로 변환하지 않는다. embedding 원본 벡터는 응답에 포함하지 않는다.

**확정된 설계**

- 패키지: `com.nohtaehwan.rag.retrieval` — `SearchController`/`SearchService`/`SearchResponse`/`SearchResult`/`RetrievalProperties` / (mapper 하위) `SearchMapper`/`SearchRow`. 5-1단계와 동일하게 XML 없이 annotation SQL(`@Select`)만 사용한다.
- 처리 흐름: `SearchController.search(query)` → `SearchService.search(query)`(query 검증 → `EmbeddingClient.embed(query)` → pgvector 리터럴 변환 → `SearchMapper.search(literal, topK)` → 응답 DTO 변환 → 로그) → 응답. embedding 호출과 DB 검색은 하나의 트랜잭션으로 묶지 않는다(읽기 전용 흐름이라 원자성 요구가 없음).
- Top-K 정책: 요청 파라미터로 받지 않고 `retrieval.top-k` 설정으로 고정(`RetrievalProperties`, 기본 5, `[1, 50]` 범위 벗어나면 애플리케이션 시작 시 실패 — `EmbeddingProperties`/`DocumentProperties`와 같은 compact constructor 검증 패턴).
- 검색 SQL(annotation, `SearchMapper`):
  ```sql
  SELECT c.document_id, d.title, d.source, c.content, c.chunk_index,
         c.embedding <=> #{queryEmbeddingLiteral}::vector AS distance
  FROM tb_document_chunk c
  JOIN tb_document d ON d.id = c.document_id
  ORDER BY c.embedding <=> #{queryEmbeddingLiteral}::vector
  LIMIT #{topK}
  ```
  `queryEmbeddingLiteral`(pgvector 리터럴 문자열)과 `topK` 둘 다 `@Param`으로 파라미터 바인딩(문자열 연결 없음). distance는 `SELECT`와 `ORDER BY`에서 동일한 표현식을 써서 정렬 기준과 반환값이 항상 일치하게 한다.
- **MyBatis 설정 추가**: `mybatis.configuration.map-underscore-to-camel-case: true`를 `application.yml`에 추가했다. 5단계/5-1단계 mapper는 컬럼명 변환이 필요 없는 케이스뿐이었는데, 이번엔 `document_id`→`documentId`, `chunk_index`→`chunkIndex` 자동 매핑이 처음 필요해서 추가함.
- **`SearchRow`는 record**: 순수 조회 결과라 쓰기(setter)가 필요 없다. MyBatis 3.5.15+(현재 번들 버전 3.5.17)는 record를 결과 매핑 대상으로 정식 지원한다 — `ChunkRow`/`DocumentInsertParameter`(5-1단계)를 class로 둔 것은 "쓰기 가능해야 하는 구조적 필요" 때문이었고, 쓰기가 필요 없는 `SearchRow`는 record가 맞는 선택이다.
- **400/500 예외 구분 신설**: 기존엔 `RagException`(500) 하나뿐이었다. 이번에 `com.nohtaehwan.rag.exception.InvalidRequestException`을 추가해 "잘못된 요청(400)"을 표현한다 — 특정 필드에 묶이지 않은 범용 이름으로, 이후 다른 엔드포인트의 검증 실패도 재사용한다. `RagException`은 계속 500(외부/DB/내부 실패) 전용. `SearchController`에 `@ExceptionHandler(InvalidRequestException.class)`(400) / `@ExceptionHandler(RagException.class)`(500) / `@ExceptionHandler(DataAccessException.class)`(500) 3개를 로컬로 둔다(전역 예외 처리기 없음, 기존 `DocumentIndexController` 패턴과 동일).
- 로깅: query 원문·embedding 벡터·외부 API 응답 원문은 로그에 남기지 않는다. `queryLength`/`topK`/`resultCount`만 기록.
- **실제 PostgreSQL 통합 테스트(`SearchPersistenceIntegrationTest`)**: 로컬 `rag-postgres` 컨테이너 사용, 기존 관례(Testcontainers 없음, DB 없으면 그대로 실패) 그대로. 모든 차원이 동일한/번갈아 나오는/정반대인 고정 벡터를 써서 cosine distance가 정확히 0/1/2가 되도록 설계 — 로컬 DB에 이미 있는 실제 색인 데이터가 섞여 있어도 이 세 값의 상대적 순서가 수학적으로 항상 보장되도록 했다. 검증 내용: 관련성 높은 Chunk가 낮은 distance로 먼저 반환됨, topK 제한과 반환 필드가 실제 저장값과 일치, query 벡터 차원이 다르면 실제 DB 오류가 발생.
  - **테스트 데이터 격리(코드리뷰 후속 수정)**: 최초 구현은 고정 source(`search-integration-test/doc.md`)를 썼는데, 같은 source가 실제 DB에 이미 있으면 insert가 UNIQUE 제약으로 실패하거나 cleanup이 다른(테스트가 만들지 않은) 데이터를 지울 수 있다는 지적을 받았다. `@BeforeEach`마다 `"search-integration-test/" + UUID.randomUUID() + ".md"`로 실행별 고유 source(`testSource` 인스턴스 필드)를 생성하도록 수정해, 저장·검증·cleanup이 전부 그 값 하나만 참조하게 했다 — 기존 실제 데이터와 충돌할 확률이 사실상 0이고, 전체 테이블 삭제 같은 파괴적 작업도 하지 않는다.
- **테스트 공백(의도적으로 남김)**: "검색 대상 Chunk가 전혀 없을 때(전체 테이블 0건) 빈 목록 반환"은 실제 통합 테스트로 검증하지 않았다 — 이 검색 SQL은 문서 단위로 필터링하지 않고 `tb_document_chunk` 전체를 대상으로 하기 때문에, 이를 진짜로 재현하려면 로컬 DB의 기존 실제 데이터를 전부 지워야 해서(파괴적 작업이라 하지 않음) 대신 `SearchServiceTest`(mock)에서 "mapper가 빈 목록을 반환하면 서비스도 빈 목록을 반환한다"로 애플리케이션 레벨 처리만 검증했다. `LIMIT`을 포함한 빈 테이블 조회 자체는 SQL 표준 동작이라 별도 실증이 필요하지 않다고 판단.

이 단계까지 끝나면 LLM 없이도 다음을 검증할 수 있다.

```text
질문 → 관련 Chunk 검색 → 출처 확인
```

### 7단계. LLM 답변 생성

검색 결과가 정상적으로 나오는 것을 확인한 뒤 LLM을 연결한다.

```text
질문
→ 질문 Embedding
→ 관련 Chunk 검색
→ Context 구성
→ LLM 호출
→ 답변과 출처 반환
```

답변 API 예시:

```text
POST /api/answers
```

요청:

```json
{
  "question": "결제 완료 후 주문을 취소하려면?"
}
```

응답:

```json
{
  "answer": "...",
  "sources": [
    {
      "documentId": 1,
      "title": "주문 관리 문서",
      "chunkIndex": 3
    }
  ]
}
```

답변에 검색된 문서만 사용하도록 Prompt를 구성하고, 근거가 없을 때는 모른다고 답하도록 처리한다.

### 8단계. 테스트와 운영 준비

- [ ] Chunking 단위 테스트
- [ ] Embedding API Client 테스트
- [ ] 검색 Repository 테스트
- [ ] 문서 색인 통합 테스트
- [ ] 질문·검색·답변 E2E 테스트
- [ ] Embedding 서버 장애 테스트
- [ ] DB 백업 방식 정리
- [ ] 로그와 요청 식별자 추가
- [ ] Docker Compose로 로컬 실행 정리

## 4. 지금 바로 시작할 작업

가장 먼저 할 일은 PostgreSQL + pgvector를 실행하는 것이다.

```text
1. 로컬 PostgreSQL + pgvector 실행
2. vector 확장 확인
3. document/document_chunk 테이블 생성
4. Spring Boot에서 DB 연결 확인
5. DGX Spark BGE-M3에 테스트 요청
6. 1,024차원 벡터 확인
```

현재는 LLM 호출이나 완성된 채팅 API부터 만들지 않는다. 먼저 아래 한 문장을 성공시키는 것을 첫 번째 완료 기준으로 삼는다.

```text
Markdown 문장 하나
→ BGE-M3 1024차원 벡터 생성
→ PostgreSQL에 저장
→ DB에서 다시 조회
```

## 5. 첫 번째 완료 기준

다음 요청과 결과가 동작하면 1차 개발 환경이 준비된 것이다.

```text
문서 파일 1개
→ Chunk 여러 개 생성
→ 각 Chunk에 Embedding 생성
→ PostgreSQL 저장
→ 질문 1개 입력
→ 관련 Chunk Top-K 반환
```

## 6. 이후 확장 순서

MVP가 동작한 뒤 필요성을 확인하며 다음 순서로 추가한다.

```text
1. Redis 캐시
2. 문서 색인 비동기 처리
3. Reranker
4. BM25 + Vector Hybrid Search
5. 관리자용 색인 상태 조회
6. 사용자별 문서 권한
7. Docker Compose 기반 DGX Spark 배포
8. 모니터링과 백업 자동화
```

각 기능은 검색 품질이나 운영 문제를 실제로 확인한 뒤 추가한다. 기술을 미리 넣는 것을 목표로 하지 않는다.

## 요약

```text
PostgreSQL 준비
→ Spring Boot 연결
→ BGE-M3 연결
→ Chunking
→ 문서 색인
→ 유사도 검색
→ LLM 답변
→ 출처 반환
→ 테스트와 운영 정리
```

첫 시작은 `PostgreSQL + pgvector 실행과 Spring Boot DB 연결`이다. 그 다음 DGX Spark의 BGE-M3 API를 연결해 벡터 하나를 생성하고 저장하는 흐름부터 구현한다.
