백엔드 개발자 관점에서 RAG를 보면, 생각보다 **LLM 자체를 다루는 일보다
기존 백엔드 엔지니어링에 가까운 일이 훨씬 많다.**

하나의 RAG 서비스를 실제 프로덕션 수준으로 만든다고 생각하면 다음처럼 볼
수 있다.

``` text
                         ┌──────────────┐
                         │   사용자     │
                         └──────┬───────┘
                                │
                              REST
                                │
                         ┌──────▼───────┐
                         │  RAG Backend │
                         └──────┬───────┘
                                │
              ┌─────────────────┼──────────────────┐
              │                 │                  │
        Query Processing    Retrieval         Conversation
              │                 │               State
              │           ┌─────┴─────┐            │
              │           │           │          Redis
              │       Vector DB   Elasticsearch
              │
              └──────────────┬───────────────────
                             │
                         Reranking
                             │
                       Context Builder
                             │
                             ▼
                            LLM
```

그리고 뒤에서는 별도의 데이터 파이프라인이 돌아간다.

``` text
Confluence ─┐
GitHub ─────┤
PDF ────────┼→ 수집 → 파싱 → Chunking → Embedding → Vector DB
DB ─────────┤
Notion ─────┘
```

백엔드 개발자가 담당할 수 있는 영역을 하나씩 보면 이렇다.

## 1. Document Ingestion Pipeline

RAG에서 꽤 큰 백엔드 영역이다.

예를 들어 회사 문서를 검색하는 RAG라면 데이터가 계속 변경된다.

``` text
Confluence
GitHub
Notion
Google Drive
DB
PDF
```

따라서 이런 시스템이 필요하다.

``` text
Scheduler
   ↓
Document Collector
   ↓
변경 여부 확인
   ↓
Parsing
   ↓
Chunking
   ↓
Embedding API
   ↓
Vector DB
```

여기에서 백엔드 개발 문제가 바로 나온다.

예를 들어 문서 100만 개가 있다고 하자. 매번 전체 문서를 embedding하면
비용도 크고 시간이 오래 걸린다.

그래서

``` text
document_id
version
updated_at
content_hash
embedding_version
```

같은 metadata를 관리한다.

``` text
기존 content_hash == 현재 content_hash
        ↓
     skip

기존 content_hash != 현재 content_hash
        ↓
   re-indexing
```

그리고 대량 처리를 해야 한다면 MQ를 붙일 수도 있다.

``` text
Document Collector
       ↓
     Kafka
       ↓
 ┌─────┼─────┐
 ▼     ▼     ▼
Worker Worker Worker
 │     │     │
 └─────┼─────┘
       ↓
Embedding API
       ↓
Vector DB
```

이쯤 되면 사실 전형적인 **비동기 데이터 처리 시스템**이다.

재시도, 중복 처리, idempotency, DLQ, rate limit, worker scaling 같은
문제가 그대로 등장한다.

------------------------------------------------------------------------

## 2. Chunking 시스템

여기서부터 RAG 특유의 문제가 조금씩 나온다.

문서 전체를 검색 단위로 만들면 검색 품질이 좋지 않다.

예를 들어

``` text
주문 시스템 설계 문서
50,000자
```

를

``` text
chunk 1: 주문 생성
chunk 2: 결제 요청
chunk 3: 결제 완료
chunk 4: 주문 취소
chunk 5: 환불
...
```

처럼 나눈다.

단순하게는

``` java
text.substring(...)
```

같은 방식으로 자를 수도 있지만 실제 시스템에서는 그렇게 단순하지 않다.

예를 들어 Markdown이면

``` text
# 주문

## 주문 생성
...

## 주문 취소
...

## 환불
...
```

이라는 구조를 활용할 수 있다.

코드라면 더 복잡하다.

``` java
class OrderService {

    createOrder() {...}

    cancelOrder() {...}

}
```

를 중간에서 잘라버리는 것보다 class/method 단위로 나누는 것이 검색에
유리할 수 있다.

그래서 백엔드 개발자가

``` text
MarkdownChunker
PDFChunker
CodeChunker
ConfluenceChunker
```

같은 것을 설계할 수도 있다.

여기서부터 **"검색 품질을 고려한 백엔드 개발"**이 된다.

------------------------------------------------------------------------

## 3. Vector DB / 검색 시스템

RAG의 핵심 백엔드 영역 중 하나다.

예를 들어 PostgreSQL + pgvector를 쓴다면 개념적으로 이런 테이블을 만들
수 있다.

``` sql
document_chunk

id
document_id
chunk_index
content
embedding vector(1536)
metadata jsonb
created_at
```

그리고

``` text
사용자 질문
    ↓
Embedding
    ↓
[0.21, -0.31, 0.82, ...]
    ↓
Vector DB
```

에서 유사한 chunk를 검색한다.

여기서부터 기존 DB/검색엔진 튜닝과 상당히 비슷해진다.

예를 들어:

-   vector index 설계
-   HNSW / IVF 계열 인덱스 선택
-   top-K 결정
-   metadata filtering
-   검색 latency 측정
-   index 크기 관리
-   shard/replica 설계

등이다.

예를 들어 질문이

> "결제 취소 처리 방식 알려줘"

여도 모든 문서를 검색할 필요는 없다.

``` text
service = payment
document_type = architecture
version = current
permission_group = backend
```

같은 조건을 먼저 걸 수 있다.

즉,

``` text
Metadata Filtering
        +
Vector Search
```

가 된다.

------------------------------------------------------------------------

## 4. Elasticsearch를 이용한 Hybrid Search

실무 RAG에서 꽤 중요한 부분이다.

Vector Search만 하면 항상 좋은 결과가 나오는 것은 아니다.

예를 들어

``` text
ERR_PAYMENT_1032
```

를 검색한다고 해보자.

embedding 기반 semantic search보다 정확한 문자열 검색이 훨씬 중요할 수
있다.

그래서

``` text
BM25
+
Vector Search
```

를 같이 사용한다.

예를 들어

``` text
사용자 질문

"ERR_PAYMENT_1032가 발생하는 원인이 뭐야?"
          │
          ├──────────────┐
          ▼              ▼
    Elasticsearch    Vector Search
       BM25             Semantic
          │              │
          └──────┬───────┘
                 ↓
           결과 Merge
                 ↓
             Reranker
```

이것을 **Hybrid Search**라고 한다.

검색/Elasticsearch 경험이 있는 백엔드 개발자가 RAG에서 특히 강점을 가질
수 있는 부분이다.

------------------------------------------------------------------------

## 5. Retrieval Service

실제 서비스에서는 Vector DB에 바로 질의하는 것보다 별도의 retrieval
layer를 두는 경우가 많다.

예를 들어 Spring으로 생각하면:

``` java
interface Retriever {

    List<Document> retrieve(Query query);
}
```

그리고

``` text
VectorRetriever
KeywordRetriever
HybridRetriever
CodeRetriever
DocumentRetriever
```

등을 둘 수 있다.

RAG API에서는

``` text
Question
   ↓
QueryAnalyzer
   ↓
Retriever
   ↓
Top 50
   ↓
Reranker
   ↓
Top 5
```

같은 식으로 처리한다.

여기서 **Reranker**라는 개념도 중요하다.

첫 검색 결과 50개를 그대로 LLM에 넣는 게 아니라 질문과 정말 관련 있는
순서로 다시 평가한다.

``` text
Vector Search

1. document A  0.89
2. document B  0.87
3. document C  0.85
...
50. document Z
        ↓
      Reranker
        ↓
1. document C
2. document A
3. document F
4. document K
5. document B
```

LLM에 넣는 context의 품질을 높이는 것이다.

------------------------------------------------------------------------

## 6. Context / Prompt 관리

검색한 문서를 무작정 LLM에 넣는 것도 아니다.

예를 들어 검색 결과가

``` text
Document A  4,000 tokens
Document B  5,000 tokens
Document C  3,000 tokens
Document D  8,000 tokens
```

이면 총 20,000 token이다.

그래서

``` text
Context Budget = 10,000 tokens
```

이라면 어떤 문서를 얼마나 넣을지 결정해야 한다.

``` text
Retrieval 결과
      ↓
중복 제거
      ↓
문서 relevance 계산
      ↓
token 계산
      ↓
context 구성
      ↓
Prompt 생성
```

이것도 하나의 백엔드 컴포넌트가 된다.

``` java
Context context =
    contextBuilder.build(
        question,
        retrievedDocuments,
        tokenBudget
    );
```

------------------------------------------------------------------------

## 7. LLM Gateway

이 부분도 상당히 백엔드스럽다.

서비스에서 LLM API를 직접 여기저기 호출하게 만들기보다

``` text
RAG Service
     ↓
LLM Gateway
     ├── OpenAI
     ├── Claude
     ├── Gemini
     └── Local LLM
```

처럼 abstraction을 만들 수 있다.

여기에는 일반적인 외부 API 연동 문제가 그대로 존재한다.

``` text
Timeout
Retry
Rate Limit
Circuit Breaker
Fallback
Cost Control
Logging
Metrics
```

예를 들어

``` text
GPT-5.x 실패
    ↓
retry
    ↓
계속 실패
    ↓
fallback model
```

같은 정책도 만들 수 있다.

또 중요한 것이 **Streaming**이다.

LLM이 답변 전체를 생성할 때까지 기다리면 UX가 나쁘기 때문에

``` text
LLM
 │
 ├─ token
 ├─ token
 ├─ token
 ▼
RAG Backend
 │
 ▼
SSE / WebSocket
 │
 ▼
Client
```

형태로 전달한다.

Netty/WebSocket/SSE 같은 서버 개발 지식이 그대로 사용된다.

------------------------------------------------------------------------

## 8. Redis를 이용한 상태/캐시 관리

Redis도 여러 곳에 쓸 수 있다.

예를 들어 conversation context:

``` text
conversation:{conversationId}

messages
retrieved_documents
user_context
```

또는 검색 결과 캐싱:

``` text
query hash

"주문 취소 정책"
        ↓
Redis
        ↓
cached retrieval result
```

embedding 자체를 캐싱할 수도 있다.

``` text
hash(question)
       ↓
embedding cache
```

동일하거나 반복적인 질문에서 embedding API 비용과 latency를 줄일 수
있다.

------------------------------------------------------------------------

## 9. 권한 제어

사내 RAG를 만든다면 굉장히 중요한 백엔드 영역이다.

예를 들어 직원 A는

``` text
주문 문서 → 접근 가능
결제 문서 → 접근 가능
인사 문서 → 접근 불가
```

일 수 있다.

그런데 Vector Search가 HR 문서를 가져와 LLM에게 전달하면 이미 보안
사고다.

따라서

``` text
User
 ↓
Authentication
 ↓
Permission
 ↓
Retrieval Filter
 ↓
Vector Search
```

가 되어야 한다.

예를 들어 metadata에

``` json
{
  "department": "payment",
  "permission": ["backend", "payment"]
}
```

같은 정보를 넣고 검색 단계부터 filtering할 수 있다.

이런 **ACL-aware retrieval**은 실제 사내 RAG에서 매우 중요한 설계
문제다.

------------------------------------------------------------------------

## 10. RAG 평가 시스템

여기까지 구현하면 다음 문제가 생긴다.

> "그래서 우리 RAG가 잘 만들어진 건 어떻게 알지?"

이를 측정하는 시스템도 필요하다.

예를 들어 테스트 데이터셋을 만든다.

``` text
Question:
주문 취소 시 결제 이벤트는?

Expected Document:
payment-cancel.md

Expected Answer:
PAYMENT_CANCEL 이벤트 발행
```

그리고 배포할 때

``` text
RAG version A
     ↓
1000개 질문 실행
     ↓
Retrieval Recall
MRR
Hit Rate
Answer Correctness
Groundedness
Latency
Token Cost
```

등을 비교한다.

예를 들어 chunk 크기를

``` text
500 tokens → 800 tokens
```

으로 변경했더니

``` text
Retrieval Recall

82% → 89%

하지만

Latency
320ms → 480ms

LLM token cost
+23%
```

가 되었다면 어떤 설정이 좋은지 판단해야 한다.

이렇게 되면 RAG 개발도 상당히 전형적인 **성능/품질 엔지니어링 문제**가
된다.

------------------------------------------------------------------------

# 결국 백엔드 개발자가 새로 공부해야 하는 것은?

기존 백엔드 지식과 RAG 특화 지식을 분리해서 보는 것이 좋다.

  영역                기존 백엔드 경험 활용
  ------------------- -----------------------
  REST API            ★★★★★
  DB                  ★★★★★
  Redis               ★★★★★
  Elasticsearch       ★★★★★
  MQ/Kafka            ★★★★★
  비동기 처리         ★★★★★
  데이터 파이프라인   ★★★★★
  모니터링            ★★★★★
  장애 대응           ★★★★★
  Vector Search       새로 학습
  Embedding           새로 학습
  Chunking            새로 학습
  Reranking           새로 학습
  Prompt/Context      새로 학습
  RAG Evaluation      새로 학습

즉, **LLM을 처음부터 학습시키는 ML 엔지니어링을 배워야 RAG를 할 수 있는
것은 아니다.**

오히려 백엔드 개발자가 진입한다면 다음 흐름이 가장 자연스럽다.

``` text
                [기존 역량]

Spring Boot
MySQL
Redis
MQ
Elasticsearch
비동기 처리
데이터 파이프라인
       │
       │
       ▼
┌─────────────────────┐
│   RAG Backend       │
├─────────────────────┤
│ Document Pipeline   │
│ Retrieval Service   │
│ Vector DB           │
│ Hybrid Search       │
│ LLM Gateway         │
│ Cache               │
│ Streaming           │
│ ACL                 │
│ Monitoring          │
└──────────┬──────────┘
           │
           │ + 새로 공부
           ▼
      Embedding
      Chunking
      Vector Search
      Reranking
      Context
      Evaluation
```

특히 이미 **Spring 기반 API, Redis, MQ, 데이터 파이프라인, Elasticsearch
같은 영역을 경험한 백엔드 개발자라면**, RAG로 넘어갈 때 완전히 다른
직군으로 전환하는 것보다는 **기존 분산 백엔드 시스템에 새로운 검색/LLM
컴포넌트를 붙이는 것**에 더 가깝게 볼 수 있다.

그리고 포트폴리오 목적으로 RAG를 직접 만들어본다면 단순한 **"PDF 올리고
LangChain으로 질문하기"** 수준보다, **Spring Boot +
Elasticsearch/pgvector + Redis + MQ를 이용한 프로덕션형 RAG**를 하나
만드는 편이 백엔드 개발자로서는 훨씬 가치가 크다. 예를 들어
`GitHub/기술문서 → 변경 감지 → MQ → chunking/embedding → hybrid search → reranking → LLM streaming → 출처 표시`까지
구현하면, 기존 백엔드 경력과 RAG 경험이 상당히 자연스럽게 연결된다.
