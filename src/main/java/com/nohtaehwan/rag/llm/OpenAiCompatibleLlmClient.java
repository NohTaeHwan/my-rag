package com.nohtaehwan.rag.llm;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.nohtaehwan.rag.exception.RagException;

import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI-compatible {@code POST /chat/completions} API를 호출하는 {@link LlmClient} 구현체.
 */
@Slf4j
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final RestClient restClient;
    private final LlmProperties properties;

    public OpenAiCompatibleLlmClient(RestClient llmRestClient, LlmProperties properties) {
        this.restClient = llmRestClient;
        this.properties = properties;
    }

    @Override
    public String complete(Prompt prompt) {
        LlmRequest request = new LlmRequest(
                properties.model(),
                List.of(
                        new LlmRequest.Message("system", prompt.systemMessage()),
                        new LlmRequest.Message("user", prompt.userMessage())),
                properties.temperature(),
                properties.maxTokens());

        RestClient.RequestBodySpec spec = restClient.post().uri("/chat/completions");
        if (!properties.apiKey().isBlank()) {
            spec = spec.header("Authorization", "Bearer " + properties.apiKey());
        }

        LlmResponse response;
        try {
            response = spec.body(request).retrieve().body(LlmResponse.class);
        } catch (RestClientException e) {
            log.warn("LLM API 호출 실패: model={}", properties.model(), e);
            throw new RagException("LLM API 호출에 실패했습니다.", e);
        }

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            log.warn("LLM API 응답이 비어 있음: model={}", properties.model());
            throw new RagException("LLM API 응답이 비어 있습니다.");
        }

        LlmResponse.Choice choice = response.choices().get(0);
        if (choice == null || choice.message() == null) {
            log.warn("LLM API 응답에 choice 또는 message가 없음: model={}", properties.model());
            throw new RagException("LLM API 응답에 choice 또는 message가 없습니다.");
        }

        LlmResponse.Message message = choice.message();
        if (message.content() == null || message.content().isBlank()) {
            log.warn("LLM API 응답에 content가 없음: model={}", properties.model());
            throw new RagException("LLM API 응답에 answer 내용이 없습니다.");
        }

        log.debug("LLM 응답 생성 성공: model={}, responseLength={}", properties.model(), message.content().length());
        return message.content();
    }
}
