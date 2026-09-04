package com.nohtaehwan.rag.indexing;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nohtaehwan.rag.exception.RagException;

@WebMvcTest(DocumentIndexController.class)
class DocumentIndexControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentIndexService documentIndexService;

    @Test
    void index_성공시_색인_결과를_반환한다() throws Exception {
        when(documentIndexService.indexDocuments())
                .thenReturn(new DocumentIndexResponse(2, 10, 1024));

        mockMvc.perform(post("/api/documents/index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(2))
                .andExpect(jsonPath("$.chunkCount").value(10))
                .andExpect(jsonPath("$.embeddingDimension").value(1024));
    }

    @Test
    void index_대상_파일이_없으면_0건_성공을_반환한다() throws Exception {
        when(documentIndexService.indexDocuments())
                .thenReturn(new DocumentIndexResponse(0, 0, 1024));

        mockMvc.perform(post("/api/documents/index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(0))
                .andExpect(jsonPath("$.chunkCount").value(0));
    }

    @Test
    void index_실패시_500과_메시지만_반환한다() throws Exception {
        when(documentIndexService.indexDocuments())
                .thenThrow(new RagException("Embedding API 호출에 실패했습니다."));

        mockMvc.perform(post("/api/documents/index"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("문서 색인에 실패했습니다."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Embedding API"))));
    }

    @Test
    void index_DB_오류시_500과_sanitized_메시지만_반환한다() throws Exception {
        when(documentIndexService.indexDocuments())
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_tb_document_source\""));

        mockMvc.perform(post("/api/documents/index"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("문서 색인에 실패했습니다."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("constraint"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("jdbc:"))));
    }
}
