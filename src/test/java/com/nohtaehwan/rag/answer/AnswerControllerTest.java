package com.nohtaehwan.rag.answer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nohtaehwan.rag.exception.InvalidRequestException;
import com.nohtaehwan.rag.exception.RagException;

@WebMvcTest(AnswerController.class)
class AnswerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnswerService answerService;

    @Test
    void answer_정상_질문은_200과_answer_sources를_반환한다() throws Exception {
        AnswerSource source = new AnswerSource(1L, "주문 관리 문서", "orders/cancel.md", 3, 0.1245);
        when(answerService.answer("결제 완료 후 주문을 취소하려면?"))
                .thenReturn(new AnswerResponse("답변입니다.", List.of(source)));

        mockMvc.perform(post("/api/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"결제 완료 후 주문을 취소하려면?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("답변입니다."))
                .andExpect(jsonPath("$.sources[0].documentId").value(1))
                .andExpect(jsonPath("$.sources[0].title").value("주문 관리 문서"))
                .andExpect(jsonPath("$.sources[0].source").value("orders/cancel.md"))
                .andExpect(jsonPath("$.sources[0].chunkIndex").value(3))
                .andExpect(jsonPath("$.sources[0].distance").value(0.1245));
    }

    @Test
    void answer_검색_결과가_없으면_200과_근거_부족_응답을_반환한다() throws Exception {
        when(answerService.answer("아무거나"))
                .thenReturn(new AnswerResponse("질문에 답할 수 있는 근거 문서를 찾지 못했습니다.", List.of()));

        mockMvc.perform(post("/api/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"아무거나\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("질문에 답할 수 있는 근거 문서를 찾지 못했습니다."))
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.sources").isEmpty());
    }

    @Test
    void answer_body가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/answers").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void answer_question이_blank이면_400과_메시지를_반환한다() throws Exception {
        mockMvc.perform(post("/api/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void answer_question이_null이면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void answer_AnswerService가_InvalidRequestException을_던지면_400과_메시지를_반환한다() throws Exception {
        when(answerService.answer("질문")).thenThrow(new InvalidRequestException("question은 비어 있을 수 없습니다."));

        mockMvc.perform(post("/api/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"질문\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("question은 비어 있을 수 없습니다."));
    }

    @Test
    void answer_SearchService_실패시_500과_sanitized_메시지를_반환한다() throws Exception {
        when(answerService.answer("질문")).thenThrow(new RagException("Embedding API 호출에 실패했습니다."));

        mockMvc.perform(post("/api/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"질문\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("답변 생성에 실패했습니다."))
                .andExpect(content().string(not(containsString("Embedding API"))));
    }

    @Test
    void answer_LlmClient_실패시_500과_sanitized_메시지를_반환한다() throws Exception {
        when(answerService.answer("질문"))
                .thenThrow(new RagException("LLM API 호출에 실패했습니다: http://secret-llm-host/v1"));

        mockMvc.perform(post("/api/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"질문\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("답변 생성에 실패했습니다."))
                .andExpect(content().string(not(containsString("secret-llm-host"))));
    }

    @Test
    void answer_DB_오류시_500과_sanitized_메시지를_반환한다() throws Exception {
        when(answerService.answer("질문")).thenThrow(new DataAccessResourceFailureException("connection refused"));

        mockMvc.perform(post("/api/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"질문\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("답변 생성에 실패했습니다."));
    }
}
