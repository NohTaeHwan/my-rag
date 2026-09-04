package com.nohtaehwan.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.DoubleStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.nohtaehwan.rag.document.DocumentChunk;
import com.nohtaehwan.rag.health.mapper.HealthMapper;

/**
 * Mockito로는 증명할 수 없는, 실제 PostgreSQL(+pgvector)에 대해서만 확인 가능한 동작을 검증한다.
 * 로컬 docker-compose {@code rag-postgres} 컨테이너가 떠 있어야 통과한다 — 이 프로젝트의 기존
 * 관례(별도 @Tag 분리 없이 {@link com.nohtaehwan.rag.RagBackendApplicationTests}처럼 DB가
 * 없으면 그대로 실패)를 그대로 따른다.
 */
@SpringBootTest
class DocumentPersistenceIntegrationTest {

    private static final String SOURCE_A = "integration-test/doc-a.md";
    private static final String SOURCE_B = "integration-test/doc-b.md";

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private HealthMapper healthMapper;

    // 테스트 셋업/검증/정리 전용. 애플리케이션 코드는 더 이상 JdbcTemplate을 쓰지 않는다.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM tb_document WHERE source IN (?, ?)", SOURCE_A, SOURCE_B);
    }

    private static List<Double> vector(int dimension) {
        return DoubleStream.iterate(0.1, d -> d + 0.001).limit(dimension).boxed().toList();
    }

    private static EmbeddedChunk embeddedChunk(String source, int index, String content, int dimension) {
        DocumentChunk chunk = new DocumentChunk(source, source + "#" + index, index, List.of(), content, content.length());
        return new EmbeddedChunk(chunk, vector(dimension));
    }

    private Long documentIdOf(String source) {
        return jdbcTemplate.queryForObject("SELECT id FROM tb_document WHERE source = ?", Long.class, source);
    }

    @Test
    void healthMapper는_1을_반환한다() {
        assertThat(healthMapper.selectOne()).isEqualTo(1);
    }

    @Test
    void reindex_문서와_Chunk를_저장하고_vector1024에_정상_저장된다() {
        EmbeddedChunk chunk = embeddedChunk(SOURCE_A, 0, "content-a0", 1024);

        documentRepository.reindex("Title A", SOURCE_A, List.of(chunk));

        Long documentId = documentIdOf(SOURCE_A);
        assertThat(documentId).isNotNull();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT content, chunk_index, vector_dims(embedding) AS dims "
                        + "FROM tb_document_chunk WHERE document_id = ?",
                documentId);
        assertThat(row.get("content")).isEqualTo("content-a0");
        assertThat(row.get("chunk_index")).isEqualTo(0);
        assertThat(row.get("dims")).isEqualTo(1024);
    }

    @Test
    void reindex_같은_source로_재색인하면_기존_문서와_Chunk가_새것으로_대체된다() {
        documentRepository.reindex("Title A v1", SOURCE_A, List.of(embeddedChunk(SOURCE_A, 0, "old-content", 1024)));
        Long oldId = documentIdOf(SOURCE_A);

        documentRepository.reindex("Title A v2", SOURCE_A, List.of(
                embeddedChunk(SOURCE_A, 0, "new-content-0", 1024),
                embeddedChunk(SOURCE_A, 1, "new-content-1", 1024)));

        Integer documentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document WHERE source = ?", Integer.class, SOURCE_A);
        assertThat(documentCount).isEqualTo(1);

        Long newId = documentIdOf(SOURCE_A);
        assertThat(newId).isNotEqualTo(oldId);

        // ON DELETE CASCADE로 이전 문서의 Chunk까지 함께 삭제됐는지 확인 (이전 id로는 조회되지 않음)
        Integer oldChunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document_chunk WHERE document_id = ?", Integer.class, oldId);
        assertThat(oldChunkCount).isZero();

        Integer newChunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document_chunk WHERE document_id = ?", Integer.class, newId);
        assertThat(newChunkCount).isEqualTo(2);
    }

    @Test
    void reindex_Chunk_insert가_실패하면_기존_문서가_그대로_남는다() {
        documentRepository.reindex("Title A", SOURCE_A, List.of(embeddedChunk(SOURCE_A, 0, "original-content", 1024)));
        Long originalId = documentIdOf(SOURCE_A);

        // vector(1024) 컬럼에 차원이 다른 벡터를 넣어 실제 pgvector 타입 오류를 강제로 유발한다(mock 아님).
        EmbeddedChunk invalidChunk = embeddedChunk(SOURCE_A, 0, "broken", 8);

        assertThatThrownBy(() -> documentRepository.reindex("Title A v2", SOURCE_A, List.of(invalidChunk)))
                .isInstanceOf(DataAccessException.class);

        Integer documentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document WHERE source = ?", Integer.class, SOURCE_A);
        assertThat(documentCount).isEqualTo(1);

        Long remainingId = documentIdOf(SOURCE_A);
        assertThat(remainingId).isEqualTo(originalId);

        String remainingContent = jdbcTemplate.queryForObject(
                "SELECT content FROM tb_document_chunk WHERE document_id = ?", String.class, remainingId);
        assertThat(remainingContent).isEqualTo("original-content");
    }

    @Test
    void reindex_다른_source_문서는_영향을_받지_않는다() {
        documentRepository.reindex("Title A", SOURCE_A, List.of(embeddedChunk(SOURCE_A, 0, "content-a", 1024)));
        documentRepository.reindex("Title B", SOURCE_B, List.of(embeddedChunk(SOURCE_B, 0, "content-b", 1024)));

        documentRepository.reindex("Title A v2", SOURCE_A, List.of(embeddedChunk(SOURCE_A, 0, "content-a-v2", 1024)));

        Integer sourceBCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document WHERE source = ?", Integer.class, SOURCE_B);
        assertThat(sourceBCount).isEqualTo(1);

        String sourceBContent = jdbcTemplate.queryForObject(
                "SELECT c.content FROM tb_document_chunk c JOIN tb_document d ON c.document_id = d.id "
                        + "WHERE d.source = ?",
                String.class, SOURCE_B);
        assertThat(sourceBContent).isEqualTo("content-b");
    }
}
