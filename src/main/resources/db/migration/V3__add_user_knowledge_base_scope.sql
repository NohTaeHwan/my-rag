-- 사용자·Knowledge Base 범위로 tb_document를 확장한다.
-- 설계: docs/personal_rag_chatbot_design.md §2, §6 / 작업 계획: .hermes/plan_part1/2026-10-23_180211-...md
--
-- 적용 범위: 이번 migration은 "DB 저장 구조"만 바꾼다. 검색 SQL의 사용자·KB 격리,
-- Knowledge Base 생성·목록 API, 업로드·비동기 색인은 다음 작업 단위에서 별도로 다룬다.

-- 1) 신규 테이블: tb_user, tb_knowledge_base
--    domain/type/category 같은 컬럼을 두지 않는다 — Knowledge Base를 구분하는 것은
--    "어떤 문서가 들어있는가"이며, name은 사용자가 붙인 자유 라벨일 뿐이다.
CREATE TABLE IF NOT EXISTS tb_user (
    id BIGSERIAL PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_knowledge_base (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_knowledge_base_user
        FOREIGN KEY (user_id)
        REFERENCES tb_user(id)
);

CREATE INDEX IF NOT EXISTS idx_tb_knowledge_base_user_id
    ON tb_knowledge_base(user_id);

-- 2) 개발용 고정 사용자(id=1)와 기본 Knowledge Base(id=1)를 시드한다.
--    id를 직접 지정해서 넣으므로, BIGSERIAL 시퀀스가 그 값을 모른 채 다음 auto-increment를
--    시도하면 PK 충돌이 날 수 있다 — 아래 setval로 시퀀스를 매번 안전하게 재조정한다.
--    (이 DB에 다른 행이 이미 있어 1번이 비어 있지 않을 가능성은 없다고 가정하지 않고,
--     ON CONFLICT로 중복 실행에도 안전하게 만든다.)
INSERT INTO tb_user (id, display_name)
VALUES (1, '개발용 사용자')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('tb_user', 'id'), (SELECT MAX(id) FROM tb_user));

INSERT INTO tb_knowledge_base (id, user_id, name, status)
VALUES (1, 1, '기본 Knowledge Base', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('tb_knowledge_base', 'id'), (SELECT MAX(id) FROM tb_knowledge_base));

-- 3) tb_document 컬럼 추가. 기존 행이 있을 수 있으므로 일단 NULL 허용으로 추가하고,
--    데이터 귀속(4번) 이후에 NOT NULL을 건다(순서를 바꾸면 기존 행에서 ALTER가 그 자리에서 실패한다).
ALTER TABLE tb_document
    ADD COLUMN IF NOT EXISTS user_id BIGINT,
    ADD COLUMN IF NOT EXISTS knowledge_base_id BIGINT,
    ADD COLUMN IF NOT EXISTS original_filename VARCHAR(255),
    ADD COLUMN IF NOT EXISTS stored_path VARCHAR(500),
    ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS file_size_bytes BIGINT,
    ADD COLUMN IF NOT EXISTS index_status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS indexed_at TIMESTAMP;

-- 4) 기존 행(있다면 기존 MVP 디렉터리 색인 결과)을 개발용 사용자(1)의 기본 KB(1)로 귀속시킨다.
--    이 행들은 이미 동기 색인이 끝난 상태이므로 index_status='INDEXED'로 채우고,
--    별도로 기록해둔 색인 완료 시각이 없으므로 created_at을 근사값으로 쓴다.
--
--    original_filename/stored_path/content_hash/file_size_bytes는 업로드 API(다음 작업 단위
--    이후)에서만 채워지는 값이라 여기서는 의도적으로 NULL로 남긴다 — 그래서 이 네 컬럼에는
--    NOT NULL을 걸지 않는다. "값이 없다 = 업로드 경로로 들어오지 않은 문서"라는 뜻이 된다.
UPDATE tb_document
SET user_id = 1,
    knowledge_base_id = 1,
    index_status = 'INDEXED',
    indexed_at = created_at
WHERE user_id IS NULL;

-- 5) 데이터 귀속 이후에만 안전한 제약을 적용한다.
ALTER TABLE tb_document
    ALTER COLUMN user_id SET NOT NULL,
    ALTER COLUMN knowledge_base_id SET NOT NULL,
    ALTER COLUMN index_status SET NOT NULL;

ALTER TABLE tb_document
    ADD CONSTRAINT fk_document_user
        FOREIGN KEY (user_id)
        REFERENCES tb_user(id),
    ADD CONSTRAINT fk_document_knowledge_base
        FOREIGN KEY (knowledge_base_id)
        REFERENCES tb_knowledge_base(id);

CREATE INDEX IF NOT EXISTS idx_tb_document_user_kb
    ON tb_document(user_id, knowledge_base_id);

-- 6) V2의 전역 UNIQUE(source)를 사용자·KB 범위로 교체한다.
--    (다른 사용자, 또는 같은 사용자의 다른 KB가 같은 파일명을 쓸 수 있어야 하므로.)
--    V2와 마찬가지로 이 ALTER 자체가 (user_id, knowledge_base_id, source) 중복이 있으면
--    unique_violation으로 실패한다 — 데이터를 조용히 지우거나 합치지 않는다.
ALTER TABLE tb_document
    DROP CONSTRAINT uq_tb_document_source;

ALTER TABLE tb_document
    ADD CONSTRAINT uq_tb_document_user_kb_source UNIQUE (user_id, knowledge_base_id, source);
