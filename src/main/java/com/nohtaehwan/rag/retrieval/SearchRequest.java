package com.nohtaehwan.rag.retrieval;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code GET /api/search} 요청 파라미터. 사용자 ID는 받지 않는다(서버가 현재 사용자로 결정).
 *
 * @param query           사용자 질문. null·빈 문자열·공백만 있으면 400.
 * @param knowledgeBaseId 검색할 Knowledge Base ID. 없거나 양의 정수가 아니면 400.
 */
public record SearchRequest(
        @Schema(description = "검색 질문", example = "결제 취소 방법", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String query,

        @Schema(description = "검색할 Knowledge Base ID(현재 사용자 소유)", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1")
        @NotNull @Positive
        Long knowledgeBaseId) {
}
