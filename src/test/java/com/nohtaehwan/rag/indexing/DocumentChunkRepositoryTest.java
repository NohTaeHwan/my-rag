package com.nohtaehwan.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.nohtaehwan.rag.document.DocumentChunk;
import com.nohtaehwan.rag.indexing.mapper.ChunkRow;
import com.nohtaehwan.rag.indexing.mapper.DocumentChunkMapper;

@ExtendWith(MockitoExtension.class)
class DocumentChunkRepositoryTest {

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    private DocumentChunkRepository repository() {
        return new DocumentChunkRepository(documentChunkMapper);
    }

    @Test
    void insertAll_빈_목록이면_mapper를_호출하지_않는다() {
        repository().insertAll(1L, List.of());
        verifyNoInteractions(documentChunkMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void insertAll_document_id와_chunk_정보와_vector_리터럴을_전달한다() {
        DocumentChunk chunk0 = new DocumentChunk("a.md", "a.md#0", 0, List.of(), "content-0", 9);
        DocumentChunk chunk1 = new DocumentChunk("a.md", "a.md#1", 1, List.of(), "content-1", 9);
        EmbeddedChunk embedded0 = new EmbeddedChunk(chunk0, List.of(0.1, 0.2));
        EmbeddedChunk embedded1 = new EmbeddedChunk(chunk1, List.of(0.3, 0.4));

        repository().insertAll(7L, List.of(embedded0, embedded1));

        ArgumentCaptor<List<ChunkRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkMapper).insertAll(eq(7L), captor.capture());

        List<ChunkRow> rows = captor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getContent()).isEqualTo("content-0");
        assertThat(rows.get(0).getChunkIndex()).isEqualTo(0);
        assertThat(rows.get(0).getEmbeddingLiteral()).isEqualTo("[0.1,0.2]");
        assertThat(rows.get(1).getContent()).isEqualTo("content-1");
        assertThat(rows.get(1).getChunkIndex()).isEqualTo(1);
        assertThat(rows.get(1).getEmbeddingLiteral()).isEqualTo("[0.3,0.4]");
    }
}
