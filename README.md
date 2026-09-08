# my-rag — RAG Backend Project

PostgreSQL + pgvector 기반 검색 증강 생성(RAG) 백엔드 프로젝트입니다.

Markdown 문서를 넣고, 질문에 관련된 Chunk와 출처를 결과값으로 반환하는 흐름을 목표로 합니다.

현재는 프로토타입이지만 해당 내용을 활용해서 여러 상황에서 사용하는 RAG 로 사용하는게 주 목표입니다.




## 기술 스택

| 구분 | 내용 |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.5.6 (Spring MVC) |
| Data Access | MyBatis (mapper interface + annotation SQL, XML 미사용, ORM 미사용) |
| Database | PostgreSQL 17 + pgvector |
| Migration | Flyway |
| Embedding | BGE-M3 (OpenAI 호환 `/v1/embeddings` API) |
| Build | Gradle (`./gradlew`) |

## 시작하기

### 1. 사전 준비

- Java 17
- Docker / Docker Compose (PostgreSQL 실행용)
- BGE-M3 Embedding API 접근 정보 (예: DGX Spark 주소)

### 2. PostgreSQL 실행

```bash
docker compose up -d
```

`docker-compose.yml`이 `pgvector/pgvector:pg17` 이미지를 `5432` 포트로 띄웁니다. 테이블은 앱 기동 시 Flyway가 `src/main/resources/db/migration/`의 스크립트로 자동 생성합니다.

### 3. 환경변수 설정

Embedding API 주소는 코드나 `application.yml`에 직접 넣지 않고 `.env`로 분리합니다.

```bash
cp .env.example .env
# .env를 열어 EMBEDDING_BASE_URL 등 실제 값 입력
```

`./gradlew test`/`bootRun`은 `.env`가 있으면 자동으로 읽어서 환경변수로 주입합니다(`build.gradle` 참고, 패키징된 산출물에는 영향 없음). 다른 도구로 직접 실행할 때만 셸에 따로 로드하면 됩니다.

```bash
set -a; source .env; set +a
```

| 변수 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `DB_URL` | 아니오 | `jdbc:postgresql://127.0.0.1:5432/rag_db` | PostgreSQL 접속 URL |
| `DB_USERNAME` | 아니오 | `rag` | PostgreSQL 사용자 |
| `DB_PASSWORD` | **예** | 없음 | PostgreSQL 비밀번호 (`.env`에만 두고 커밋하지 않음) |
| `SERVER_PORT` | 아니오 | `8080` | 애플리케이션 포트 |
| `EMBEDDING_BASE_URL` | **예** | `http://127.0.0.1:8000/v1` | BGE-M3 Embedding API base URL (`/v1` 포함) |
| `EMBEDDING_MODEL` | 아니오 | `BAAI/bge-m3` | Embedding 모델명 |
| `EMBEDDING_DIMENSION` | 아니오 | `1024` | 기대하는 벡터 차원 |
| `RETRIEVAL_TOP_K` | 아니오 | `5` | 검색 API가 반환할 최대 결과 수 (1~50) |

`EMBEDDING_BASE_URL`은 로컬 더미 값이라 실제 Embedding 서버를 쓰려면 반드시 채워야 합니다. `DB_PASSWORD`는 기본값이 없어 설정하지 않으면 애플리케이션이 기동하지 않습니다.

### 4. 애플리케이션 실행

```bash
./gradlew bootRun
```

### 5. 테스트

```bash
./gradlew test
```

## 프로젝트 구조

```text
com.nohtaehwan.rag
├─ RagBackendApplication.java   # 엔트리 포인트
├─ HealthController.java        # 헬스체크 (HealthMapper 사용)
├─ health/mapper/
│   └─ HealthMapper.java        # @Select("SELECT 1")
├─ document/                    # Markdown 수집·파싱·Chunking
│   ├─ DocumentSourceReader.java
│   ├─ MarkdownParser.java
│   ├─ MarkdownChunker.java
│   └─ ...
├─ embedding/                   # BGE-M3 Embedding API 연동
│   ├─ EmbeddingClient.java
│   ├─ EmbeddingConfig.java
│   ├─ EmbeddingProperties.java
│   ├─ EmbeddingRequest.java
│   └─ EmbeddingResponse.java
├─ indexing/                    # 문서 색인(Chunk+Embedding → PostgreSQL 저장)
│   ├─ DocumentIndexController.java
│   ├─ DocumentIndexService.java
│   ├─ DocumentIndexResponse.java
│   ├─ DocumentRepository.java
│   ├─ DocumentChunkRepository.java
│   └─ mapper/                  # MyBatis mapper interface, SQL은 annotation에 직접 작성(XML 없음)
│       ├─ DocumentMapper.java          # @Delete/@Insert
│       ├─ DocumentChunkMapper.java     # @InsertProvider
│       ├─ DocumentChunkSqlProvider.java # insertAll의 multi-row INSERT SQL을 순수 Java로 생성
│       ├─ DocumentInsertParameter.java
│       └─ ChunkRow.java
├─ retrieval/                   # 질문 embedding → pgvector cosine distance 검색
│   ├─ SearchController.java
│   ├─ SearchService.java
│   ├─ SearchResponse.java
│   ├─ SearchResult.java
│   ├─ RetrievalProperties.java
│   └─ mapper/
│       ├─ SearchMapper.java            # @Select, pgvector <=> 연산자
│       └─ SearchRow.java
└─ exception/
    ├─ RagException.java            # 서버/외부 실패(500)
    └─ InvalidRequestException.java # 잘못된 요청(400)
```

MyBatis mapper는 XML을 쓰지 않고 인터페이스 메서드 위에 SQL을 직접 붙이는 annotation 방식(`@Select`/`@Insert`/`@Delete`)입니다. 문서 수만큼 늘어나는 chunk batch insert처럼 정적 SQL로 표현이 안 되는 경우만 `@InsertProvider` + SQL을 만드는 순수 Java 클래스(`DocumentChunkSqlProvider`)를 씁니다.

Answer(LLM 호출) 모듈은 아직 구현되지 않았습니다. 진행 상황은 [`docs/rag_mvp_development_plan.md`](docs/rag_mvp_development_plan.md)에서 단계별로 확인할 수 있습니다.

## API 목록

_last update: 2026-09-08_

| Method | Path | 설명 |
|---|---|---|
| GET | `/health` | 애플리케이션·DB 상태 확인 |
| POST | `/api/documents/index` | Markdown 문서 Chunking·Embedding 후 색인(재색인 시 기존 문서 대체) |
| GET | `/api/search?query=...` | 질문과 관련된 Chunk를 pgvector cosine distance로 검색 |

**`POST /api/documents/index` 정책**

- 설정된 디렉터리(`document.source-directory`)의 `.md` 파일 전체를 색인한다. 요청 본문은 없다.
- 대상 파일이 없으면 오류가 아니라 0건 성공으로 응답한다.
- 같은 `source`로 재색인하면 기존 문서를 대체한다. `tb_document.source`는 UNIQUE 제약이 있어 같은 source의 문서는 항상 최대 1건이다.
- Embedding 생성은 DB 트랜잭션 밖에서 순차 수행한다(재시도 없음, 실패 시 즉시 중단).
- 문서 삭제·insert·Chunk insert는 문서 1건 단위의 하나의 트랜잭션이다.
- 여러 문서 중 뒤의 문서가 실패해도 앞서 이미 저장(commit)된 문서는 유지된다(부분 성공 허용).
- Chunk가 0개인 빈 문서도 문서 수(`documentCount`)에는 포함하고 Chunk 수(`chunkCount`)에는 포함하지 않는다.
- 실패 응답은 DB·Embedding API 상세 원인을 노출하지 않고 `{"message": "문서 색인에 실패했습니다."}`만 반환한다.

**`GET /api/search?query=...` 정책**

요청 예시:

```
GET /api/search?query=결제 취소 방법
```

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

- `query`는 필수 파라미터이며 null·빈 문자열·공백만 있으면 400을 반환한다.
- Top-K는 요청으로 받지 않고 `retrieval.top-k` 설정(기본 5)을 따른다.
- 결과가 없으면 오류가 아니라 `results: []`를 반환한다. 결과는 `distance`(pgvector cosine distance, 낮을수록 유사) 오름차순이다.
- embedding 원본 벡터는 응답에 포함하지 않는다.
- 실패 응답은 상세 원인을 노출하지 않고 `{"message": "..."}`만 반환한다(400은 검증 메시지, 500은 고정 메시지).

## 참고 문서

| 문서 | 내용 |
|---|---|
| [`docs/rag_mvp_development_plan.md`](docs/rag_mvp_development_plan.md) | 개발 단계별 계획과 진행 체크리스트 |
| [`docs/rag_backend_developer.md`](docs/rag_backend_developer.md) | RAG 백엔드 개발 개념 정리 |
| [`docs/local_embedding_model_setup_dgx_spark.md`](docs/local_embedding_model_setup_dgx_spark.md) | DGX Spark BGE-M3 Embedding 서버 구성 가이드 |
| [`docs/claude_kit_development_checklist.md`](docs/claude_kit_development_checklist.md) | 기능 개발 시 준수 체크리스트 템플릿 |
| [`docs/checklists/`](docs/checklists) | 작업별 체크리스트 작성 기록 |
