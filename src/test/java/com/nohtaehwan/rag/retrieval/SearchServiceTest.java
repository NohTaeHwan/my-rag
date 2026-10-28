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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.nohtaehwan.rag.embedding.EmbeddingClient;
import com.nohtaehwan.rag.exception.InvalidRequestException;
import com.nohtaehwan.rag.exception.RagException;
import com.nohtaehwan.rag.knowledgebase.mapper.KnowledgeBaseMapper;
import com.nohtaehwan.rag.retrieval.mapper.SearchMapper;
import com.nohtaehwan.rag.retrieval.mapper.SearchRow;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    private static final RetrievalProperties PROPERTIES = new RetrievalProperties(5);

    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private SearchMapper searchMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    private static final Long USER_ID = 1L;
    private static final Long KB_ID = 7L;

    private SearchService service() {
        return new SearchService(embeddingClient, searchMapper, knowledgeBaseMapper, PROPERTIES);
    }

    private void kbOwned() {
        when(knowledgeBaseMapper.existsByIdAndUserId(KB_ID, USER_ID)).thenReturn(true);
    }

    @Test
    void search_정상_query는_embedding과_mapper를_호출하고_결과를_변환한다() {
        kbOwned();
        when(embeddingClient.embed("결제 취소 방법")).thenReturn(List.of(0.1, 0.2, 0.3));
        SearchRow row = new SearchRow(1L, "주문 관리 문서", "orders/cancel.md", "결제 완료 후 취소 절차", 3, 0.1245);
        when(searchMapper.search(eq("[0.1,0.2,0.3]"), eq(USER_ID), eq(KB_ID), eq(5))).thenReturn(List.of(row));

        SearchResponse response = service().search(USER_ID, KB_ID, "결제 취소 방법");

        assertThat(response.results()).hasSize(1);
        SearchResult result = response.results().get(0);
        assertThat(result.documentId()).isEqualTo(1L);
        assertThat(result.title()).isEqualTo("주문 관리 문서");
        assertThat(result.source()).isEqualTo("orders/cancel.md");
        assertThat(result.content()).isEqualTo("결제 완료 후 취소 절차");
        assertThat(result.chunkIndex()).isEqualTo(3);
        assertThat(result.distance()).isEqualTo(0.1245);

        verify(embeddingClient).embed("결제 취소 방법");
        verify(searchMapper).search("[0.1,0.2,0.3]", USER_ID, KB_ID, 5);
    }

    @Test
    void search_결과가_없으면_빈_목록을_반환한다() {
        kbOwned();
        when(embeddingClient.embed("아무거나")).thenReturn(List.of(0.1));
        when(searchMapper.search(eq("[0.1]"), eq(USER_ID), eq(KB_ID), eq(5))).thenReturn(List.of());

        SearchResponse response = service().search(USER_ID, KB_ID, "아무거나");

        assertThat(response.results()).isEmpty();
    }

    @Test
    void search_null_query는_InvalidRequestException을_던지고_아무것도_호출하지_않는다() {
        assertThatThrownBy(() -> service().search(USER_ID, KB_ID, null))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(embeddingClient, searchMapper, knowledgeBaseMapper);
    }

    @Test
    void search_공백_query는_InvalidRequestException을_던지고_아무것도_호출하지_않는다() {
        assertThatThrownBy(() -> service().search(USER_ID, KB_ID, "   "))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(embeddingClient, searchMapper, knowledgeBaseMapper);
    }

    @Test
    void search_embedding_실패시_예외를_삼키지_않고_mapper를_호출하지_않는다() {
        kbOwned();
        when(embeddingClient.embed("질문")).thenThrow(new RagException("Embedding API 호출에 실패했습니다."));

        assertThatThrownBy(() -> service().search(USER_ID, KB_ID, "질문"))
                .isInstanceOf(RagException.class);

        verify(searchMapper, never()).search(any(), any(), any(), anyInt());
    }

    @Test
    void search_mapper_예외를_삼키지_않고_그대로_전파한다() {
        kbOwned();
        when(embeddingClient.embed("질문")).thenReturn(List.of(0.1));
        DataAccessException dbFailure = new DataAccessResourceFailureException("connection refused");
        when(searchMapper.search(eq("[0.1]"), eq(USER_ID), eq(KB_ID), eq(5))).thenThrow(dbFailure);

        assertThatThrownBy(() -> service().search(USER_ID, KB_ID, "질문"))
                .isSameAs(dbFailure);
    }

    @Test
    void search_결과_순서를_임의로_바꾸지_않는다() {
        kbOwned();
        when(embeddingClient.embed("질문")).thenReturn(List.of(0.1));
        SearchRow first = new SearchRow(1L, "A", "a.md", "content-a", 0, 0.1);
        SearchRow second = new SearchRow(2L, "B", "b.md", "content-b", 0, 0.2);
        when(searchMapper.search(eq("[0.1]"), eq(USER_ID), eq(KB_ID), eq(5))).thenReturn(List.of(first, second));

        SearchResponse response = service().search(USER_ID, KB_ID, "질문");

        assertThat(response.results()).extracting(SearchResult::documentId).containsExactly(1L, 2L);
    }

    @Test
    void search_KB가_없거나_다른_사용자_소유면_404를_던지고_embedding과_검색을_호출하지_않는다() {
        when(knowledgeBaseMapper.existsByIdAndUserId(KB_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service().search(USER_ID, KB_ID, "질문"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(embeddingClient, searchMapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void search_knowledgeBaseId가_양의_정수가_아니면_InvalidRequestException을_던지고_아무것도_호출하지_않는다(
            Long knowledgeBaseId) {
        assertThatThrownBy(() -> service().search(USER_ID, knowledgeBaseId, "질문"))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(embeddingClient, searchMapper, knowledgeBaseMapper);
    }

    @Test
    void search_userId가_없으면_아무것도_호출하지_않는다() {
        assertThatThrownBy(() -> service().search(null, KB_ID, "질문"))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(embeddingClient, searchMapper, knowledgeBaseMapper);
    }
}
