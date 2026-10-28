package com.nohtaehwan.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.DoubleStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.nohtaehwan.rag.document.DocumentChunk;
import com.nohtaehwan.rag.health.mapper.HealthMapper;
import com.nohtaehwan.rag.testing.IsolatedDatabaseGuard;
import com.nohtaehwan.rag.testing.IsolatedDatabaseGuard.Mode;

/**
 * Mockito로는 증명할 수 없는, 실제 PostgreSQL(+pgvector)에 대해서만 확인 가능한 동작을 검증한다.
 * {@code isolated-integration} tag라 기본 {@code test} task에서 제외되고 {@code ./gradlew isolatedIntegrationTest}로만
 * 실행된다. 그 task만 Flyway를 켜며, migrate·insert·cleanup 전에 {@link IsolatedDatabaseGuard}가 격리 통합 DB인지
 * 확인하고 아니면 실패한다.
 *
 * <p>V3 migration(사용자·Knowledge Base 범위) 이후에는 개발용 사용자(id=1)의 기본 KB(id=1)뿐
 * 아니라, 그 범위 밖(다른 사용자 또는 다른 KB)에서 같은 source가 공존·격리되는지도 함께
 * 검증한다.
 *
 * <p>테스트 실행마다 UUID 기반 고유 source와, 필요할 때만 새로 생성한 두 번째 KB(생성된 id를
 * 캡처)를 사용한다. cleanup은 이 실행에서 만든 source의 문서와 이 실행에서 만든 KB만 삭제한다 —
 * 고정 source나 고정 KB id로 기존 데이터를 지우지 않는다.
 */
@Tag("isolated-integration")
@SpringBootTest
@Import(IsolatedDatabaseGuard.GuardedFlywayConfig.class)
class DocumentPersistenceIntegrationTest {

    private static final Long USER_1 = 1L;
    private static final Long KB_1 = 1L;

    // JUnit은 테스트 메서드마다 새 인스턴스를 만들므로 실행마다 고유한 값이 된다.
    private final String runId = UUID.randomUUID().toString();
    private final String sourceA = "integration-test/" + runId + "/doc-a.md";
    private final String sourceB = "integration-test/" + runId + "/doc-b.md";

    /** {@link #createSecondKnowledgeBase()}가 이 실행에서 생성한 KB id. 만들지 않았으면 null. */
    private Long secondKbId;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private HealthMapper healthMapper;

    // 테스트 셋업/검증/정리 전용. 애플리케이션 코드는 더 이상 JdbcTemplate을 쓰지 않는다.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /** insert 전에 격리 통합 DB인지 확인한다. 아니면 실패한다. */
    @BeforeEach
    void requireIsolatedDatabase() {
        IsolatedDatabaseGuard.verify(dataSource, Mode.INTEGRATION);
    }

    @AfterEach
    void cleanUp() {
        IsolatedDatabaseGuard.verify(dataSource, Mode.INTEGRATION);
        // 문서를 먼저 지워야 KB를 참조하는 FK에 걸리지 않는다. Chunk는 ON DELETE CASCADE로 함께 삭제된다.
        jdbcTemplate.update("DELETE FROM tb_document WHERE source IN (?, ?)", sourceA, sourceB);
        if (secondKbId != null) {
            jdbcTemplate.update("DELETE FROM tb_knowledge_base WHERE id = ?", secondKbId);
        }
    }

    /**
     * 테스트 전용 두 번째 Knowledge Base(같은 개발용 사용자 소유)를 새로 만들고 생성된 id를
     * {@link #secondKbId}에 캡처한다. id를 지정하지 않으므로 기존 KB와 충돌하지 않고, cleanup은 이 id만 삭제한다.
     */
    private void createSecondKnowledgeBase() {
        secondKbId = jdbcTemplate.queryForObject(
                "INSERT INTO tb_knowledge_base (user_id, name, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                Long.class, USER_1, "격리 테스트용 두 번째 KB " + runId);
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
        EmbeddedChunk chunk = embeddedChunk(sourceA, 0, "content-a0", 1024);

        documentRepository.reindex(USER_1, KB_1, "Title A", sourceA, List.of(chunk));

        Long documentId = documentIdOf(sourceA);
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
        documentRepository.reindex(USER_1, KB_1, "Title A v1", sourceA, List.of(embeddedChunk(sourceA, 0, "old-content", 1024)));
        Long oldId = documentIdOf(sourceA);

        documentRepository.reindex(USER_1, KB_1, "Title A v2", sourceA, List.of(
                embeddedChunk(sourceA, 0, "new-content-0", 1024),
                embeddedChunk(sourceA, 1, "new-content-1", 1024)));

        Integer documentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document WHERE source = ?", Integer.class, sourceA);
        assertThat(documentCount).isEqualTo(1);

        Long newId = documentIdOf(sourceA);
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
        documentRepository.reindex(USER_1, KB_1, "Title A", sourceA, List.of(embeddedChunk(sourceA, 0, "original-content", 1024)));
        Long originalId = documentIdOf(sourceA);

        // vector(1024) 컬럼에 차원이 다른 벡터를 넣어 실제 pgvector 타입 오류를 강제로 유발한다(mock 아님).
        EmbeddedChunk invalidChunk = embeddedChunk(sourceA, 0, "broken", 8);

        assertThatThrownBy(() -> documentRepository.reindex(USER_1, KB_1, "Title A v2", sourceA, List.of(invalidChunk)))
                .isInstanceOf(DataAccessException.class);

        Integer documentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document WHERE source = ?", Integer.class, sourceA);
        assertThat(documentCount).isEqualTo(1);

        Long remainingId = documentIdOf(sourceA);
        assertThat(remainingId).isEqualTo(originalId);

        String remainingContent = jdbcTemplate.queryForObject(
                "SELECT content FROM tb_document_chunk WHERE document_id = ?", String.class, remainingId);
        assertThat(remainingContent).isEqualTo("original-content");
    }

    @Test
    void reindex_다른_source_문서는_영향을_받지_않는다() {
        documentRepository.reindex(USER_1, KB_1, "Title A", sourceA, List.of(embeddedChunk(sourceA, 0, "content-a", 1024)));
        documentRepository.reindex(USER_1, KB_1, "Title B", sourceB, List.of(embeddedChunk(sourceB, 0, "content-b", 1024)));

        documentRepository.reindex(USER_1, KB_1, "Title A v2", sourceA, List.of(embeddedChunk(sourceA, 0, "content-a-v2", 1024)));

        Integer sourceBCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document WHERE source = ?", Integer.class, sourceB);
        assertThat(sourceBCount).isEqualTo(1);

        String sourceBContent = jdbcTemplate.queryForObject(
                "SELECT c.content FROM tb_document_chunk c JOIN tb_document d ON c.document_id = d.id "
                        + "WHERE d.source = ?",
                String.class, sourceB);
        assertThat(sourceBContent).isEqualTo("content-b");
    }

    @Test
    void 같은_사용자의_다른_Knowledge_Base에는_동일_source_문서가_공존한다() {
        createSecondKnowledgeBase();

        documentRepository.reindex(USER_1, KB_1, "KB1 문서", sourceA, List.of(embeddedChunk(sourceA, 0, "kb1-content", 1024)));
        documentRepository.reindex(USER_1, secondKbId, "KB2 문서", sourceA, List.of(embeddedChunk(sourceA, 0, "kb2-content", 1024)));

        Integer sameSourceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document WHERE source = ?", Integer.class, sourceA);
        assertThat(sameSourceCount).isEqualTo(2);

        String kb1Content = jdbcTemplate.queryForObject(
                "SELECT c.content FROM tb_document_chunk c JOIN tb_document d ON c.document_id = d.id "
                        + "WHERE d.source = ? AND d.knowledge_base_id = ?",
                String.class, sourceA, KB_1);
        assertThat(kb1Content).isEqualTo("kb1-content");

        String kb2Content = jdbcTemplate.queryForObject(
                "SELECT c.content FROM tb_document_chunk c JOIN tb_document d ON c.document_id = d.id "
                        + "WHERE d.source = ? AND d.knowledge_base_id = ?",
                String.class, sourceA, secondKbId);
        assertThat(kb2Content).isEqualTo("kb2-content");
    }

    @Test
    void 한_Knowledge_Base의_재색인은_다른_Knowledge_Base의_동일_source_문서를_삭제하지_않는다() {
        createSecondKnowledgeBase();

        documentRepository.reindex(USER_1, KB_1, "KB1 문서", sourceA, List.of(embeddedChunk(sourceA, 0, "kb1-content", 1024)));
        documentRepository.reindex(USER_1, secondKbId, "KB2 문서 v1", sourceA, List.of(embeddedChunk(sourceA, 0, "kb2-content-v1", 1024)));

        // secondKbId 범위에서 같은 source로 재색인 — KB_1의 동일 source 문서는 범위 조건에 걸리지 않아야 한다.
        documentRepository.reindex(USER_1, secondKbId, "KB2 문서 v2", sourceA, List.of(embeddedChunk(sourceA, 0, "kb2-content-v2", 1024)));

        Integer kb1Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document WHERE source = ? AND knowledge_base_id = ?",
                Integer.class, sourceA, KB_1);
        assertThat(kb1Count).isEqualTo(1);

        String kb1Content = jdbcTemplate.queryForObject(
                "SELECT c.content FROM tb_document_chunk c JOIN tb_document d ON c.document_id = d.id "
                        + "WHERE d.source = ? AND d.knowledge_base_id = ?",
                String.class, sourceA, KB_1);
        assertThat(kb1Content).isEqualTo("kb1-content");

        String kb2Content = jdbcTemplate.queryForObject(
                "SELECT c.content FROM tb_document_chunk c JOIN tb_document d ON c.document_id = d.id "
                        + "WHERE d.source = ? AND d.knowledge_base_id = ?",
                String.class, sourceA, secondKbId);
        assertThat(kb2Content).isEqualTo("kb2-content-v2");
    }
}
