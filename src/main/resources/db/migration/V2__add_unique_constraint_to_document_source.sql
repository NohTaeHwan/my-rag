-- source 중복 데이터가 있으면 아래 ALTER TABLE 자체가 unique_violation으로 실패하고
-- Flyway가 이 migration을 실패 처리한다(조용히 통과하지 않는다).
--
-- migration 적용 전 중복 여부는 아래 쿼리로 직접 확인한다(운영 DB에서 결과 값을 로그·문서에 남기지 않는다).
--
--   SELECT source, COUNT(*)
--   FROM tb_document
--   GROUP BY source
--   HAVING COUNT(*) > 1;
--
-- 중복이 있으면 이 migration을 적용하기 전에 어느 행을 남길지 직접 판단해 정리해야 한다.
-- (자동 삭제하지 않는다 — 어떤 버전의 문서를 남길지는 운영 판단이 필요하다.)
ALTER TABLE tb_document
    ADD CONSTRAINT uq_tb_document_source UNIQUE (source);
