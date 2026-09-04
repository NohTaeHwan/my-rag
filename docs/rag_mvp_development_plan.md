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
- [x] PostgreSQL 연결 — `spring-boot-starter-jdbc` + `JdbcTemplate`
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
- generated key 처리: `KeyHolder.getKey()`가 null이면(생성된 id를 확인 못하면) `.longValue()`로 인한 알 수 없는 NPE 대신 `RagException`을 던진다.
- pgvector 저장: embedding은 `Double` 리스트를 `[0.1,0.2,...]` 형태의 문자열로 변환해 `?::vector`로 바인딩한다(문자열 연결이 아닌 PreparedStatement 파라미터). `tb_document_chunk`는 `JdbcTemplate.batchUpdate()`로 저장한다.
- 예외 처리: 전역 예외 처리기가 없어 `DocumentIndexController`에 `@ExceptionHandler(RagException.class)`와 `@ExceptionHandler(DataAccessException.class)`를 최소로 추가했다. 둘 다 응답은 동일하게 `{"message": "문서 색인에 실패했습니다."}`이며, DB URL·SQL·문서 내용·embedding 벡터는 응답에 노출하지 않고 로그에만 남긴다. 그 외 RuntimeException은 가로채지 않는다(무분별한 예외 은폐 금지).
- 부분 성공(partial success) 정책: 색인은 문서 단위로 commit된다. 뒤에 오는 문서가 실패해도 앞서 이미 commit된 문서는 롤백되지 않는다. Embedding 생성이 실패한 문서는 저장 자체가 시도되지 않는다(`DocumentRepository.reindex()` 호출 전에 예외 발생). API 응답은 성공/실패 둘 중 하나지만, 실패 응답에도 내부적으로는 일부 문서가 이미 새로 저장된 상태로 남을 수 있다 — 어디까지 처리됐는지는 서버 로그(sourceKey 기준)로 확인한다.
- 빈 Markdown 문서 정책: Chunk가 0개인 문서도 색인 대상에서 제외하지 않는다. `tb_document` 행은 저장하고(`documentCount`에 포함), `tb_document_chunk`는 저장하지 않는다(`chunkCount`에는 미포함). 검색 단계에서는 Chunk가 없으므로 결과에 나타나지 않는다. MVP 단순성을 위한 의도된 동작이다.
- rollback 실제 검증 범위: Spring `@Transactional`의 rollback 자체(런타임 예외 시 롤백)는 프레임워크가 보장하는 동작이라 별도로 재검증하지 않았다. 대신 애플리케이션 코드가 DB 예외를 catch해서 삼키지 않고 그대로 전파하는지는 unit test(`DocumentRepositoryTest`)로 확인했다. 실제 PostgreSQL에 대한 rollback/pgvector 저장 통합 테스트(Testcontainers 등)는 이번 범위에서 추가하지 않았다 — 이유는 아래 "테스트 공백" 참고.
- **테스트 공백(의도적으로 남김)**: 실제 rollback 여부, `vector(1024)` 컬럼 저장, 재색인 후 이전 Chunk cascade 삭제를 실제 PostgreSQL로 검증하는 통합 테스트는 없다. Testcontainers 도입은 이번 작업의 직접 범위를 넘어선다고 판단해 추가하지 않았다(기존 stage 5 지침도 "no Testcontainers"였음). 대신 로컬에서 `docker compose`로 띄운 실제 Postgres(`rag-postgres` 컨테이너)에 V2 migration을 적용해 마이그레이션 자체가 성공하는지, UNIQUE 제약이 실제로 생성되는지는 수동으로 확인했다(`docs/checklists/2026-09-04_document-indexing.md` 참고). 추후 통합 테스트가 필요하면 `@Tag("integration")` + Testcontainers로 별도 소스셋 분리를 권장한다.

### 6단계. 유사도 검색

- [ ] 질문을 BGE-M3로 변환
- [ ] pgvector cosine distance 검색
- [ ] Top-K 개수 설정
- [ ] 제목·내용·출처·거리 반환
- [ ] 관련 문서가 실제로 상위에 나오는지 확인

검색 SQL 예시:

```sql
SELECT id,
       document_id,
       content,
       chunk_index,
       embedding <=> CAST(:query_embedding AS vector) AS distance
FROM document_chunk
ORDER BY embedding <=> CAST(:query_embedding AS vector)
LIMIT :top_k;
```

검색 API 예시:

```text
GET /api/search?query=결제 취소 방법
```

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
