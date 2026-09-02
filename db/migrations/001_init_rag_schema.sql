CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS tb_document (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    source VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_document_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    embedding vector(1024) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_document_chunk_document
        FOREIGN KEY (document_id)
        REFERENCES tb_document(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_tb_document_chunk_document_id
    ON tb_document_chunk(document_id);
