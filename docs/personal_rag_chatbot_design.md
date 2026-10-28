# 문서 기반 RAG 챗봇 확장 — Phase 0 설계 (API 명세·데이터 모델)

> 관련 문서: `.hermes/plans/2026-09-21_151055-personal-rag-chatbot-implementation.md`(구현 계획),
> `.hermes/plans/2026-09-14_220046-personal-knowledge-rag-chatbot.md`(초기 제품 기획 — **아래 §0.1 참고, 일부 전제가 이 문서로 대체됨**)
>
> 이 문서는 Phase 0 산출물이다. Phase 1부터는 이 문서를 기준으로 구현하고, 실제 구현 중 바뀐 내용은 이 문서를 함께 갱신한다.
>
> **2026-10-22 개정**: 제품 방향을 "개인 지식 비서"에서 "범용 문서 기반 RAG 챗봇"으로 전환했다. 변경 이유와 이전 버전과의 차이는 §0에 명시한다.

---

## 0. 제품 정의 (개정)

### 0.1 변경 이유

기존 설계는 "개인이 수집·정리한 문서/메모리/대화 기록을 다루는 개인 지식 비서"를 전제로 했다(초기 제품 기획 §2, §3). 이번 개정은 다음으로 바꾼다.

> 특정 사용자나 도메인에 역할이 고정된 "개인 문서 비서"가 아니라, **사용자가 등록한 문서 집합에 따라 질의 가능한 분야가 달라지는 범용 문서 기반 RAG 챗봇**.

같은 API·처리 흐름으로 서로 다른 두 예시가 동작해야 한다.

```text
예시 A: Knowledge Base "한국 소설"에 소설 텍스트 업로드 → 그 소설 내용에 대해 질문
예시 B: Knowledge Base "열역학"에 특정 학문 문서 업로드 → 그 학문 내용에 대해 질문
```

두 예시는 **코드가 다르지 않다.** Knowledge Base의 `name`은 사용자가 붙인 라벨(메타데이터)일 뿐, 검색·답변 로직이 이름이나 도메인을 보고 분기하지 않는다. 데이터 모델(§2)에도 `domain`/`type` 같은 컬럼을 두지 않는다 — Knowledge Base를 구분하는 유일한 기준은 "어떤 문서가 들어있는가"이다.

### 0.2 공통 처리 흐름

```text
문서 등록 → 파싱·Chunking·Embedding·색인 → Knowledge Base 선택 → 관련 근거 검색 → 답변과 출처 표시
```

### 0.3 설계 원칙 (이번 개정에서 추가)

1. Knowledge Base는 문서 집합과 검색 범위를 나타낸다. 이름/설명은 메타데이터이며 코드에 고정된 종류로 다루지 않는다.
2. 질문·검색·답변의 핵심 로직은 Knowledge Base에 관계없이 동일하게 동작한다. 선택한 Knowledge Base 밖의 문서가 근거나 답변에 섞이지 않아야 한다(§6, §8.1).
3. 답변은 검색된 문서에 근거하고 출처를 표시한다. 근거가 부족하면 추측하지 않는다. **문서를 업로드하는 것만으로 정확한 답변이 보장되지는 않는다** — 검색이 관련 Chunk를 찾아오는 품질, LLM이 그 근거를 올바르게 종합하는 품질 모두에 따라 달라진다(실제 한계는 `docs/rag_performance_results.md` §9 참고).
4. 개인 Memory와 대화 요약은 범용 RAG의 필수 전제가 아니다. 핵심 흐름(Phase 1~3)에서 분리하고, 후속 선택 기능(§7)으로 재배치한다. Memory에 저장된 사실이 문서 출처를 대체하지 않는다 — 두 근거는 항상 구분해서 표시한다.
5. 1차 프로토타입은 기존 Markdown 처리 구조를 재사용해 `.md` 파일만 지원한다. 다른 형식(PDF 등)은 "향후 파서 확장 지점"(§3.2 참고)만 설계하고, 이번에 구현하거나 "무엇이든 업로드 가능"하다고 명세하지 않는다.
6. 개발용 고정 사용자 식별(`CurrentUserProvider`)과 향후 인증 경계는 유지한다. 단, **사용자 식별(누가 요청했는가)과 챗봇의 전문 도메인(어떤 분야를 다루는가)은 서로 무관한 개념**이다 — 전자는 `user_id`, 후자는 선택한 `knowledge_base_id`가 담당하며 둘을 혼동하지 않는다. **공개 서비스용 인증은 아직 없다** — 지금의 고정 사용자 방식은 개발 편의용이며 실제 다른 사람이 안전하게 쓸 수 있는 상태가 아니다.
7. 여러 Knowledge Base, 동명·유사 문서, "전체 지식" 범위에 대한 정책은 §8에서 명시적으로 정의한다 — 암묵적 합산으로 처리하지 않는다.

---

## 1. Phase 0 세부 항목 결정

| 항목 | 결정 |
|---|---|
| 업로드 파일 최대 크기 | 20MB |
| 허용 확장자 | `.md`만 (다른 형식은 §3.2의 확장 지점으로 설계, 이번에 구현하지 않음) |
| 원본 파일 저장 위치 | 서버 디스크, `{storage-root}/users/{userId}/kb/{knowledgeBaseId}/{documentId}.md` |
| 디스크 파일명 규칙 | `documentId` 기반 고정 — 충돌·특수문자 문제 회피. 사용자가 올린 원래 파일명은 DB `original_filename` 컬럼에 별도 보관 |
| 보존 정책 | 자동 만료 없음. 사용자가 명시적으로 삭제 요청할 때만 원본+Chunk+Embedding 함께 삭제 |
| 개발용 사용자 식별 | `CurrentUserProvider` 인터페이스 + 고정 `user_id` 반환 구현체. 공개 전환 시 이 Bean만 실제 인증 기반 구현체로 교체. **사용자 식별과 Knowledge Base의 전문 분야는 무관** — §0.3 6번 |
| 경로 노출(UI) | 논리적 경로/원본 파일명만 노출, 서버 실제 절대경로는 노출하지 않음 |
| Knowledge Base 개수 | 사용자당 여러 개 허용 — 서로 다른 문서 집합(예: 소설, 학문, 커리어 등 무엇이든)을 독립적으로 운영 |
| 질의 범위 | **한 번의 질문은 정확히 하나의 Knowledge Base를 지정한다** (암묵적 "전체" 합산 없음 — §8.1) |
| 색인 처리 방식 | 비동기 (업로드 응답은 작업 ID + `PENDING`) |

> 대화 요약·Memory 관련 세부 정책은 §7(후순위 선택 기능)로 이동했다 — 이번 Phase 0 핵심 결정에는 포함하지 않는다.

---

## 2. 데이터 모델 초안 (핵심 범위)

기존 `tb_document`/`tb_document_chunk`는 컬럼을 추가하는 방식으로 확장한다(재생성하지 않음).

```text
tb_user
 ├─ id BIGSERIAL PK
 ├─ display_name VARCHAR(255)          -- 개발용 고정 사용자 1행만 시드
 └─ created_at TIMESTAMP

tb_knowledge_base
 ├─ id BIGSERIAL PK
 ├─ user_id BIGINT FK → tb_user.id
 ├─ name VARCHAR(255) NOT NULL         -- 사용자가 붙인 자유 라벨. 시스템이 해석하는 분류 아님(§0.1)
 ├─ status VARCHAR(20) NOT NULL        -- ACTIVE, DELETED
 ├─ created_at TIMESTAMP
 └─ updated_at TIMESTAMP

  주의: domain/type/category 같은 컬럼을 두지 않는다 — Knowledge Base를 구분하는 것은
       오직 "어떤 문서가 들어있는가"이며, 이름은 메타데이터일 뿐이다(§0.1).

tb_document (기존 테이블에 컬럼 추가)
 ├─ id, title, source, created_at      -- 기존 컬럼 유지
 ├─ user_id BIGINT FK → tb_user.id             [신규]
 ├─ knowledge_base_id BIGINT FK → tb_knowledge_base.id   [신규]
 ├─ original_filename VARCHAR(255)             [신규] -- UI 노출용
 ├─ stored_path VARCHAR(500)                   [신규] -- 서버 디스크 경로, UI에 노출 안 함
 ├─ content_hash VARCHAR(64)                   [신규] -- 변경 감지
 ├─ file_size_bytes BIGINT                     [신규]
 ├─ index_status VARCHAR(20) NOT NULL          [신규] -- PENDING/PROCESSING/INDEXED/FAILED/DELETED
 ├─ error_message TEXT                         [신규]
 └─ indexed_at TIMESTAMP                       [신규]

  주의: 기존 UNIQUE(source) 제약은 "사용자+KB 범위 안에서 유일"로 완화해야 한다
       (다른 사용자, 또는 같은 사용자의 다른 KB가 같은 파일명을 쓸 수 있으므로 — §6, §8.3).

tb_document_chunk
 └─ 기존 컬럼 유지(document_id FK로 이미 사용자·KB 범위가 간접적으로 걸림 — 별도 컬럼 추가 없음)

tb_document_index_job
 ├─ id BIGSERIAL PK
 ├─ document_id BIGINT FK → tb_document.id
 ├─ status VARCHAR(20) NOT NULL         -- PENDING/PROCESSING/INDEXED/FAILED
 ├─ error_message TEXT
 ├─ created_at TIMESTAMP
 └─ updated_at TIMESTAMP
```

Migration 파일 매핑(핵심 범위, 구현 계획과 동일):
- `V3__add_user_knowledge_base_scope.sql` — `tb_user`, `tb_knowledge_base`, `tb_document` 컬럼 추가, UNIQUE 제약 완화
- `V4__add_document_index_job.sql` — `tb_document_index_job`

> `tb_user_memory`, `tb_conversation_summary`는 §7(후순위)로 이동했다. 실제로 그 기능을 구현하기로 결정하는 시점에, 그때 다음으로 사용 가능한 V 번호를 부여한다(지금 V5/V6으로 미리 확정하지 않음).

---

## 3. API 명세 초안 (핵심 범위)

모든 요청은 `CurrentUserProvider`가 resolve하는 `user_id`를 서비스 계층까지 전달한다(요청 파라미터로 직접 받지 않음 — 나중에 인증으로 교체될 때 API 시그니처가 안 바뀌게 하기 위함).

### 3.1 Knowledge Base

```http
POST /api/knowledge-bases
{"name": "한국 소설"}
→ 201 {"id": 2, "name": "한국 소설", "status": "ACTIVE", "createdAt": "..."}

GET /api/knowledge-bases
→ 200 [{"id": 1, "name": "열역학", "status": "ACTIVE", "documentCount": 12}, ...]
```

`name`은 자유 텍스트이며 서버가 값에 따라 동작을 분기하지 않는다(§0.1).

### 3.2 문서 업로드·색인 상태

```http
POST /api/knowledge-bases/{kbId}/documents  (multipart/form-data, file 필드 1개)
→ 202 {"documentId": 55, "indexJobId": 12, "status": "PENDING"}

  실패 응답:
  400 확장자 불허/빈 파일/20MB 초과 — {"message": "..."}

GET /api/index-jobs/{jobId}
→ 200 {"jobId": 12, "documentId": 55, "status": "INDEXED", "errorMessage": null}

GET /api/knowledge-bases/{kbId}/documents
→ 200 [{"documentId": 55, "originalFilename": "1장.md", "indexStatus": "INDEXED", "indexedAt": "..."}, ...]

DELETE /api/knowledge-bases/{kbId}/documents/{documentId}
→ 204  (원본 파일 + Chunk + Embedding 함께 삭제)
```

동일 파일 재업로드 시: `content_hash` 비교 → 같으면 `PENDING` 생성 없이 기존 상태 그대로 반환(불필요한 재색인 방지), 다르면 새 색인 작업 생성.

**파서 확장 지점(설계만, 이번에 구현하지 않음):** 현재 파싱 흐름은 `DocumentSourceReader` → `MarkdownParser` → `MarkdownChunker`(모두 `.md` 전용, `docs/rag_mvp_development_plan.md` 4단계)로 고정되어 있다. PDF 등 다른 형식을 추가할 때는 확장자별로 분기하는 `DocumentParser` 인터페이스(가칭, `parse(InputStream) → ParsedDocument`)를 두고 `MarkdownParser`를 그 첫 구현체로 재배치하는 방향을 권장한다 — 지금은 이 경계만 설계 노트로 남기고, 실제 인터페이스 추출은 두 번째 형식(PDF)을 실제로 추가하는 시점에 한다(YAGNI — 구현체가 하나뿐일 때 인터페이스를 미리 뽑지 않는다).

### 3.3 답변

```http
POST /api/knowledge-bases/{kbId}/answers
{"question": "주인공이 마지막에 내린 선택은?"}
→ 200 {
    "answer": "...",
    "sources": [
      {"documentId": 55, "knowledgeBaseId": 2, "originalFilename": "3장.md", "chunkIndex": 1, "distance": 0.21}
    ]
  }

근거 부족: answer는 고정 거절 문구, sources: []
동시성 제한 초과: 429 {"message": "지금 요청이 많아 잠시 후 다시 시도해주세요."}
```

- `kbId`는 **경로 파라미터로 필수**이며, 응답의 모든 `sources`는 반드시 그 `kbId`에 속한 문서만 포함한다(§8.1 — 여러 KB를 암묵적으로 합쳐 검색하지 않음).
- `sources[].knowledgeBaseId`를 포함한다 — 지금은 요청 자체가 단일 KB로 고정돼 있어 굳이 안 필요해 보이지만, §8.3(동명 문서가 여러 KB에 있는 경우) 때문에 출처를 보여줄 때 "어느 KB의 어느 문서"인지 항상 명시적으로 알 수 있게 한다.
- 이전 버전에 있던 `conversationId`/`memoryCandidate` 필드는 제거했다 — 대화 요약·Memory는 핵심 답변 흐름에서 분리됐다(§0.3 4번, §7).

---

## 4. 사용자 흐름 (핵심 범위)

### 4.1 Knowledge Base 생성 → 업로드 → 색인
```
Knowledge Base 생성(이름은 자유 라벨) → 파일 선택 → POST /documents (202, PENDING)
→ 클라이언트가 GET /index-jobs/{id} polling
→ PROCESSING → INDEXED 또는 FAILED(errorMessage 표시)
```

### 4.2 Knowledge Base 선택 → 질문 → 답변
```
Knowledge Base 선택(정확히 하나) → 질문 입력
→ POST /knowledge-bases/{kbId}/answers
→ 답변 렌더링 + 출처 chip(KB명 + 원본 파일명 표시, 클릭 시 Chunk 내용 모달)
```

이 두 흐름은 Knowledge Base에 어떤 문서가 들어있든(소설이든 학문 자료든) 동일하게 동작한다 — §0.2.

> Memory 승인, 대화 요약 흐름은 §7로 이동했다.

---

## 5. Phase 0 세부 정책 (핵심 범위)

### 업로드 정책
- Knowledge Base별 파일 개수·전체 용량 제한: **없음(1차)** — 필요성이 확인되면 추가한다.
- 빈 파일: 업로드 자체를 **400으로 거부**한다(색인 작업을 만들지 않음).
- 손상 파일(파싱 실패 등 콘텐츠 문제): 업로드는 받아들이되 **색인 단계에서 `FAILED` + `error_message`**로 기록한다(업로드 검증은 확장자·크기·빈 파일만, 콘텐츠 유효성은 색인 단계 책임).
- 파일명 정규화·경로 조작 방어: 원본 파일명에서 경로 구분자(`/`, `\`, `..`)를 제거/치환한 뒤 `original_filename`에 저장한다. 디스크 실제 경로는 `documentId` 기반으로 고정되므로(§1) 원본 파일명이 경로 조작에 쓰일 여지가 애초에 없다.

### 색인 작업 정책
- 색인 실패 시 재시도: **없음** — 기존 MVP 정책(`rag_mvp_development_plan.md` 5단계, fail-fast)과 동일하게 유지한다. 실패는 `FAILED`로 기록하고, 재시도는 사용자가 파일을 다시 업로드하는 방식으로만 이루어진다.
- 색인 중(`PENDING`/`PROCESSING`) 문서의 검색 포함 여부: **포함하지 않는다**. 검색 SQL에 `index_status = 'INDEXED'` 조건을 추가한다(Phase 1/3 구현에 반영).
- 색인 작업 동시 실행 제한: 사용자당 **단일 스레드 executor로 순차 처리**한다(별도 세마포어 카운터를 두지 않고 처리 자체를 직렬화). 지금 트래픽 규모에서 병렬화 이득보다 구현 단순성이 우선이다.

### 개발용 사용자 식별 방식
- 고정 `user_id` 값: **1** (`tb_user`에 마이그레이션으로 1행 시드).
- Controller → Service → Mapper 전달 규칙: **Controller가 `CurrentUserProvider.currentUserId()`로 조회한 값을 Service 메서드 파라미터로 명시적으로 전달**한다 — Service가 `CurrentUserProvider`에 직접 의존하지 않는다(테스트 시 Service를 순수 값 파라미터로 검증할 수 있게 하기 위함). Mapper는 Repository/Service가 넘긴 값을 그대로 SQL 파라미터로 받는다.
- 공개 환경 비활성화: 고정 사용자 구현체는 나중에 프로파일 가드(예: 운영 프로파일에서는 이 Bean이 뜨지 않고 실제 인증 구현체가 반드시 있어야 기동)를 적용한다. 지금은 프로파일이 `default` 하나뿐이라 실제 분리는 공개 전환 시점(Phase 6 이후)의 작업으로 남겨두고, 이 지점만 설계에 표시해둔다. **지금 상태로는 공개 서비스에 노출할 수 없다**(§0.3 6번).

### Phase 1 범위 확정
- Phase 1 범위: **DB 스키마 확장(`tb_user`/`tb_knowledge_base`/`tb_document` 컬럼) + 검색 SQL의 사용자·KB 격리만.**
- Phase 1 제외 범위: 업로드 API, 비동기 색인 작업, LLM 동시성 제한, Memory, 대화 요약, 프론트엔드 — 전부 Phase 2 이후(핵심 범위는 Phase 2~3, Memory/요약은 §7 후순위).

---

## 6. Phase 1에서 확인이 필요한 기존 제약

- `tb_document`의 기존 `UNIQUE(source)` 제약(`V2__add_unique_constraint_to_document_source.sql`)은 전역 유일성을 강제한다. 사용자·KB별 구조로 바뀌면 **`UNIQUE(user_id, knowledge_base_id, source)`로 완화**해야 한다 — 그렇지 않으면 사용자 B가(또는 같은 사용자가 다른 KB에) 같은 파일명(`source`)을 업로드하는 순간 실패한다. V3 migration에서 기존 제약을 drop하고 복합 UNIQUE로 재생성한다.
- `DocumentRepository.reindex()`의 `deleteBySource(sourceKey)`도 같은 이유로 `deleteByUserIdAndKnowledgeBaseIdAndSource(...)`로 범위를 좁혀야 한다.
- `SearchMapper`의 검색 SQL은 현재 전체 `tb_document_chunk`를 대상으로 한다 — `WHERE d.user_id = ? AND d.knowledge_base_id = ?` 조건을 추가해야 한다(Phase 1의 핵심 작업이자 완료 기준).
- **완료 기준에 반드시 포함할 것**: 사용자 A/B 간 격리뿐 아니라, **같은 사용자가 만든 서로 다른 Knowledge Base 사이의 격리**도 통합 테스트로 검증한다(예: 같은 사용자의 "소설" KB에 색인한 문서가 "학문" KB로 질문했을 때 검색/답변에 섞이지 않음) — 1차가 단일 개발용 사용자라서 실질적으로는 이 KB 간 격리가 사용자 간 격리보다 먼저, 더 자주 검증되는 조건이다.
- 기존 MVP 데이터(현재 로컬/Spark에 색인된 `order-management.md` 등)는 마이그레이션 시 개발용 고정 사용자(`user_id=1`)의 기본 Knowledge Base로 귀속시킨다.

> **구현 결정 (2026-10-23, Part 1 — `V3__add_user_knowledge_base_scope.sql`)**
> 아래는 이 초안에 없던, 실제 migration을 작성하면서 확정한 세부 사항이다.
> - 기본 Knowledge Base도 `id=1`로 명시적으로 시드한다(사용자와 마찬가지로). `tb_user`/`tb_knowledge_base` 모두 id를 직접 지정해 insert하므로, 그 즉시 `setval(pg_get_serial_sequence(...))`로 시퀀스를 재조정한다 — 안 하면 다음 auto-increment insert가 PK 충돌로 실패한다.
> - `original_filename`/`stored_path`/`content_hash`/`file_size_bytes`는 업로드 API(다음 작업 단위 이후)에서만 채워지는 값이라 NOT NULL을 걸지 않는다. 기존 MVP 문서(디렉터리 색인 결과)는 이 네 컬럼이 전부 NULL인 채로 남는다 — "값이 없다 = 업로드 경로로 들어오지 않은 문서"라는 뜻이다.
> - `DocumentIndexService`(디렉터리 색인)는 아직 `CurrentUserProvider`나 KB 선택 API가 없으므로, 위 시드 값과 짝을 맞춘 상수(`DEFAULT_USER_ID=1L`, `DEFAULT_KNOWLEDGE_BASE_ID=1L`)로 직접 호출한다. 실제 사용자·KB 선택 경로가 생기면(다음 작업 단위 이후) 이 상수를 호출자가 넘기는 값으로 교체한다.
> - `tb_document.source`의 기존 `VARCHAR(1000)`과 복합 UNIQUE 인덱스(최대 바이트 수 제한, PostgreSQL btree 항목당 약 2.7KB)는 V2부터 있던 제약을 그대로 물려받은 것이며, 이번 V3가 새로 만든 위험이 아니다. 한글처럼 멀티바이트 문자로 1000자에 가까운 source를 실제로 쓰는 경우는 아직 없어 당장 막지 않았지만, 다음 단위에서 source 길이 정책을 다시 볼 필요가 있다.

---

## 7. 후순위 선택 기능 (1차 범위 밖)

> 개인 Memory와 대화 요약은 범용 문서 RAG의 필수 전제가 아니다(§0.3 4번). 핵심 흐름(§1~§6)이 먼저 동작한 뒤, 필요성이 확인되면 아래 설계를 참고해 별도 Phase로 재검토한다. 지금 이 문서나 구현 계획의 완료 기준에는 포함하지 않는다.

### 7.1 데이터 모델 (제안, 미확정)

```text
tb_user_memory
 ├─ id, user_id, category, memory_key, memory_value, source, confirmed, created_at, updated_at

tb_conversation_summary
 ├─ id, user_id, knowledge_base_id, conversation_id, summary_text(≤300자), source_count, created_at
```

### 7.2 API (제안, 미확정)

```http
POST /api/memories   {"category": "...", "key": "...", "value": "...", "source": "user_explicit"}
GET  /api/memories
PUT  /api/memories/{id}
DELETE /api/memories/{id}

GET  /api/conversation-summaries?conversationId=...
DELETE /api/conversation-summaries/{id}
```

### 7.3 세부 정책 (제안, 미확정)

- 대화 요약 형식: 평문 2~3문장(질문 핵심 + 답변 핵심 사실 1~2개 + 참조 출처 개수), 최대 300자.
- 요약 생성 실패: 비핵심 후처리 — 답변 API 자체 실패로 전파되지 않음.
- 보존: 저장은 무제한(명시적 삭제 요청 시에만 제거), 조회는 최근 20개까지, 후속 질문 Context에는 최근 3개만 포함.
- Memory 저장 원칙: "기억해줘" 같은 명시적 요청과 사용자 확인 후에만 저장. 모델이 대화에서 추론한 정보를 자동으로 저장하지 않는다.
- **원칙(재확인)**: Memory가 저장돼 있어도, 문서 근거 기반 답변의 출처 표시를 대체하지 않는다. 답변에서 "문서 근거"와 "Memory 근거"는 항상 분리해서 보여준다.

이 기능을 실제로 구현하기로 결정하면, 위 제안을 다시 검토하고 §0.3 설계 원칙(특히 4번)에 맞게 확정한 뒤 별도 Phase로 진행한다.

---

## 8. 설계 쟁점과 추천 기본값

### 8.1 Knowledge Base 여러 개를 한 번에 검색할지

**쟁점**: 사용자가 KB를 여러 개 만들면("한국 소설", "열역학"), 질문할 때 하나만 고를지 여러 개를 동시에 검색할지.

**추천 기본값: 1차는 정확히 하나의 KB만 지정한다(§1, §3.3).** 여러 KB를 암묵적으로 합쳐 검색하는 기능은 1차에 두지 않는다. 이유:
- 설계 원칙 2번("선택한 KB 밖 문서가 섞이지 않아야 한다")을 지키는 가장 단순하고 확실한 방법이다.
- 여러 KB를 섞으면 "소설"과 "열역학"처럼 서로 무관한 문서가 같은 검색 결과에 등장할 수 있어, 근거 품질이 오히려 나빠질 수 있다(이미 성능 평가에서 확인한 "비슷한 문서 간 혼동" 문제가 더 커짐).

**필요해지면**: 사용자가 **명시적으로 KB id 목록을 선택**하는 별도 엔드포인트(예: `POST /api/answers?knowledgeBaseIds=1,2`)를 추가하고, 이때도 `sources[].knowledgeBaseId`로 어느 KB에서 왔는지 항상 구분해 보여준다. "전체"라는 암묵적 마법값은 쓰지 않는다.

### 8.2 "전체 지식"의 의미를 어떻게 정의할지

**쟁점**: 기존 프로토타입(`.hermes/prototypes/personal-rag-chat.html`)은 사이드바 첫 항목이 "내 전체 지식"(128개 문서)으로, 여러 KB를 합산한 듯한 뷰를 기본값으로 보여준다.

**추천 기본값: "전체 지식" 같은 암묵적 합산 뷰는 1차에 두지 않는다.** 프로토타입의 그 레이블은 설계와 맞지 않으므로, Phase 6(프론트엔드 구현) 때 "사용자가 만든 KB 목록 중 하나를 선택"하는 UI로 교체한다(기본 선택값은 "가장 최근에 만든/사용한 KB" 정도면 충분하고, 그 자체도 명시적으로 어떤 KB인지 화면에 표시한다). 진짜 "여러 KB 통합 검색"이 필요하다고 판단되면 §8.1의 명시적 다중 선택 기능으로 구현한다.

### 8.3 Knowledge Base별 프롬프트 설정이 정말 필요한지

**쟁점**: "소설" KB와 "학문" KB가 서로 다른 성격의 질문을 받으니, KB마다 다른 system prompt를 두고 싶다는 유혹이 있을 수 있다.

**추천 기본값: 필요 없다.** 설계 원칙 2번("핵심 로직은 KB에 관계없이 동일하게 동작")과 정면으로 부딪힌다 — KB별 프롬프트를 두면 사실상 "도메인별 분기 코드"가 생기는 것과 같다. KB마다 달라져야 하는 유일한 것은 **검색 대상 문서 집합(데이터)**이며, 프롬프트·검색·답변 로직은 공통이어야 한다. 특정 KB에서 답변 품질이 떨어지면(예: 소설 특유의 비유적 표현), 그건 프롬프트 분기가 아니라 Chunking/검색 품질 개선(`docs/rag_performance_results.md` §9 병목 분석 참고)으로 접근한다.

### 8.4 동명이거나 유사한 문서가 서로 다른 Knowledge Base에 있는 경우

이미 §2/§6에서 데이터 모델로 다룬다 — `UNIQUE(user_id, knowledge_base_id, source)`라 같은 파일명이 다른 KB에 독립적으로 존재할 수 있다. 답변 API는 항상 단일 KB로 범위가 고정돼 있어 검색 시점에 다른 KB의 동명 문서와 혼동될 일은 없지만, **출처 표시에는 항상 `knowledgeBaseId`(또는 이름)를 같이 보여줘서**, 사용자가 나중에 "이 출처가 어느 KB의 문서였는지" 혼동하지 않게 한다(§3.3).
