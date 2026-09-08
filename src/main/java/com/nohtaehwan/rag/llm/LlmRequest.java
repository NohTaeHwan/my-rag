package com.nohtaehwan.rag.llm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI-compatible {@code POST /chat/completions} 요청 본문.
 */
public record LlmRequest(
        String model,
        List<Message> messages,
        double temperature,
        @JsonProperty("max_tokens") int maxTokens
) {

    public record Message(String role, String content) {
    }
}
