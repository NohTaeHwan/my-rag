package com.nohtaehwan.rag.retrieval;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nohtaehwan.rag.exception.InvalidRequestException;
import com.nohtaehwan.rag.exception.RagException;

@WebMvcTest(SearchController.class)
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchService searchService;

    @Test
    void search_정상_query는_200과_결과를_반환한다() throws Exception {
        SearchResult result = new SearchResult(1L, "주문 관리 문서", "orders/cancel.md", "결제 완료 후 취소 절차", 3, 0.1245);
        when(searchService.search("결제 취소 방법")).thenReturn(new SearchResponse(List.of(result)));

        mockMvc.perform(get("/api/search").param("query", "결제 취소 방법"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].documentId").value(1))
                .andExpect(jsonPath("$.results[0].title").value("주문 관리 문서"))
                .andExpect(jsonPath("$.results[0].source").value("orders/cancel.md"))
                .andExpect(jsonPath("$.results[0].content").value("결제 완료 후 취소 절차"))
                .andExpect(jsonPath("$.results[0].chunkIndex").value(3))
                .andExpect(jsonPath("$.results[0].distance").value(0.1245));
    }

    @Test
    void search_결과가_없으면_200과_빈_배열을_반환한다() throws Exception {
        when(searchService.search("아무거나")).thenReturn(new SearchResponse(List.of()));

        mockMvc.perform(get("/api/search").param("query", "아무거나"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results").isEmpty());
    }

    @Test
    void search_query_파라미터가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/search"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_공백_query는_400과_메시지를_반환한다() throws Exception {
        when(searchService.search(" ")).thenThrow(new InvalidRequestException("query는 비어 있을 수 없습니다."));

        mockMvc.perform(get("/api/search").param("query", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("query는 비어 있을 수 없습니다."));
    }

    @Test
    void search_실패시_500과_sanitized_메시지를_반환한다() throws Exception {
        when(searchService.search("질문")).thenThrow(new RagException("Embedding API 호출에 실패했습니다."));

        mockMvc.perform(get("/api/search").param("query", "질문"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("검색에 실패했습니다."))
                .andExpect(content().string(not(containsString("Embedding API"))));
    }

    @Test
    void search_DB_오류시_500과_sanitized_메시지를_반환한다() throws Exception {
        when(searchService.search("질문")).thenThrow(new DataAccessResourceFailureException("connection refused"));

        mockMvc.perform(get("/api/search").param("query", "질문"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("검색에 실패했습니다."))
                .andExpect(content().string(not(containsString("connection refused"))));
    }
}
