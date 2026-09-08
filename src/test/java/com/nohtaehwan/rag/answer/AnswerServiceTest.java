package com.nohtaehwan.rag.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.nohtaehwan.rag.exception.InvalidRequestException;
import com.nohtaehwan.rag.exception.RagException;
import com.nohtaehwan.rag.llm.LlmClient;
import com.nohtaehwan.rag.llm.LlmProperties;
import com.nohtaehwan.rag.llm.Prompt;
import com.nohtaehwan.rag.retrieval.SearchResponse;
import com.nohtaehwan.rag.retrieval.SearchResult;
import com.nohtaehwan.rag.retrieval.SearchService;

@ExtendWith(MockitoExtension.class)
class AnswerServiceTest {

    private static final LlmProperties PROPERTIES =
            new LlmProperties("http://x", "m", Duration.ofSeconds(1), Duration.ofSeconds(1), 12000, 512, 0.0, "");

    @Mock
    private SearchService searchService;
    @Mock
    private ContextBuilder contextBuilder;
    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private LlmClient llmClient;

    private AnswerService service() {
        return new AnswerService(searchService, contextBuilder, promptBuilder, llmClient, PROPERTIES);
    }

    private static SearchResult result(long id, String title, String source, String content, int index, double distance) {
        return new SearchResult(id, title, source, content, index, distance);
    }

    @Test
    void answer_정상_질문이_SearchService에_전달된다() {
        SearchResult result = result(1L, "T", "s.md", "c", 0, 0.1);
        when(searchService.search("결제 취소")).thenReturn(new SearchResponse(List.of(result)));
        when(contextBuilder.build(List.of(result), 12000)).thenReturn(new Context("ctx", List.of(result)));
        Prompt prompt = new Prompt("sys", "user");
        when(promptBuilder.build(eq("결제 취소"), eq("ctx"))).thenReturn(prompt);
        when(llmClient.complete(prompt)).thenReturn("답변");

        service().answer("결제 취소");

        verify(searchService).search("결제 취소");
    }

    @Test
    void answer_검색_결과가_있으면_context와_prompt가_생성되고_LlmClient에_전달된다() {
        SearchResult result = result(1L, "T", "s.md", "c", 0, 0.1);
        when(searchService.search("질문")).thenReturn(new SearchResponse(List.of(result)));
        when(contextBuilder.build(List.of(result), 12000)).thenReturn(new Context("ctx", List.of(result)));
        Prompt prompt = new Prompt("sys", "user");
        when(promptBuilder.build(eq("질문"), eq("ctx"))).thenReturn(prompt);
        when(llmClient.complete(prompt)).thenReturn("답변");

        AnswerResponse response = service().answer("질문");

        verify(contextBuilder).build(List.of(result), 12000);
        verify(promptBuilder).build("질문", "ctx");
        verify(llmClient).complete(prompt);
        assertThat(response.answer()).isEqualTo("답변");
    }

    @Test
    void answer_LLM_답변이_그대로_반환된다() {
        SearchResult result = result(1L, "T", "s.md", "c", 0, 0.1);
        when(searchService.search("질문")).thenReturn(new SearchResponse(List.of(result)));
        when(contextBuilder.build(any(), eq(12000))).thenReturn(new Context("ctx", List.of(result)));
        Prompt prompt = new Prompt("sys", "user");
        when(promptBuilder.build(any(), any())).thenReturn(prompt);
        when(llmClient.complete(prompt)).thenReturn("최종답변");

        AnswerResponse response = service().answer("질문");

        assertThat(response.answer()).isEqualTo("최종답변");
    }

    @Test
    void answer_context에_실제_포함된_결과만_sources로_반환한다() {
        SearchResult a = result(1L, "A", "a.md", "ca", 0, 0.1);
        SearchResult b = result(2L, "B", "b.md", "cb", 1, 0.2);
        when(searchService.search("질문")).thenReturn(new SearchResponse(List.of(a, b)));
        // ContextBuilder가 길이 제한으로 a만 포함시켰다고 가정
        when(contextBuilder.build(List.of(a, b), 12000)).thenReturn(new Context("ctx", List.of(a)));
        Prompt prompt = new Prompt("sys", "user");
        when(promptBuilder.build(any(), any())).thenReturn(prompt);
        when(llmClient.complete(prompt)).thenReturn("답변");

        AnswerResponse response = service().answer("질문");

        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).documentId()).isEqualTo(1L);
    }

    @Test
    void answer_검색_결과가_없으면_고정_근거_부족_응답을_반환하고_LlmClient를_호출하지_않는다() {
        when(searchService.search("질문")).thenReturn(new SearchResponse(List.of()));

        AnswerResponse response = service().answer("질문");

        assertThat(response.answer()).isEqualTo("질문에 답할 수 있는 근거 문서를 찾지 못했습니다.");
        assertThat(response.sources()).isEmpty();
        verifyNoInteractions(contextBuilder, promptBuilder, llmClient);
    }

    @Test
    void answer_context가_비면_고정_근거_부족_응답을_반환하고_LlmClient를_호출하지_않는다() {
        SearchResult result = result(1L, "T", "s.md", "c", 0, 0.1);
        when(searchService.search("질문")).thenReturn(new SearchResponse(List.of(result)));
        when(contextBuilder.build(List.of(result), 12000)).thenReturn(new Context("", List.of()));

        AnswerResponse response = service().answer("질문");

        assertThat(response.answer()).isEqualTo("질문에 답할 수 있는 근거 문서를 찾지 못했습니다.");
        assertThat(response.sources()).isEmpty();
        verifyNoInteractions(promptBuilder, llmClient);
    }

    @Test
    void answer_question이_null이거나_공백이면_InvalidRequestException을_던지고_아무것도_호출하지_않는다() {
        assertThatThrownBy(() -> service().answer(null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service().answer("   ")).isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(searchService, contextBuilder, promptBuilder, llmClient);
    }

    @Test
    void answer_SearchService_실패시_예외를_삼키지_않고_빈_결과로_바꾸지_않는다() {
        when(searchService.search("질문")).thenThrow(new RagException("검색 실패"));

        assertThatThrownBy(() -> service().answer("질문")).isInstanceOf(RagException.class);

        verifyNoInteractions(contextBuilder, promptBuilder, llmClient);
    }

    @Test
    void answer_LlmClient_실패시_예외를_삼키지_않는다() {
        SearchResult result = result(1L, "T", "s.md", "c", 0, 0.1);
        when(searchService.search("질문")).thenReturn(new SearchResponse(List.of(result)));
        when(contextBuilder.build(List.of(result), 12000)).thenReturn(new Context("ctx", List.of(result)));
        Prompt prompt = new Prompt("sys", "user");
        when(promptBuilder.build(any(), any())).thenReturn(prompt);
        when(llmClient.complete(prompt)).thenThrow(new RagException("LLM 실패"));

        assertThatThrownBy(() -> service().answer("질문")).isInstanceOf(RagException.class);
    }
}
