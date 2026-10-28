package com.nohtaehwan.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.nohtaehwan.rag.testing.IsolatedDatabaseGuard;
import com.nohtaehwan.rag.testing.IsolatedDatabaseGuard.Mode;

/**
 * V3 migration(`V3__add_user_knowledge_base_scope.sql`)이 두 경로 모두에서 안전한지
 * 검증한다: (1) 빈 DB에 V1~V3를 순서대로 적용, (2) 기존 V2 데이터가 있는 DB에 V3만 추가 적용.
 *
 * <p><b>경고 — 이 테스트는 {@code flyway.clean()}으로 스키마를 통째로 비운다.</b> 격리된
 * 전용 테스트 DB에서만 실행해야 하며, 로컬/Spark 등 실사용 DB를 가리키는 DB_URL로는 절대
 * 실행하지 않는다. {@code isolated-migration} tag라 기본 {@code test} task에서 제외되고
 * {@code ./gradlew isolatedMigrationTest}로만 실행된다. 매 테스트 전 {@link IsolatedDatabaseGuard}가
 * opt-in과 실제 JDBC host·DB 이름을 확인하며, 어긋나면 clean 전에 실패한다(skip 아님).
 * spring.flyway.enabled=false로 Spring Boot의 자동 migrate를 막고, 이 테스트가 직접 Flyway 버전을 제어한다.
 *
 * <p>migration 실행 건수를 검증하는 두 테스트는 V4 이후 migration이 추가돼도 V3 자체만 검증하도록
 * target을 {@code "3"}으로 고정한다.
 */
@Tag("isolated-migration")
@SpringBootTest(properties = "spring.flyway.enabled=false")
class V3UserKnowledgeBaseScopeMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** flyway.clean() 전에 격리 migration DB인지 확인한다. 아니면 실패한다. */
    @BeforeEach
    void requireIsolatedDatabase() {
        IsolatedDatabaseGuard.verify(dataSource, Mode.MIGRATION);
    }

    private Flyway flywayTo(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    @Test
    void 빈_DB에_V1_V2_V3를_순서대로_적용하면_성공하고_기본_사용자와_KB가_시드된다() {
        flywayTo("latest").clean();
        MigrateResult result = flywayTo("3").migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(3);

        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM tb_user WHERE id = 1", Long.class);
        assertThat(userId).isEqualTo(1L);

        Long kbId = jdbcTemplate.queryForObject(
                "SELECT id FROM tb_knowledge_base WHERE id = 1 AND user_id = 1", Long.class);
        assertThat(kbId).isEqualTo(1L);
    }

    @Test
    void 기존_V2_데이터가_있는_DB에_V3를_적용하면_기존_문서와_Chunk가_보존되고_기본_범위로_귀속된다() {
        flywayTo("latest").clean();
        flywayTo("2").migrate();

        jdbcTemplate.update(
                "INSERT INTO tb_document (title, source) VALUES (?, ?)",
                "레거시 문서", "legacy/doc.md");
        Long legacyDocId = jdbcTemplate.queryForObject(
                "SELECT id FROM tb_document WHERE source = ?", Long.class, "legacy/doc.md");
        jdbcTemplate.update(
                "INSERT INTO tb_document_chunk (document_id, content, chunk_index, embedding) "
                        + "VALUES (?, ?, 0, ?::vector)",
                legacyDocId, "레거시 Chunk 내용", vectorLiteral(1024));

        MigrateResult result = flywayTo("3").migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT user_id, knowledge_base_id, index_status FROM tb_document WHERE id = ?",
                legacyDocId);
        assertThat(row.get("user_id")).isEqualTo(1L);
        assertThat(row.get("knowledge_base_id")).isEqualTo(1L);
        assertThat(row.get("index_status")).isEqualTo("INDEXED");

        Integer chunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document_chunk WHERE document_id = ?",
                Integer.class, legacyDocId);
        assertThat(chunkCount).isEqualTo(1);
    }

    @Test
    void 같은_사용자의_다른_Knowledge_Base에는_동일_source가_공존할_수_있다() {
        flywayTo("latest").clean();
        flywayTo("latest").migrate();

        jdbcTemplate.update(
                "INSERT INTO tb_knowledge_base (id, user_id, name, status) VALUES (2, 1, '다른 KB', 'ACTIVE')");

        jdbcTemplate.update(
                "INSERT INTO tb_document (title, source, user_id, knowledge_base_id, index_status) "
                        + "VALUES (?, ?, 1, 1, 'INDEXED')", "문서 A", "same-name.md");
        jdbcTemplate.update(
                "INSERT INTO tb_document (title, source, user_id, knowledge_base_id, index_status) "
                        + "VALUES (?, ?, 1, 2, 'INDEXED')", "문서 B", "same-name.md");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document WHERE source = ?", Integer.class, "same-name.md");
        assertThat(count).isEqualTo(2);
    }

    @Test
    void 같은_사용자_같은_KB_안에서는_동일_source가_유일해야_한다() {
        flywayTo("latest").clean();
        flywayTo("latest").migrate();

        jdbcTemplate.update(
                "INSERT INTO tb_document (title, source, user_id, knowledge_base_id, index_status) "
                        + "VALUES (?, ?, 1, 1, 'INDEXED')", "문서 A", "dup.md");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO tb_document (title, source, user_id, knowledge_base_id, index_status) "
                        + "VALUES (?, ?, 1, 1, 'INDEXED')", "문서 A2", "dup.md"))
                .isInstanceOf(DataAccessException.class);
    }

    private static String vectorLiteral(int dimension) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dimension; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("0.1");
        }
        return sb.append(']').toString();
    }
}
