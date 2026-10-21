# 개인용 RAG 챗봇 확장 — Phase 0 설계 (API 계약·데이터 모델)

> 관련 문서: `.hermes/plans/2026-09-21_151055-personal-rag-chatbot-implementation.md`(구현 계획),
> `.hermes/plans/2026-09-14_220046-personal-knowledge-rag-chatbot.md`(제품 기획, §15 의사결정 반영됨)
>
> 이 문서는 Phase 0 산출물이다. Phase 1부터는 이 문서를 기준으로 구현하고, 실제 구현 중 바뀐 내용은 이 문서를 함께 갱신한다.

---

## 1. Phase 0 세부 항목 결정

| 항목 | 결정 |
|---|---|
| 업로드 파일 최대 크기 | 20MB |
| 허용 확장자 | `.md`만 (PDF/DOCX/코드는 Markdown 흐름 안정화 후 별도 Phase) |
| 원본 파일 저장 위치 | 서버 디스크, `{storage-root}/users/{userId}/kb/{knowledgeBaseId}/{documentId}.md` |
| 디스크 파일명 규칙 | `documentId` 기반 고정 — 충돌·특수문자 문제 회피. 사용자가 올린 원래 파일명은 DB `original_filename` 컬럼에 별도 보관 |
| 보존 정책 | 자동 만료 없음. 사용자가 명시적으로 삭제 요청할 때만 원본+Chunk+Embedding 함께 삭제 |
| 대화 요약 형식 | 평문 2~3문장(질문 핵심 + 답변 핵심 사실 1~2개 + 참조 출처 개수), 최대 300자 |
| 대화 요약 실패 처리 | 비핵심 후처리 — 요약 생성 실패가 답변 API 자체 실패로 전파되지 않음 |
| 개발용 사용자 식별 | `CurrentUserProvider` 인터페이스 + 고정 `user_id` 반환 구현체. 공개 전환 시 이 Bean만 실제 인증 기반 구현체로 교체 |
| 경로 노출(UI) | 논리적 경로/원본 파일명만 노출, 서버 실제 절대경로는 노출하지 않음 |
| Knowledge Base 개수 | 사용자당 여러 개 허용 |
| 색인 처리 방식 | 비동기 (업로드 응답은 작업 ID + `PENDING`) |

---

## 2. 데이터 모델 초안

기존 `tb_document`/`tb_document_chunk`는 컬럼을 추가하는 방식으로 확장한다(재생성하지 않음).

```text
tb_user
 ├─ id BIGSERIAL PK
 ├─ display_name VARCHAR(255)          -- 개발용 고정 사용자 1행만 시드
 └─ created_at TIMESTAMP

tb_knowledge_base
 ├─ id BIGSERIAL PK
 ├─ user_id BIGINT FK → tb_user.id
 ├─ name VARCHAR(255) NOT NULL
 ├─ status VARCHAR(20) NOT NULL        -- ACTIVE, DELETED
 ├─ created_at TIMESTAMP
 └─ updated_at TIMESTAMP

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
       (다른 사용자가 같은 파일명을 올릴 수 있으므로). 아래 §5 참고.

tb_document_chunk
 └─ 기존 컬럼 유지(document_id FK로 이미 사용자·KB 범위가 간접적으로 걸림 — 별도 컬럼 추가 없음)

tb_document_index_job
 ├─ id BIGSERIAL PK
 ├─ document_id BIGINT FK → tb_document.id
 ├─ status VARCHAR(20) NOT NULL         -- PENDING/PROCESSING/INDEXED/FAILED
 ├─ error_message TEXT
 ├─ created_at TIMESTAMP
 └─ updated_at TIMESTAMP

tb_user_memory
 ├─ id BIGSERIAL PK
 ├─ user_id BIGINT FK → tb_user.id
 ├─ category VARCHAR(50)                -- career, preference, profile, goal 등
 ├─ memory_key VARCHAR(255)
 ├─ memory_value TEXT
 ├─ source VARCHAR(20) NOT NULL          -- user_explicit (초기엔 이 값만 사용)
 ├─ confirmed BOOLEAN NOT NULL DEFAULT false
 ├─ created_at TIMESTAMP
 └─ updated_at TIMESTAMP

tb_conversation_summary
 ├─ id BIGSERIAL PK
 ├─ user_id BIGINT FK → tb_user.id
 ├─ knowledge_base_id BIGINT FK → tb_knowledge_base.id
 ├─ conversation_id VARCHAR(64) NOT NULL  -- 클라이언트가 생성해 매 질문에 실어 보내는 UUID
 ├─ summary_text VARCHAR(300) NOT NULL
 ├─ source_count INTEGER NOT NULL DEFAULT 0
 └─ created_at TIMESTAMP
```

Migration 파일 매핑(구현 계획과 동일):
- `V3__add_user_knowledge_base_scope.sql` — `tb_user`, `tb_knowledge_base`, `tb_document` 컬럼 추가, UNIQUE 제약 완화
- `V4__add_document_index_job.sql` — `tb_document_index_job`
- `V5__add_user_memory.sql` — `tb_user_memory`
- `V6__add_conversation_summary.sql` — `tb_conversation_summary`

---

## 3. API 계약 초안

모든 요청은 `CurrentUserProvider`가 resolve하는 `user_id`를 서비스 계층까지 전달한다(요청 파라미터로 직접 받지 않음 — 나중에 인증으로 교체될 때 API 시그니처가 안 바뀌게 하기 위함).

### 3.1 Knowledge Base

```http
POST /api/knowledge-bases
{"name": "RAG 프로젝트"}
→ 201 {"id": 2, "name": "RAG 프로젝트", "status": "ACTIVE", "createdAt": "..."}

GET /api/knowledge-bases
→ 200 [{"id": 1, "name": "내 전체 지식", "status": "ACTIVE", "documentCount": 128}, ...]
```

### 3.2 문서 업로드·색인 상태

```http
POST /api/knowledge-bases/{kbId}/documents  (multipart/form-data, file 필드 1개)
→ 202 {"documentId": 55, "indexJobId": 12, "status": "PENDING"}

  실패 응답:
  400 확장자 불허/빈 파일/20MB 초과 — {"message": "..."}

GET /api/index-jobs/{jobId}
→ 200 {"jobId": 12, "documentId": 55, "status": "INDEXED", "errorMessage": null}

GET /api/knowledge-bases/{kbId}/documents
→ 200 [{"documentId": 55, "originalFilename": "회고.md", "indexStatus": "INDEXED", "indexedAt": "..."}, ...]

DELETE /api/knowledge-bases/{kbId}/documents/{documentId}
→ 204  (원본 파일 + Chunk + Embedding 함께 삭제)
```

동일 파일 재업로드 시: `content_hash` 비교 → 같으면 `PENDING` 생성 없이 기존 상태 그대로 반환(불필요한 재색인 방지), 다르면 새 색인 작업 생성.

### 3.3 답변

```http
POST /api/knowledge-bases/{kbId}/answers
{"question": "결제 실패 시 상태 코드는?", "conversationId": "c-uuid-1234"}
→ 200 {
    "answer": "...",
    "sources": [{"documentId": 55, "originalFilename": "결제정책.md", "chunkIndex": 1, "distance": 0.21}],
    "conversationId": "c-uuid-1234",
    "memoryCandidate": null
  }

근거 부족: answer는 기존 고정 문구, sources: []
동시성 제한 초과: 429 {"message": "지금 요청이 많아 잠시 후 다시 시도해주세요."}
```

`conversationId`는 클라이언트가 생성해서 매 질문에 실어 보낸다(서버는 발급하지 않음 — 대화 원문을 저장하지 않으므로 서버가 세션을 유지할 필요가 없다).

`memoryCandidate`: 질문이 "~라고 기억해줘" 패턴이면, 이 필드에 `{category, key, value}` 후보를 채워 반환하고 `answer`는 "이 내용을 기억할까요?" 안내 문구로 대체한다. 실제 저장은 아래 3.4의 확인 API로 별도 수행한다(답변 API 자체가 즉시 저장하지 않음 — 사용자 확인 원칙).

### 3.4 Memory

```http
POST /api/memories
{"category": "goal", "key": "current_goal", "value": "개인 RAG 비서 개발", "source": "user_explicit"}
→ 201 {"id": 3, "confirmed": true, ...}

GET /api/memories
→ 200 [{"id": 3, "category": "goal", "key": "current_goal", "value": "...", "updatedAt": "..."}]

PUT /api/memories/{id}
DELETE /api/memories/{id}
```

### 3.5 대화 요약

```http
GET /api/conversation-summaries?conversationId=c-uuid-1234
→ 200 [{"id": 9, "summaryText": "...", "sourceCount": 2, "createdAt": "..."}]

DELETE /api/conversation-summaries/{id}
→ 204
```

---

## 4. 사용자 흐름

### 4.1 업로드 → 색인
```
파일 선택 → POST /documents (202, PENDING)
→ 클라이언트가 GET /index-jobs/{id} polling
→ PROCESSING → INDEXED 또는 FAILED(errorMessage 표시)
```

### 4.2 질문 → 답변
```
질문 입력(+ KB 선택 + conversationId)
→ POST /answers
→ 답변 렌더링 + 출처 chip(원본 파일명 표시, 클릭 시 Chunk 내용 모달)
→ 답변 완료 후 서버가 비동기로 대화 요약 생성·저장(클라이언트는 기다리지 않음)
```

### 4.3 Memory 승인
```
질문이 "~기억해줘" 패턴 → 답변 API가 memoryCandidate 반환
→ UI가 "기억할까요?" 카드 표시
→ 사용자가 "기억하기" 클릭 → POST /memories
→ 사용자가 거절 → 아무 것도 저장하지 않음(서버에 임시 상태도 남기지 않음)
```

### 4.4 대화 요약 삭제
```
사용자가 특정 요약을 UI에서 삭제 요청 → DELETE /conversation-summaries/{id}
→ 다음 질문부터 해당 요약이 Context에 포함되지 않음
```

---

## 5. Phase 0 세부 정책 (구현 계획서 0-2~0-6 대응)

### 업로드 정책
- Knowledge Base별 파일 개수·전체 용량 제한: **없음(1차)** — 개인용 범위라 필요성이 확인되면 추가한다.
- 빈 파일: 업로드 자체를 **400으로 거부**한다(색인 작업을 만들지 않음).
- 손상 파일(파싱 실패 등 콘텐츠 문제): 업로드는 받아들이되 **색인 단계에서 `FAILED` + `error_message`**로 기록한다(업로드 검증은 확장자·크기·빈 파일만, 콘텐츠 유효성은 색인 단계 책임).
- 파일명 정규화·경로 조작 방어: 원본 파일명에서 경로 구분자(`/`, `\`, `..`)를 제거/치환한 뒤 `original_filename`에 저장한다. 디스크 실제 경로는 `documentId` 기반으로 고정되므로(§1) 원본 파일명이 경로 조작에 쓰일 여지가 애초에 없다.

### 색인 작업 정책
- 색인 실패 시 재시도: **없음** — 기존 MVP 정책(`rag_mvp_development_plan.md` 5단계, fail-fast)과 동일하게 유지한다. 실패는 `FAILED`로 기록하고, 재시도는 사용자가 파일을 다시 업로드하는 방식으로만 이루어진다.
- 색인 중(`PENDING`/`PROCESSING`) 문서의 검색 포함 여부: **포함하지 않는다**. 검색 SQL에 `index_status = 'INDEXED'` 조건을 추가한다(Phase 1/3 구현에 반영).
- 색인 작업 동시 실행 제한: 사용자당 **단일 스레드 executor로 순차 처리**한다(별도 세마포어 카운터를 두지 않고 처리 자체를 직렬화). 개인용 트래픽 규모에서 병렬화 이득보다 구현 단순성이 우선이다.

### 개발용 사용자 식별 방식
- 고정 `user_id` 값: **1** (`tb_user`에 마이그레이션으로 1행 시드).
- Controller → Service → Mapper 전달 규칙: **Controller가 `CurrentUserProvider.currentUserId()`로 조회한 값을 Service 메서드 파라미터로 명시적으로 전달**한다 — Service가 `CurrentUserProvider`에 직접 의존하지 않는다(테스트 시 Service를 순수 값 파라미터로 검증할 수 있게 하기 위함). Mapper는 Repository/Service가 넘긴 값을 그대로 SQL 파라미터로 받는다.
- 공개 환경 비활성화: 고정 사용자 구현체는 나중에 프로파일 가드(예: 운영 프로파일에서는 이 Bean이 뜨지 않고 실제 인증 구현체가 반드시 있어야 기동)를 적용한다. 지금은 프로파일이 `default` 하나뿐이라 실제 분리는 공개 전환 시점(Phase 6 이후)의 작업으로 남겨두고, 이 지점만 설계에 표시해둔다.

### 대화 요약 정책
- Knowledge Base별 요약 보존 개수: **무제한 저장**(삭제는 사용자가 명시적으로 요청할 때만) — 조회 API(`GET /conversation-summaries`)는 최근 **20개**까지만 반환한다.
- 후속 질문 Context에 포함할 요약 개수: 최근 **3개**만 포함한다(Context budget 낭비 방지).
- 조회·삭제 API 범위: §3.5에 정의된 `GET /api/conversation-summaries?conversationId=...`, `DELETE /api/conversation-summaries/{id}`로 충분하다(추가 범위 없음).

### Phase 1 범위 확정
- Phase 1 범위: **DB 스키마 확장(`tb_user`/`tb_knowledge_base`/`tb_document` 컬럼) + 검색 SQL의 사용자·KB 격리만.**
- Phase 1 제외 범위: 업로드 API, 비동기 색인 작업, Memory, 대화 요약, LLM 동시성 제한, 프론트엔드 — 전부 Phase 2 이후.

---

## 6. Phase 1에서 확인이 필요한 기존 제약

- `tb_document`의 기존 `UNIQUE(source)` 제약(`V2__add_unique_constraint_to_document_source.sql`)은 전역 유일성을 강제한다. 사용자별 구조로 바뀌면 **`UNIQUE(user_id, knowledge_base_id, source)`로 완화**해야 한다 — 그렇지 않으면 사용자 B가 사용자 A와 같은 파일명(`source`)을 업로드하는 순간 실패한다. V3 migration에서 기존 제약을 drop하고 복합 UNIQUE로 재생성한다.
- `DocumentRepository.reindex()`의 `deleteBySource(sourceKey)`도 같은 이유로 `deleteByUserIdAndKnowledgeBaseIdAndSource(...)`로 범위를 좁혀야 한다.
- `SearchMapper`의 검색 SQL은 현재 전체 `tb_document_chunk`를 대상으로 한다 — `WHERE d.user_id = ? AND d.knowledge_base_id = ?` 조건을 추가해야 한다(Phase 1의 핵심 작업이자 완료 기준).
- 기존 MVP 데이터(현재 로컬/Spark에 색인된 `order-management.md` 등)는 마이그레이션 시 개발용 고정 사용자(`user_id=1`)의 기본 Knowledge Base로 귀속시킨다.
