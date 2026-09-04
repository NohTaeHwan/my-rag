package com.nohtaehwan.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.nohtaehwan.rag.document.DocumentChunk;
import com.nohtaehwan.rag.exception.RagException;

@ExtendWith(MockitoExtension.class)
class DocumentRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DocumentChunkRepository documentChunkRepository;

    private DocumentRepository repository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        repository = new DocumentRepository(jdbcTemplate, documentChunkRepository);
    }

    @Test
    void reindex는_Transactional이다() throws NoSuchMethodException {
        Method method = DocumentRepository.class.getMethod("reindex", String.class, String.class, List.class);
        assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void reindex_기존_문서_삭제후_새_문서와_Chunk를_저장한다() throws Exception {
        lenient().when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(invocation -> {
                    KeyHolder keyHolder = invocation.getArgument(1);
                    keyHolder.getKeyList().add(Map.of("id", 42L));
                    return 1;
                });

        DocumentChunk chunk = new DocumentChunk("a.md", "a.md#0", 0, List.of(), "content", 7);
        EmbeddedChunk embeddedChunk = new EmbeddedChunk(chunk, List.of(0.1, 0.2));

        repository.reindex("Title A", "a.md", List.of(embeddedChunk));

        InOrder inOrder = Mockito.inOrder(jdbcTemplate, documentChunkRepository);
        inOrder.verify(jdbcTemplate).update(eq("DELETE FROM tb_document WHERE source = ?"), eq("a.md"));
        inOrder.verify(jdbcTemplate).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        inOrder.verify(documentChunkRepository).insertAll(eq(42L), eq(List.of(embeddedChunk)));

        ArgumentCaptor<PreparedStatementCreator> captor = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbcTemplate).update(captor.capture(), any(KeyHolder.class));

        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement("INSERT INTO tb_document (title, source) VALUES (?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)).thenReturn(ps);

        captor.getValue().createPreparedStatement(connection);

        verify(ps).setString(1, "Title A");
        verify(ps).setString(2, "a.md");
    }

    @Test
    void reindex_source가_null이거나_공백이면_RagException을_던지고_DB에_접근하지_않는다() {
        assertThatThrownBy(() -> repository.reindex("Title A", null, List.of()))
                .isInstanceOf(RagException.class);
        assertThatThrownBy(() -> repository.reindex("Title A", "  ", List.of()))
                .isInstanceOf(RagException.class);

        verifyNoInteractions(jdbcTemplate, documentChunkRepository);
    }

    @Test
    void reindex_생성된_id가_없으면_NPE_대신_RagException을_던진다() {
        // jdbcTemplate이 mock이라 KeyHolder를 채우지 않으므로 keyHolder.getKey()는 null을 반환한다.
        assertThatThrownBy(() -> repository.reindex("Title A", "a.md", List.of()))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("a.md");

        verify(jdbcTemplate).update(eq("DELETE FROM tb_document WHERE source = ?"), eq("a.md"));
        verifyNoInteractions(documentChunkRepository);
    }

    @Test
    void reindex_Chunk_insert_실패시_예외를_삼키지_않고_그대로_전파한다() {
        lenient().when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(invocation -> {
                    KeyHolder keyHolder = invocation.getArgument(1);
                    keyHolder.getKeyList().add(Map.of("id", 42L));
                    return 1;
                });

        DocumentChunk chunk = new DocumentChunk("a.md", "a.md#0", 0, List.of(), "content", 7);
        List<EmbeddedChunk> chunks = List.of(new EmbeddedChunk(chunk, List.of(0.1, 0.2)));

        DataIntegrityViolationException dbFailure = new DataIntegrityViolationException("constraint violation");
        Mockito.doThrow(dbFailure).when(documentChunkRepository).insertAll(eq(42L), eq(chunks));

        // Spring @Transactional의 rollback 자체(프레임워크 보장)는 여기서 검증하지 않는다. 이 테스트가
        // 확인하는 것은 애플리케이션 코드가 이 예외를 catch해서 삼키지 않는다는 것 — 예외가 그대로
        // 전파돼야 @Transactional 프록시가 rollback을 트리거할 수 있다.
        assertThatThrownBy(() -> repository.reindex("Title A", "a.md", chunks))
                .isSameAs(dbFailure);
    }
}
