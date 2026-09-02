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

- [ ] 지정한 Markdown 디렉터리에서 문서 읽기
- [ ] 제목·본문·파일 경로 추출
- [ ] Chunk 크기와 overlap 결정
- [ ] 너무 짧은 Chunk 제거
- [ ] 문서와 Chunk 식별자 생성

초기 기준은 고정된 숫자를 정답으로 보지 않고, 다음 정도에서 시작한다.

```text
Chunk: 약 500~800 토큰
Overlap: 약 50~100 토큰
```

문서 유형과 검색 결과를 확인하면서 조정한다.

### 5단계. 문서 색인

- [ ] Markdown 파일 선택
- [ ] 문서 레코드 저장
- [ ] Chunk 생성
- [ ] Chunk별 BGE-M3 Embedding 생성
- [ ] Chunk와 벡터 저장
- [ ] 색인 결과 수 반환

첫 API는 단순하게 만든다.

```text
POST /api/documents/index
```

응답 예시:

```json
{
  "documentId": 1,
  "chunkCount": 8,
  "embeddingDimension": 1024
}
```

**설계 유의사항 (3단계 EmbeddingClient 검수 중 발견, 5단계 구현 시 반영)**

- 재시도 정책: 현재 `EmbeddingClient.embed()`는 재시도 없이 1회 호출 실패 시 바로 예외를 전파한다. 3단계(단일 문장 호출)에서는 의도된 설계이지만, 5단계에서 Chunk 여러 개를 반복 호출하면 일시적 네트워크 오류로 색인 전체가 실패할 수 있다. 색인 API에서 Chunk 단위 재시도 또는 실패 Chunk만 별도 처리하는 정책을 검토한다.
- 트랜잭션 경계: "Embedding 생성 → PostgreSQL 저장"을 하나의 트랜잭션으로 묶을 경우, 외부 API 호출(embed)을 트랜잭션 내부에 두면 트랜잭션 유지 시간이 길어진다. Embedding 생성은 트랜잭션 밖에서 먼저 수행하고, DB 저장만 트랜잭션으로 묶는 방식을 우선 검토한다.

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
