package com.nohtaehwan.rag.indexing;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * tb_document_chunk 테이블에 Chunk와 embedding 벡터를 저장한다.
 */
@Repository
public class DocumentChunkRepository {

    private static final String INSERT_SQL =
            "INSERT INTO tb_document_chunk (document_id, content, chunk_index, embedding) VALUES (?, ?, ?, ?::vector)";

    private final JdbcTemplate jdbcTemplate;

    public DocumentChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 문서에 속한 Chunk 전체를 batch insert한다. embedding은 pgvector 리터럴 문자열로 변환해 파라미터 바인딩한다.
     *
     * @param documentId 소속 tb_document.id
     * @param chunks     저장할 Chunk와 embedding 목록 (빈 목록이면 아무 것도 하지 않는다)
     */
    public void insertAll(Long documentId, List<EmbeddedChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                EmbeddedChunk embeddedChunk = chunks.get(i);
                ps.setLong(1, documentId);
                ps.setString(2, embeddedChunk.chunk().content());
                ps.setInt(3, embeddedChunk.chunk().index());
                ps.setString(4, toVectorLiteral(embeddedChunk.embedding()));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    private static String toVectorLiteral(List<Double> embedding) {
        return embedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
