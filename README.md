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

실행 전 셸에 로드합니다.

```bash
set -a; source .env; set +a
```

| 변수 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `DB_URL` | 아니오 | `jdbc:postgresql://127.0.0.1:5432/rag_db` | PostgreSQL 접속 URL |
| `DB_USERNAME` | 아니오 | `rag` | PostgreSQL 사용자 |
| `DB_PASSWORD` | 아니오 | `rag_dev_password` | PostgreSQL 비밀번호 (로컬 개발용 기본값) |
| `SERVER_PORT` | 아니오 | `8080` | 애플리케이션 포트 |
| `EMBEDDING_BASE_URL` | **예** | `http://127.0.0.1:8000/v1` | BGE-M3 Embedding API base URL (`/v1` 포함) |
| `EMBEDDING_MODEL` | 아니오 | `BAAI/bge-m3` | Embedding 모델명 |
| `EMBEDDING_DIMENSION` | 아니오 | `1024` | 기대하는 벡터 차원 |

기본값은 로컬 더미 값이라 실제 Embedding 서버를 쓰려면 `EMBEDDING_BASE_URL`을 반드시 채워야 합니다.

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
└─ exception/
    └─ RagException.java        # 프로젝트 단일 비즈니스 예외
```

MyBatis mapper는 XML을 쓰지 않고 인터페이스 메서드 위에 SQL을 직접 붙이는 annotation 방식(`@Select`/`@Insert`/`@Delete`)입니다. 문서 수만큼 늘어나는 chunk batch insert처럼 정적 SQL로 표현이 안 되는 경우만 `@InsertProvider` + SQL을 만드는 순수 Java 클래스(`DocumentChunkSqlProvider`)를 씁니다.

Retrieval(pgvector 검색), Answer(LLM 호출) 모듈은 아직 구현되지 않았습니다. 진행 상황은 [`docs/rag_mvp_development_plan.md`](docs/rag_mvp_development_plan.md)에서 단계별로 확인할 수 있습니다.

## API 목록

_last update: 2026-09-04_

| Method | Path | 설명 |
|---|---|---|
| GET | `/health` | 애플리케이션·DB 상태 확인 |
| POST | `/api/documents/index` | Markdown 문서 Chunking·Embedding 후 색인(재색인 시 기존 문서 대체) |

**`POST /api/documents/index` 정책**

- 설정된 디렉터리(`document.source-directory`)의 `.md` 파일 전체를 색인한다. 요청 본문은 없다.
- 대상 파일이 없으면 오류가 아니라 0건 성공으로 응답한다.
- 같은 `source`로 재색인하면 기존 문서를 대체한다. `tb_document.source`는 UNIQUE 제약이 있어 같은 source의 문서는 항상 최대 1건이다.
- Embedding 생성은 DB 트랜잭션 밖에서 순차 수행한다(재시도 없음, 실패 시 즉시 중단).
- 문서 삭제·insert·Chunk insert는 문서 1건 단위의 하나의 트랜잭션이다.
- 여러 문서 중 뒤의 문서가 실패해도 앞서 이미 저장(commit)된 문서는 유지된다(부분 성공 허용).
- Chunk가 0개인 빈 문서도 문서 수(`documentCount`)에는 포함하고 Chunk 수(`chunkCount`)에는 포함하지 않는다.
- 실패 응답은 DB·Embedding API 상세 원인을 노출하지 않고 `{"message": "문서 색인에 실패했습니다."}`만 반환한다.

## 참고 문서

| 문서 | 내용 |
|---|---|
| [`docs/rag_mvp_development_plan.md`](docs/rag_mvp_development_plan.md) | 개발 단계별 계획과 진행 체크리스트 |
| [`docs/rag_backend_developer.md`](docs/rag_backend_developer.md) | RAG 백엔드 개발 개념 정리 |
| [`docs/local_embedding_model_setup_dgx_spark.md`](docs/local_embedding_model_setup_dgx_spark.md) | DGX Spark BGE-M3 Embedding 서버 구성 가이드 |
| [`docs/claude_kit_development_checklist.md`](docs/claude_kit_development_checklist.md) | 기능 개발 시 준수 체크리스트 템플릿 |
| [`docs/checklists/`](docs/checklists) | 작업별 체크리스트 작성 기록 |
