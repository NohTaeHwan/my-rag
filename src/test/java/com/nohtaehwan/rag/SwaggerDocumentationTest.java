package com.nohtaehwan.rag;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OpenAPI JSON({@code /v3/api-docs})의 핵심 계약을 확인한다. Springdoc이 실제 Controller
 * annotation을 어떻게 렌더링하는지는 구현 세부사항이라, path·parameter·response·DTO 필드 존재 여부와
 * {@code /health} 미노출까지만 검증하고 문서 전체 구조를 스냅샷처럼 고정하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SwaggerDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiJson_기본_정보와_대상_API_path가_포함된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("my-rag API"))
                .andExpect(jsonPath("$.info.version").value("v1.0.0"))
                .andExpect(jsonPath("$.paths['/api/search']").exists())
                .andExpect(jsonPath("$.paths['/api/documents/index']").exists())
                .andExpect(jsonPath("$.paths['/api/answers']").exists())
                .andExpect(jsonPath("$.paths['/health']").doesNotExist());
    }

    @Test
    void openApiJson_검색_API의_parameter와_response가_포함된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/search'].get.parameters[0].name").value("query"))
                .andExpect(jsonPath("$.paths['/api/search'].get.parameters[0].required").value(true))
                .andExpect(jsonPath("$.paths['/api/search'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/search'].get.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/search'].get.responses['500']").exists());
    }

    @Test
    void openApiJson_색인_API의_response가_포함된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/documents/index'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/documents/index'].post.responses['500']").exists());
    }

    @Test
    void openApiJson_답변_API의_requestBody와_response가_포함된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/answers'].post.requestBody").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/answers'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/AnswerRequest"))
                .andExpect(jsonPath("$.paths['/api/answers'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/answers'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/answers'].post.responses['500']").exists())
                .andExpect(jsonPath("$.components.schemas.AnswerRequest.properties.question").exists())
                .andExpect(jsonPath("$.components.schemas.AnswerResponse.properties.answer").exists())
                .andExpect(jsonPath("$.components.schemas.AnswerResponse.properties.sources").exists())
                .andExpect(jsonPath("$.components.schemas.AnswerSource.properties.documentId").exists())
                .andExpect(jsonPath("$.components.schemas.AnswerSource.properties.title").exists())
                .andExpect(jsonPath("$.components.schemas.AnswerSource.properties.source").exists())
                .andExpect(jsonPath("$.components.schemas.AnswerSource.properties.chunkIndex").exists())
                .andExpect(jsonPath("$.components.schemas.AnswerSource.properties.distance").exists());
    }

    @Test
    void openApiJson_응답_DTO_필드가_schema에_포함된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.SearchResponse.properties.results").exists())
                .andExpect(jsonPath("$.components.schemas.SearchResult.properties.documentId").exists())
                .andExpect(jsonPath("$.components.schemas.SearchResult.properties.title").exists())
                .andExpect(jsonPath("$.components.schemas.SearchResult.properties.source").exists())
                .andExpect(jsonPath("$.components.schemas.SearchResult.properties.content").exists())
                .andExpect(jsonPath("$.components.schemas.SearchResult.properties.chunkIndex").exists())
                .andExpect(jsonPath("$.components.schemas.SearchResult.properties.distance").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentIndexResponse.properties.documentCount").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentIndexResponse.properties.chunkCount").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentIndexResponse.properties.embeddingDimension").exists());
    }
}
