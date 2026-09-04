package com.nohtaehwan.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.nohtaehwan.rag.document.DocumentChunk;
import com.nohtaehwan.rag.exception.RagException;
import com.nohtaehwan.rag.indexing.mapper.DocumentInsertParameter;
import com.nohtaehwan.rag.indexing.mapper.DocumentMapper;

@ExtendWith(MockitoExtension.class)
class DocumentRepositoryTest {

    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentChunkRepository documentChunkRepository;

    private DocumentRepository repository;

    @BeforeEach
    void setUp() {
        repository = new DocumentRepository(documentMapper, documentChunkRepository);
    }

    private void stubGeneratedId(long id) {
        doAnswer(invocation -> {
            DocumentInsertParameter parameter = invocation.getArgument(0);
            parameter.setId(id);
            return null;
        }).when(documentMapper).insert(any(DocumentInsertParameter.class));
    }

    @Test
    void reindex는_Transactional이다() throws NoSuchMethodException {
        Method method = DocumentRepository.class.getMethod("reindex", String.class, String.class, List.class);
        assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void reindex_기존_문서_삭제후_새_문서와_Chunk를_저장한다() {
        stubGeneratedId(42L);

        DocumentChunk chunk = new DocumentChunk("a.md", "a.md#0", 0, List.of(), "content", 7);
        EmbeddedChunk embeddedChunk = new EmbeddedChunk(chunk, List.of(0.1, 0.2));

        repository.reindex("Title A", "a.md", List.of(embeddedChunk));

        InOrder inOrder = Mockito.inOrder(documentMapper, documentChunkRepository);
        inOrder.verify(documentMapper).deleteBySource("a.md");

        ArgumentCaptor<DocumentInsertParameter> captor = ArgumentCaptor.forClass(DocumentInsertParameter.class);
        inOrder.verify(documentMapper).insert(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Title A");
        assertThat(captor.getValue().getSource()).isEqualTo("a.md");

        inOrder.verify(documentChunkRepository).insertAll(eq(42L), eq(List.of(embeddedChunk)));
    }

    @Test
    void reindex_source가_null이거나_공백이면_RagException을_던지고_아무_mapper도_호출하지_않는다() {
        assertThatThrownBy(() -> repository.reindex("Title A", null, List.of()))
                .isInstanceOf(RagException.class);
        assertThatThrownBy(() -> repository.reindex("Title A", "  ", List.of()))
                .isInstanceOf(RagException.class);

        verifyNoInteractions(documentMapper, documentChunkRepository);
    }

    @Test
    void reindex_생성된_id가_없으면_NPE_대신_RagException을_던진다() {
        // documentMapper.insert()가 mock이라 parameter.id를 채우지 않으므로 getId()는 null을 반환한다.
        assertThatThrownBy(() -> repository.reindex("Title A", "a.md", List.of()))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("a.md");

        verify(documentMapper).deleteBySource("a.md");
        verify(documentMapper).insert(any(DocumentInsertParameter.class));
        verifyNoInteractions(documentChunkRepository);
    }

    @Test
    void reindex_Chunk_insert_실패시_예외를_삼키지_않고_그대로_전파한다() {
        stubGeneratedId(42L);

        DocumentChunk chunk = new DocumentChunk("a.md", "a.md#0", 0, List.of(), "content", 7);
        List<EmbeddedChunk> chunks = List.of(new EmbeddedChunk(chunk, List.of(0.1, 0.2)));

        DataIntegrityViolationException dbFailure = new DataIntegrityViolationException("constraint violation");
        doThrow(dbFailure).when(documentChunkRepository).insertAll(eq(42L), eq(chunks));

        assertThatThrownBy(() -> repository.reindex("Title A", "a.md", chunks))
                .isSameAs(dbFailure);
    }

    @Test
    void reindex_mapper_예외도_삼키지_않고_그대로_전파한다() {
        DataIntegrityViolationException dbFailure = new DataIntegrityViolationException("unique_violation");
        doThrow(dbFailure).when(documentMapper).deleteBySource("a.md");

        assertThatThrownBy(() -> repository.reindex("Title A", "a.md", List.of()))
                .isSameAs(dbFailure);

        verify(documentMapper, never()).insert(any());
        verifyNoInteractions(documentChunkRepository);
    }
}
