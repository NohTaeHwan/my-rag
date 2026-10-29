# my-rag — RAG Backend Project

PostgreSQL + pgvector 기반 검색 증강 생성(RAG) 백엔드 프로젝트입니다.

Markdown 문서를 넣고, 질문에 관련된 Chunk와 출처를 결과값으로 반환하는 흐름을 목표로 합니다.

현재는 프로토타입이지만 해당 내용을 활용해서 여러 상황에서 사용하는 RAG 로 사용하는게 주 목표입니다.

같은 JAR가 `/`에서 최소 웹 채팅 화면(`frontend/`, React + TypeScript + Vite)을 함께 제공합니다. 질문을 `POST /api/answers`로 보내고 답변과 출처(문서 제목·문서 ID·구간 번호)를 표시합니다. 자세한 범위와 한계는 [웹 채팅 UI](#웹-채팅-ui)를 참고하세요.




## 기술 스택

| 구분 | 내용 |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.5.6 (Spring MVC) |
| Data Access | MyBatis (mapper interface + annotation SQL, XML 미사용, ORM 미사용) |
| Database | PostgreSQL 17 + pgvector |
| Migration | Flyway |
| Embedding | BGE-M3 (OpenAI 호환 `/v1/embeddings` API) |
| LLM | OpenAI-compatible `/chat/completions` API (특정 provider SDK 미사용) |
| API 문서 | Springdoc OpenAPI 3 + Swagger UI |
| Build | Gradle (`./gradlew`) |
| Web UI | React 19 + TypeScript + Vite (`frontend/`, Node 22) |

## 시작하기

### 1. 사전 준비

- Java 17
- Node.js 22 / npm (웹 화면 빌드·`bootJar`·`bootRun`에만 필요, `./gradlew test`에는 불필요)
- Docker / Docker Compose (PostgreSQL 실행용)
- BGE-M3 Embedding API 접근 정보 (예: DGX Spark 주소)

### 2. PostgreSQL 실행

```bash
docker compose up -d
```

`docker-compose.yml`이 `pgvector/pgvector:pg17` 이미지를 `5432` 포트로 띄웁니다. Flyway 자동 적용은 기본적으로 꺼져 있습니다. 빈 DB에는 테이블이 없으므로, DB 변경은 검토·승인 후 관리 계정으로 별도 적용해야 합니다. 앱/테스트는 DDL 권한이 없는 계정으로 접속합니다.

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
| `DB_USERNAME` | 아니오 | `rag` | 로컬 docker-compose 관리자 계정(앱에서 사용하지 않음) |
| `DB_PASSWORD` | **예** | 없음 | 로컬 docker-compose 관리자 암호(앱에서 사용하지 않음) |
| `DB_APP_USERNAME` | 아니오 | `rag_app` | 앱/테스트 전용 제한 계정 |
| `DB_APP_PASSWORD` | **예** | 없음 | 앱/테스트 전용 제한 계정 암호 (`.env`에만 두고 커밋하지 않음) |
| `SERVER_PORT` | 아니오 | `8080` | 애플리케이션 포트 |
| `EMBEDDING_BASE_URL` | **예** | `http://127.0.0.1:8000/v1` | BGE-M3 Embedding API base URL (`/v1` 포함) |
| `EMBEDDING_MODEL` | 아니오 | `BAAI/bge-m3` | Embedding 모델명 |
| `EMBEDDING_DIMENSION` | 아니오 | `1024` | 기대하는 벡터 차원 |
| `RETRIEVAL_TOP_K` | 아니오 | `5` | 검색 API가 반환할 최대 결과 수 (1~50) |
| `LLM_BASE_URL` | **예** | `http://127.0.0.1:8002/v1` | OpenAI-compatible LLM API base URL (`/v1` 포함) |
| `LLM_MODEL` | 아니오 | `local-model` | 사용할 LLM 모델 id |
| `LLM_API_KEY` | 아니오 | (빈 문자열) | LLM API Key. 있으면 `Authorization: Bearer` 헤더로 전송, 없으면 헤더 생략 |
| `LLM_MAX_CONTEXT_CHARS` | 아니오 | `12000` | LLM prompt에 넣을 검색 근거의 최대 문자 수 |
| `LLM_MAX_TOKENS` | 아니오 | `512` | LLM 응답의 최대 토큰 수 |
| `LLM_TEMPERATURE` | 아니오 | `0.0` | LLM 샘플링 temperature (0~2) |

`EMBEDDING_BASE_URL`/`LLM_BASE_URL`은 로컬 더미 값이라 실제 서버를 쓰려면 반드시 채워야 합니다. 로컬 DB 컨테이너에는 `DB_PASSWORD`, 앱에는 `DB_APP_PASSWORD`가 필요합니다. `LLM_API_KEY`는 `.env`에만 두고 커밋하지 않습니다. Spark DB에는 V4가 적용되어 있습니다(2026-10-29 사용자 확인). Spark의 제한 계정·앱 전용 환경변수 준비 여부는 배포 전에 별도로 확인합니다.

### 4. 애플리케이션 실행

```bash
./gradlew bootRun
```

### 5. 테스트

```bash
./gradlew test                      # 백엔드. DB 접속은 127.0.0.1:1로 차단되고 격리 DB 태그는 제외된다
cd frontend && npm ci && npm test -- --run && npm run build   # 웹 화면(모의 fetch만 사용)
```

### 6. API 문서 (Swagger UI)

```bash
./gradlew bootRun
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

`POST /api/documents/index`, `GET /api/search`, `POST /api/answers`가 문서화 대상이며, 운영 상태 확인용 `GET /health`는 `@Hidden`으로 Swagger 문서에서 제외됩니다(API 자체는 그대로 동작).

## 프로젝트 구조

```text
com.nohtaehwan.rag
├─ RagBackendApplication.java   # 엔트리 포인트
├─ HealthController.java        # 헬스체크 (HealthMapper 사용, Swagger 문서에서는 @Hidden)
├─ config/
│   ├─ SwaggerConfig.java       # OpenAPI/Swagger UI 설정
│   └─ RequestIdFilter.java     # 요청마다 ID 발급, MDC·응답 헤더(X-Request-Id)에 반영, 로그 추적용
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
├─ answer/                      # 검색 근거 기반 LLM 답변 생성
│   ├─ AnswerController.java
│   ├─ AnswerService.java
│   ├─ AnswerRequest.java
│   ├─ AnswerResponse.java
│   ├─ AnswerSource.java
│   ├─ Context.java
│   ├─ ContextBuilder.java       # 검색 결과를 길이 제한 내 근거 context로 구성
│   └─ PromptBuilder.java        # system/user prompt 고정 정책 + context 삽입
├─ llm/                         # OpenAI-compatible LLM 연동
│   ├─ LlmClient.java            # provider 추상화
│   ├─ OpenAiCompatibleLlmClient.java
│   ├─ LlmConfig.java
│   ├─ LlmProperties.java
│   ├─ LlmRequest.java
│   ├─ LlmResponse.java
│   └─ Prompt.java
└─ exception/
    ├─ RagException.java            # 서버/외부 실패(500)
    └─ InvalidRequestException.java # 잘못된 요청(400)
```

MyBatis mapper는 XML을 쓰지 않고 인터페이스 메서드 위에 SQL을 직접 붙이는 annotation 방식(`@Select`/`@Insert`/`@Delete`)입니다. 문서 수만큼 늘어나는 chunk batch insert처럼 정적 SQL로 표현이 안 되는 경우만 `@InsertProvider` + SQL을 만드는 순수 Java 클래스(`DocumentChunkSqlProvider`)를 씁니다.

로그는 `src/main/resources/logback-spring.xml`에서 설정합니다. Spring Boot 기본 콘솔 포맷을 그대로 쓰되, `RequestIdFilter`가 채운 요청 ID를 `[%X{requestId}]`로 모든 로그 줄에 포함시켜, 동시 요청이 섞여도 로그에서 요청 단위로 추적할 수 있습니다.

진행 상황은 [`docs/rag_mvp_development_plan.md`](docs/rag_mvp_development_plan.md)에서 단계별로 확인할 수 있습니다.

## API 목록

_last update: 2026-09-08_

| Method | Path | 설명 |
|---|---|---|
| GET | `/health` | 애플리케이션·DB 상태 확인 |
| POST | `/api/documents/index` | Markdown 문서 Chunking·Embedding 후 색인(재색인 시 기존 문서 대체) |
| GET | `/api/search?query=...` | 질문과 관련된 Chunk를 pgvector cosine distance로 검색 |
| POST | `/api/answers` | 검색 근거를 바탕으로 LLM 답변과 출처를 생성 |

**`POST /api/documents/index` 정책**

- 설정된 디렉터리(`document.source-directory`)의 `.md` 파일 전체를 색인한다. 요청 본문은 없다.
- 대상 파일이 없으면 오류가 아니라 0건 성공으로 응답한다.
- 같은 `source`로 재색인하면 기존 문서를 대체한다. `tb_document.source`는 사용자·Knowledge Base 범위 안에서 유일하도록 제약된다(V3, `docs/personal_rag_chatbot_design.md` §2). 이 API가 호출하는 디렉터리 색인은 항상 개발용 고정 사용자·기본 Knowledge Base로 귀속되므로, 지금은 그 범위 안에서 같은 source의 문서가 항상 최대 1건이다.
- Embedding 생성은 DB 트랜잭션 밖에서 순차 수행한다(재시도 없음, 실패 시 즉시 중단).
- 문서 삭제·insert·Chunk insert는 문서 1건 단위의 하나의 트랜잭션이다.
- 여러 문서 중 뒤의 문서가 실패해도 앞서 이미 저장(commit)된 문서는 유지된다(부분 성공 허용).
- Chunk가 0개인 빈 문서도 문서 수(`documentCount`)에는 포함하고 Chunk 수(`chunkCount`)에는 포함하지 않는다.
- 실패 응답은 DB·Embedding API 상세 원인을 노출하지 않고 `{"message": "문서 색인에 실패했습니다."}`만 반환한다.

**`GET /api/search?query=...&knowledgeBaseId=...` 정책**

요청 예시:

```
GET /api/search?query=결제 취소 방법&knowledgeBaseId=1
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
- `knowledgeBaseId`는 필수(양의 정수)다. 누락·비숫자·0 이하면 400(`{"message": "knowledgeBaseId는 양의 정수여야 합니다."}`). 2026-10-28부터 `query`만 보내는 기존 요청은 400이다.
- 검색 범위는 현재 사용자 소유의 해당 Knowledge Base에 `INDEXED` 상태로 색인된 문서로만 제한된다(SQL `WHERE`가 정렬·`LIMIT`보다 먼저 적용). 사용자 ID는 요청으로 받지 않으며(`userId`를 보내도 무시) 인증 도입 전까지 서버가 개발용 사용자(id=1)로 고정한다 — 실제 다중 사용자 보호가 아니다.
- Knowledge Base가 없거나 다른 사용자 소유면 둘을 구분하지 않고 404 `{"message": "Knowledge Base를 찾을 수 없습니다."}`를 반환하며, 이때 Embedding API를 호출하지 않는다.
- Top-K는 요청으로 받지 않고 `retrieval.top-k` 설정(기본 5)을 따른다.
- 유효한 Knowledge Base에 결과가 없으면 오류가 아니라 `results: []`를 반환한다. 결과는 `distance`(pgvector cosine distance, 낮을수록 유사) 오름차순이다.
- embedding 원본 벡터는 응답에 포함하지 않는다.
- 실패 응답은 상세 원인을 노출하지 않고 `{"message": "..."}`만 반환한다(400은 검증 메시지, 500은 고정 메시지).

**`POST /api/answers` 정책**

요청 예시:

```json
{
  "question": "결제 완료 후 주문을 취소하려면?",
  "knowledgeBaseId": 1
}
```

응답 예시:

```json
{
  "answer": "결제 완료 후 주문 취소 절차는 ...입니다.",
  "sources": [
    {
      "documentId": 1,
      "title": "주문 관리 문서",
      "source": "orders/cancel.md",
      "chunkIndex": 3,
      "distance": 0.1245
    }
  ]
}
```

- 내부적으로 `GET /api/search`와 동일한 검색을 재사용한다. `question`은 필수이며 null·빈 문자열·공백만 있으면 400.
- `knowledgeBaseId`는 필수(JSON 정수, 양수)다. 누락·0 이하는 400 `{"message": "knowledgeBaseId는 양의 정수여야 합니다."}`, 실수(`1.5`)·문자열·boolean 등 형식 오류는 400 `{"message": "요청 본문 형식이 올바르지 않습니다."}`. 2026-10-28부터 `question`만 보내는 기존 요청은 400이다.
- LLM context와 `sources`는 검색과 같은 범위(현재 사용자·해당 KB·`INDEXED`)의 결과로만 만들어진다. 없는 KB·다른 사용자 소유 KB는 404이며 Embedding·LLM을 호출하지 않는다. 사용자 ID는 요청 본문으로 받지 않는다(보내도 무시).
- 유효한 Knowledge Base에서 검색 결과가 없거나(또는 있어도 `llm.max-context-chars` 제한으로 근거가 하나도 안 들어가면) LLM을 호출하지 않고 `{"answer": "질문에 답할 수 있는 근거 문서를 찾지 못했습니다.", "sources": []}`를 200으로 반환한다.
- `sources`는 실제 LLM prompt에 포함된 검색 결과만 담으며 `content`는 포함하지 않는다(출처 식별·distance만).
- LLM 호출은 OpenAI-compatible `POST /chat/completions`를 사용하며(`LLM_BASE_URL`/`LLM_MODEL`/`LLM_API_KEY` 등으로 설정), 실제 사용 서버·모델·SDK는 이 설정값에 따라 달라진다(특정 provider SDK를 코드에 고정하지 않음).
- 검색·LLM 호출을 DB 트랜잭션으로 묶지 않는다(읽기 전용 흐름).
- 실패 응답은 상세 원인(URL·API Key·prompt·SQL 등)을 노출하지 않고 `{"message": "..."}`만 반환한다.

## 웹 채팅 UI

`frontend/`의 React 화면을 Spring Boot가 같은 출처의 `/`에서 제공합니다(별도 서버 없음).

- 기능: 질문 입력(Enter 전송·Shift+Enter 줄바꿈), 로딩·오류 안내, 답변 평문 표시, 출처(문서 제목·문서 ID·구간 번호) 목록, 새 대화(진행 중 요청 취소), 120초 응답 대기 한도, 데스크톱/모바일(390px) 반응형.
- 한계:
  - 인증이 없다. 서버는 개발용 고정 사용자로 동작하고, 화면은 Knowledge Base ID `1`(표시 이름 "기본 지식 공간")로만 질문한다. KB 목록 조회·선택, 문서 업로드, 대화 저장·다중 턴 문맥, 스트리밍, 출처 원문 미리보기는 없다.
  - 질문을 여러 개 보내도 매번 독립 질문으로 처리한다.
  - 시간 초과나 새 대화는 브라우저의 대기만 멈춘다. 서버 처리 취소는 보장하지 않는다.
- 로컬 개발: 백엔드를 `./gradlew bootRun`(8080)으로 띄운 뒤 `cd frontend && npm ci && npm run dev`. Vite 개발 서버가 `/api`를 `http://127.0.0.1:8080`으로 프록시하므로 백엔드가 떠 있지 않으면 답변 요청이 `ECONNREFUSED`(화면에는 오류 안내)로 실패한다.
- 패키징:
  - `./gradlew bootJar`는 `frontendBuild`(`npm ci && npm run build`)를 자동 실행해 최신 번들을 JAR의 `BOOT-INF/classes/static/`에 넣고, `index.html`이 참조하는 JS·CSS가 JAR에 있는지 검사한다.
  - `./gradlew bootRun`도 빌드한 `frontend/dist`를 `/`에서 제공한다.
  - `./gradlew test`는 Node 없이 fixture(`src/test/resources/static/index.html`)로 라우팅만 확인한다.

## 배포

`Dockerfile`(멀티스테이지: `node:22-alpine`에서 웹 화면 빌드 → `eclipse-temurin:17-jdk`에서 그 번들을 넣어 `bootJar` → `eclipse-temurin:17-jre` 실행)로 컨테이너 이미지를 만듭니다.

```bash
docker build -t my-rag:latest .
```

GitHub Actions로 CI/CD가 구성되어 있습니다.

- `.github/workflows/ci.yml` — PR·`main` push마다 Node 22로 `frontend/`의 `npm ci`·`npm test -- --run`·`npm run build`와 `./gradlew clean test`를 실행. `main` push에서 이 test job이 성공해야만 ARM64 이미지를 빌드해 GitHub Container Registry(`ghcr.io/nohtaehwan/my-rag`)에 `latest`/커밋 SHA 태그로 push
- `.github/workflows/cd.yml` — `prd-spark` Environment의 사람 승인 후에만 진행한다. Tailscale로 배포 대상 서버에 접속해 **커밋 SHA 태그 이미지**(`ghcr.io/nohtaehwan/my-rag:<SHA>`)를 pull·재기동하고 `/health`를 확인한다. `latest`는 배포에 쓰지 않는다.
  - 자동: 같은 저장소 `main` push로 실행된 CI가 성공하면 그 CI의 `head_sha`로 트리거된다.
  - 수동: Actions → CD (DGX Spark) → Run workflow에서 `sha`(40자리 커밋 SHA, `main` 이력)를 반드시 입력한다.
  - SHA 형식 오류, checkout 불일치, 이미지 미존재, SHA 고정 이전 커밋은 Spark 접속 전에 실패한다.
  - 배포 job은 `concurrency: cd-prd-spark`(`cancel-in-progress: false`)로 직렬화된다. 진행 중 배포·롤백은 끝까지 실행되고 겹쳐 돌지 않는다.
    - GitHub는 그룹당 실행 1개와 대기 1개만 유지한다. 대기 중에 새 CD run(자동·수동)이 들어오면 이전 대기 run은 **취소**된다.
    - 그래서 연속 push 시 중간 SHA는 배포되지 않을 수 있고, 나중에 들어온 **오래된 SHA의 수동 실행**이 대기 중인 최신 SHA run을 밀어낼 수도 있다. 승인 전에 run의 SHA를 확인한다.
    - 오래된 SHA를 승인하면 그 버전으로 되돌아간다(롤백 용도로는 의도된 동작).

흐름: `main` push → CI(프런트엔드·백엔드 테스트) → ARM64 이미지(`:<SHA>`, `:latest`) 발행 → CD 대기 → `prd-spark` 사람 승인 → 그 SHA 이미지로 Spark 배포.

> Spark의 `compose/docker-compose.app.yml`은 CD가 수정하지 않는다. `deploy.sh`가 배포 때마다 image 한 항목만 덮는 임시 override를 함께 넘긴다. Spark에서 override 없이 `docker compose up`을 직접 실행하면 Compose 파일의 `latest` 이미지로 바뀌므로, 수동 재기동도 `DEPLOY_SHA=<SHA> deploy.sh` 또는 CD 수동 실행으로 한다.

실제 DGX Spark 배포 절차(하드웨어 점검부터 Docker Compose 구성, 실패 시 확인 방법, 알려진 보안/운영 리스크까지)는 [`docs/dgx_spark_mvp_deployment.md`](docs/dgx_spark_mvp_deployment.md)에 정리되어 있습니다.

## 참고 문서

| 문서 | 내용 |
|---|---|
| [`docs/rag_mvp_development_plan.md`](docs/rag_mvp_development_plan.md) | 개발 단계별 계획과 진행 체크리스트 |
| [`docs/rag_backend_developer.md`](docs/rag_backend_developer.md) | RAG 백엔드 개발 개념 정리 |
| [`docs/local_embedding_model_setup_dgx_spark.md`](docs/local_embedding_model_setup_dgx_spark.md) | DGX Spark BGE-M3 Embedding 서버 구성 가이드 |
| [`docs/dgx_spark_mvp_deployment.md`](docs/dgx_spark_mvp_deployment.md) | DGX Spark 실배포 절차·검증 결과·CI/CD 구성·알려진 리스크 |
| [`docs/claude_kit_development_checklist.md`](docs/claude_kit_development_checklist.md) | 기능 개발 시 준수 체크리스트 템플릿 |
| [`docs/checklists/`](docs/checklists) | 작업별 체크리스트 작성 기록 |
