package com.nohtaehwan.rag.answer;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code POST /api/answers} 요청 본문. 사용자 ID는 받지 않는다(본문에 있어도 무시되고 서버가 현재 사용자로 결정).
 *
 * @param question        사용자 질문. null·빈 문자열·공백만 있으면 400.
 * @param knowledgeBaseId 근거를 검색할 Knowledge Base ID. 없거나 JSON 정수가 아니거나(1.5·"1"·true) 0 이하면 400.
 */
public record AnswerRequest(
        @Schema(description = "사용자 질문", example = "결제 완료 후 주문을 취소하려면?",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String question,

        @Schema(description = "근거를 검색할 Knowledge Base ID(현재 사용자 소유)", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1")
        @JsonDeserialize(using = AnswerRequest.IntegerOnlyLongDeserializer.class)
        @NotNull @Positive
        Long knowledgeBaseId) {

    /**
     * JSON 정수 토큰만 Long으로 받는다. Jackson 기본값(ACCEPT_FLOAT_AS_INT)은 1.5를 1로 잘라 다른 KB를
     * 조용히 가리킬 수 있으므로, 이 필드에 한해 실수·문자열·boolean을 거부한다(→ 400).
     */
    static class IntegerOnlyLongDeserializer extends JsonDeserializer<Long> {

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            return parser.getLongValue();
        }
    }
}
