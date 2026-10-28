package com.nohtaehwan.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.nohtaehwan.rag.testing.IsolatedDatabaseGuard;
import com.nohtaehwan.rag.testing.IsolatedDatabaseGuard.Mode;

/**
 * V4 migration(`V4__enforce_document_knowledge_base_owner.sql`)이 tb_document의 user_id와
 * knowledge_base_id 소유자 불일치를 DB에서 거부하는지 검증한다.
 *
 * <p><b>경고 — {@link V3UserKnowledgeBaseScopeMigrationTest}와 마찬가지로 {@code flyway.clean()}으로
 * 스키마를 통째로 비운다.</b> {@code ./gradlew isolatedMigrationTest}로만 실행되며, 매 테스트 전
 * {@link IsolatedDatabaseGuard}가 격리 migration DB임을 확인하지 못하면 실패한다(skip 아님).
 */
@Tag("isolated-migration")
@SpringBootTest(properties = "spring.flyway.enabled=false")
class V4DocumentKnowledgeBaseOwnerMigrationTest {

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

    /** 개발용 사용자(id=1)와 별개인 사용자 2와 그 사용자 소유 KB(id=2)를 만든다. */
    private void insertSecondUserAndKnowledgeBase() {
        jdbcTemplate.update("INSERT INTO tb_user (id, display_name) VALUES (2, '다른 사용자')");
        jdbcTemplate.update(
                "INSERT INTO tb_knowledge_base (id, user_id, name, status) VALUES (2, 2, '사용자 2의 KB', 'ACTIVE')");
    }

    @Test
    void 문서의_user_id가_Knowledge_Base_소유자와_다르면_insert가_거부된다() {
        flywayTo("latest").clean();
        flywayTo("latest").migrate();
        insertSecondUserAndKnowledgeBase();

        // 사용자 1의 문서를 사용자 2 소유 KB(id=2)에 넣으려는 시도
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO tb_document (title, source, user_id, knowledge_base_id, index_status) "
                        + "VALUES (?, ?, 1, 2, 'INDEXED')", "불일치 문서", "mismatch.md"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 문서의_user_id가_Knowledge_Base_소유자와_같으면_insert가_성공한다() {
        flywayTo("latest").clean();
        flywayTo("latest").migrate();
        insertSecondUserAndKnowledgeBase();

        jdbcTemplate.update(
                "INSERT INTO tb_document (title, source, user_id, knowledge_base_id, index_status) "
                        + "VALUES (?, ?, 2, 2, 'INDEXED')", "일치 문서", "match.md");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document WHERE source = ?", Integer.class, "match.md");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void 문서가_참조_중인_Knowledge_Base의_소유자를_바꾸면_거부된다() {
        flywayTo("latest").clean();
        flywayTo("latest").migrate();
        insertSecondUserAndKnowledgeBase();
        jdbcTemplate.update(
                "INSERT INTO tb_document (title, source, user_id, knowledge_base_id, index_status) "
                        + "VALUES (?, ?, 2, 2, 'INDEXED')", "일치 문서", "match.md");

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE tb_knowledge_base SET user_id = 1 WHERE id = 2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V4_적용_전에_불일치_데이터가_있으면_migration이_실패하고_데이터는_그대로_남는다() {
        flywayTo("latest").clean();
        flywayTo("3").migrate();
        insertSecondUserAndKnowledgeBase();
        jdbcTemplate.update(
                "INSERT INTO tb_document (title, source, user_id, knowledge_base_id, index_status) "
                        + "VALUES (?, ?, 1, 2, 'INDEXED')", "기존 불일치 문서", "legacy-mismatch.md");

        assertThatThrownBy(() -> flywayTo("latest").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V4 중단");

        // 데이터를 고치거나 지우지 않았고, 제약도 추가되지 않았다(트랜잭션 롤백).
        Integer mismatchCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_document WHERE source = ? AND user_id = 1 AND knowledge_base_id = 2",
                Integer.class, "legacy-mismatch.md");
        assertThat(mismatchCount).isEqualTo(1);

        Integer constraintCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'fk_document_knowledge_base_owner'",
                Integer.class);
        assertThat(constraintCount).isZero();
    }
}
