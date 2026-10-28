-- tb_document.user_id와 tb_document.knowledge_base_id가 같은 소유자를 가리키도록 DB에서 강제한다.
-- V3는 두 컬럼에 각각 단일 FK만 걸어서, "사용자 A의 문서가 사용자 B의 KB에 들어있는" 행을 막지 못했다.
-- V3는 이미 적용된 환경이 있으므로 수정하지 않고 이 forward migration으로 보강한다.
--
-- 방식: tb_knowledge_base(id, user_id)에 UNIQUE를 두고, tb_document(knowledge_base_id, user_id)가
-- 그 쌍을 참조하는 복합 FK를 건다. 이 FK는 KB 소유자를 바꾸는 UPDATE도(참조 문서가 있으면) 막는다.

-- 1) 기존 불일치 데이터 확인. 불일치 행이 있으면 데이터를 조용히 고치거나 지우지 않고
--    건수와 함께 migration 전체를 실패시킨다(트랜잭션 롤백) — 어느 소유자가 맞는지는 사람이 판단한다.
--    현재 코드 경로(DocumentIndexService: user 1/KB 1, V3 기본 귀속: user 1/KB 1)로는 불일치가 생기지 않는다.
DO $$
DECLARE
    mismatch_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO mismatch_count
    FROM tb_document d
    JOIN tb_knowledge_base kb ON kb.id = d.knowledge_base_id
    WHERE kb.user_id <> d.user_id;

    IF mismatch_count > 0 THEN
        -- 확인용 조회: SELECT d.id, d.user_id, kb.user_id FROM tb_document d
        --   JOIN tb_knowledge_base kb ON kb.id = d.knowledge_base_id WHERE kb.user_id <> d.user_id;
        RAISE EXCEPTION 'V4 중단: tb_document %건의 user_id가 knowledge_base_id 소유자와 다릅니다. 데이터를 정리한 뒤 다시 적용하세요.',
            mismatch_count;
    END IF;
END $$;

-- 2) 복합 FK의 참조 대상. id가 이미 PK라 항상 유일하므로 이 제약 자체는 실패하지 않는다.
ALTER TABLE tb_knowledge_base
    ADD CONSTRAINT uq_tb_knowledge_base_id_user UNIQUE (id, user_id);

-- 3) 문서의 (knowledge_base_id, user_id) 쌍이 실제 KB의 (id, user_id)와 같아야 한다.
--    V3의 fk_document_user / fk_document_knowledge_base는 그대로 둔다(중복이지만 무해하다).
ALTER TABLE tb_document
    ADD CONSTRAINT fk_document_knowledge_base_owner
        FOREIGN KEY (knowledge_base_id, user_id)
        REFERENCES tb_knowledge_base(id, user_id);
