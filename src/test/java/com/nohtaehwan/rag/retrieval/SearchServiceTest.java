package com.nohtaehwan.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataAccessException;

import com.nohtaehwan.rag.embedding.EmbeddingClient;
import com.nohtaehwan.rag.exception.InvalidRequestException;
import com.nohtaehwan.rag.exception.RagException;
import com.nohtaehwan.rag.retrieval.mapper.SearchMapper;
import com.nohtaehwan.rag.retrieval.mapper.SearchRow;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    private static final RetrievalProperties PROPERTIES = new RetrievalProperties(5);

    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private SearchMapper searchMapper;

    private SearchService service() {
        return new SearchService(embeddingClient, searchMapper, PROPERTIES);
    }

    @Test
    void search_정상_query는_embedding과_mapper를_호출하고_결과를_변환한다() {
        when(embeddingClient.embed("결제 취소 방법")).thenReturn(List.of(0.1, 0.2, 0.3));
        SearchRow row = new SearchRow(1L, "주문 관리 문서", "orders/cancel.md", "결제 완료 후 취소 절차", 3, 0.1245);
        when(searchMapper.search(eq("[0.1,0.2,0.3]"), eq(5))).thenReturn(List.of(row));

        SearchResponse response = service().search("결제 취소 방법");

        assertThat(response.results()).hasSize(1);
        SearchResult result = response.results().get(0);
        assertThat(result.documentId()).isEqualTo(1L);
        assertThat(result.title()).isEqualTo("주문 관리 문서");
        assertThat(result.source()).isEqualTo("orders/cancel.md");
        assertThat(result.content()).isEqualTo("결제 완료 후 취소 절차");
        assertThat(result.chunkIndex()).isEqualTo(3);
        assertThat(result.distance()).isEqualTo(0.1245);

        verify(embeddingClient).embed("결제 취소 방법");
        verify(searchMapper).search("[0.1,0.2,0.3]", 5);
    }

    @Test
    void search_결과가_없으면_빈_목록을_반환한다() {
        when(embeddingClient.embed("아무거나")).thenReturn(List.of(0.1));
        when(searchMapper.search(eq("[0.1]"), eq(5))).thenReturn(List.of());

        SearchResponse response = service().search("아무거나");

        assertThat(response.results()).isEmpty();
    }

    @Test
    void search_null_query는_InvalidRequestException을_던지고_아무것도_호출하지_않는다() {
        assertThatThrownBy(() -> service().search(null))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(embeddingClient, searchMapper);
    }

    @Test
    void search_공백_query는_InvalidRequestException을_던지고_아무것도_호출하지_않는다() {
        assertThatThrownBy(() -> service().search("   "))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(embeddingClient, searchMapper);
    }

    @Test
    void search_embedding_실패시_예외를_삼키지_않고_mapper를_호출하지_않는다() {
        when(embeddingClient.embed("질문")).thenThrow(new RagException("Embedding API 호출에 실패했습니다."));

        assertThatThrownBy(() -> service().search("질문"))
                .isInstanceOf(RagException.class);

        verify(searchMapper, never()).search(any(), anyInt());
    }

    @Test
    void search_mapper_예외를_삼키지_않고_그대로_전파한다() {
        when(embeddingClient.embed("질문")).thenReturn(List.of(0.1));
        DataAccessException dbFailure = new DataAccessResourceFailureException("connection refused");
        when(searchMapper.search(eq("[0.1]"), eq(5))).thenThrow(dbFailure);

        assertThatThrownBy(() -> service().search("질문"))
                .isSameAs(dbFailure);
    }

    @Test
    void search_결과_순서를_임의로_바꾸지_않는다() {
        when(embeddingClient.embed("질문")).thenReturn(List.of(0.1));
        SearchRow first = new SearchRow(1L, "A", "a.md", "content-a", 0, 0.1);
        SearchRow second = new SearchRow(2L, "B", "b.md", "content-b", 0, 0.2);
        when(searchMapper.search(eq("[0.1]"), eq(5))).thenReturn(List.of(first, second));

        SearchResponse response = service().search("질문");

        assertThat(response.results()).extracting(SearchResult::documentId).containsExactly(1L, 2L);
    }
}
