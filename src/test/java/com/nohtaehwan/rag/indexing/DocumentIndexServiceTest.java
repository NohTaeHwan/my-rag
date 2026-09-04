package com.nohtaehwan.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.nohtaehwan.rag.document.DocumentChunk;
import com.nohtaehwan.rag.document.DocumentContent;
import com.nohtaehwan.rag.document.DocumentSourceReader;
import com.nohtaehwan.rag.document.MarkdownChunker;
import com.nohtaehwan.rag.document.MarkdownParser;
import com.nohtaehwan.rag.document.SourceFile;
import com.nohtaehwan.rag.embedding.EmbeddingClient;
import com.nohtaehwan.rag.embedding.EmbeddingProperties;
import com.nohtaehwan.rag.exception.RagException;

@ExtendWith(MockitoExtension.class)
class DocumentIndexServiceTest {

    private static final EmbeddingProperties PROPERTIES =
            new EmbeddingProperties("http://localhost:8000/v1", "BAAI/bge-m3", 4, Duration.ofSeconds(1), Duration.ofSeconds(1));

    @Mock
    private DocumentSourceReader documentSourceReader;
    @Mock
    private MarkdownParser markdownParser;
    @Mock
    private MarkdownChunker markdownChunker;
    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private DocumentRepository documentRepository;

    private DocumentIndexService service() {
        return new DocumentIndexService(
                documentSourceReader, markdownParser, markdownChunker, embeddingClient, PROPERTIES, documentRepository);
    }

    private static DocumentChunk chunk(String sourceKey, int index, String content) {
        return new DocumentChunk(sourceKey, sourceKey + "#" + index, index, List.of(), content, content.length());
    }

    @Test
    void indexDocuments_다중_파일을_순서대로_파싱_청킹_임베딩_저장한다() {
        SourceFile fileA = new SourceFile("a.md", "raw-a");
        SourceFile fileB = new SourceFile("b.md", "raw-b");
        when(documentSourceReader.readAll()).thenReturn(List.of(fileA, fileB));

        DocumentContent contentA = new DocumentContent("a.md", "Title A", List.of());
        DocumentContent contentB = new DocumentContent("b.md", "Title B", List.of());
        when(markdownParser.parse("a.md", "raw-a")).thenReturn(contentA);
        when(markdownParser.parse("b.md", "raw-b")).thenReturn(contentB);

        DocumentChunk a0 = chunk("a.md", 0, "content-a0");
        DocumentChunk a1 = chunk("a.md", 1, "content-a1");
        DocumentChunk b0 = chunk("b.md", 0, "content-b0");
        when(markdownChunker.chunk(contentA)).thenReturn(List.of(a0, a1));
        when(markdownChunker.chunk(contentB)).thenReturn(List.of(b0));

        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2, 0.3, 0.4));

        DocumentIndexResponse response = service().indexDocuments();

        assertThat(response.documentCount()).isEqualTo(2);
        assertThat(response.chunkCount()).isEqualTo(3);
        assertThat(response.embeddingDimension()).isEqualTo(4);

        verify(embeddingClient).embed("content-a0");
        verify(embeddingClient).embed("content-a1");
        verify(embeddingClient).embed("content-b0");
        verify(embeddingClient, times(3)).embed(anyString());

        InOrder inOrder = Mockito.inOrder(documentRepository);
        inOrder.verify(documentRepository).reindex(eq("Title A"), eq("a.md"), any());
        inOrder.verify(documentRepository).reindex(eq("Title B"), eq("b.md"), any());
    }

    @Test
    void indexDocuments_대상_파일이_없으면_0건_성공으로_처리한다() {
        when(documentSourceReader.readAll()).thenReturn(List.of());

        DocumentIndexResponse response = service().indexDocuments();

        assertThat(response.documentCount()).isZero();
        assertThat(response.chunkCount()).isZero();
        assertThat(response.embeddingDimension()).isEqualTo(4);
        verifyNoInteractions(markdownParser, markdownChunker, embeddingClient, documentRepository);
    }

    @Test
    void indexDocuments_embedding_실패시_즉시_전파하고_저장을_호출하지_않는다() {
        SourceFile file = new SourceFile("a.md", "raw-a");
        when(documentSourceReader.readAll()).thenReturn(List.of(file));

        DocumentContent content = new DocumentContent("a.md", "Title A", List.of());
        when(markdownParser.parse("a.md", "raw-a")).thenReturn(content);

        DocumentChunk chunk = chunk("a.md", 0, "content-a0");
        when(markdownChunker.chunk(content)).thenReturn(List.of(chunk));

        when(embeddingClient.embed("content-a0")).thenThrow(new RagException("Embedding API 호출에 실패했습니다."));

        assertThatThrownBy(() -> service().indexDocuments())
                .isInstanceOf(RagException.class);

        verify(documentRepository, never()).reindex(anyString(), anyString(), any());
    }

    @Test
    void indexDocuments_두번째_문서_embedding_실패시_첫번째_문서는_저장되고_두번째는_저장되지_않는다() {
        SourceFile fileA = new SourceFile("a.md", "raw-a");
        SourceFile fileB = new SourceFile("b.md", "raw-b");
        when(documentSourceReader.readAll()).thenReturn(List.of(fileA, fileB));

        DocumentContent contentA = new DocumentContent("a.md", "Title A", List.of());
        DocumentContent contentB = new DocumentContent("b.md", "Title B", List.of());
        when(markdownParser.parse("a.md", "raw-a")).thenReturn(contentA);
        when(markdownParser.parse("b.md", "raw-b")).thenReturn(contentB);

        DocumentChunk a0 = chunk("a.md", 0, "content-a0");
        DocumentChunk b0 = chunk("b.md", 0, "content-b0");
        when(markdownChunker.chunk(contentA)).thenReturn(List.of(a0));
        when(markdownChunker.chunk(contentB)).thenReturn(List.of(b0));

        when(embeddingClient.embed("content-a0")).thenReturn(List.of(0.1, 0.2, 0.3, 0.4));
        when(embeddingClient.embed("content-b0"))
                .thenThrow(new RagException("Embedding API 호출에 실패했습니다."));

        assertThatThrownBy(() -> service().indexDocuments())
                .isInstanceOf(RagException.class);

        // 첫 번째 문서는 이미 reindex()가 호출되어 저장됐고(문서 단위 commit), 두 번째는 호출되지 않는다.
        verify(documentRepository, times(1)).reindex(eq("Title A"), eq("a.md"), any());
        verify(documentRepository, never()).reindex(eq("Title B"), eq("b.md"), any());
    }

    @Test
    void indexDocuments_빈_문서는_Chunk_0건으로_저장하고_documentCount에는_포함한다() {
        SourceFile file = new SourceFile("empty.md", "");
        when(documentSourceReader.readAll()).thenReturn(List.of(file));

        DocumentContent content = new DocumentContent("empty.md", "empty", List.of());
        when(markdownParser.parse("empty.md", "")).thenReturn(content);
        when(markdownChunker.chunk(content)).thenReturn(List.of());

        DocumentIndexResponse response = service().indexDocuments();

        assertThat(response.documentCount()).isEqualTo(1);
        assertThat(response.chunkCount()).isZero();
        verifyNoInteractions(embeddingClient);
        verify(documentRepository).reindex(eq("empty"), eq("empty.md"), eq(List.of()));
    }
}
