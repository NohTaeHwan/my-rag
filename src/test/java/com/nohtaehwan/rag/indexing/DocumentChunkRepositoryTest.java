package com.nohtaehwan.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.PreparedStatement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import com.nohtaehwan.rag.document.DocumentChunk;

@ExtendWith(MockitoExtension.class)
class DocumentChunkRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final DocumentChunkRepository repository() {
        return new DocumentChunkRepository(jdbcTemplate);
    }

    @Test
    void insertAll_빈_목록이면_아무것도_하지_않는다() {
        repository().insertAll(1L, List.of());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void insertAll_document_id와_chunk_정보와_vector_리터럴을_바인딩한다() throws Exception {
        DocumentChunk chunk0 = new DocumentChunk("a.md", "a.md#0", 0, List.of(), "content-0", 9);
        DocumentChunk chunk1 = new DocumentChunk("a.md", "a.md#1", 1, List.of(), "content-1", 9);
        EmbeddedChunk embedded0 = new EmbeddedChunk(chunk0, List.of(0.1, 0.2));
        EmbeddedChunk embedded1 = new EmbeddedChunk(chunk1, List.of(0.3, 0.4));

        repository().insertAll(7L, List.of(embedded0, embedded1));

        ArgumentCaptor<BatchPreparedStatementSetter> captor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(
                org.mockito.ArgumentMatchers.eq(
                        "INSERT INTO tb_document_chunk (document_id, content, chunk_index, embedding) VALUES (?, ?, ?, ?::vector)"),
                captor.capture());

        BatchPreparedStatementSetter setter = captor.getValue();
        assertThat(setter.getBatchSize()).isEqualTo(2);

        PreparedStatement ps0 = mock(PreparedStatement.class);
        setter.setValues(ps0, 0);
        verify(ps0).setLong(1, 7L);
        verify(ps0).setString(2, "content-0");
        verify(ps0).setInt(3, 0);
        verify(ps0).setString(4, "[0.1,0.2]");

        PreparedStatement ps1 = mock(PreparedStatement.class);
        setter.setValues(ps1, 1);
        verify(ps1).setLong(1, 7L);
        verify(ps1).setString(2, "content-1");
        verify(ps1).setInt(3, 1);
        verify(ps1).setString(4, "[0.3,0.4]");
    }
}
