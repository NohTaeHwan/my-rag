package com.nohtaehwan.rag.llm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * llm.* 설정 값을 바인딩한다.
 *
 * @param baseUrl         OpenAI-compatible LLM API base URL (예: http://host:8002/v1, 기본값은 실제 서버 연결을 보장하지 않는 더미 값)
 * @param model           사용할 모델 id
 * @param connectTimeout  연결 타임아웃
 * @param readTimeout     응답 대기 타임아웃
 * @param maxContextChars prompt에 포함할 검색 근거 context의 최대 문자 수(문자 수 근사치, tokenizer 아님)
 * @param maxTokens       LLM 응답의 최대 토큰 수
 * @param temperature     LLM 샘플링 temperature ([0, 2])
 * @param apiKey          선택적 API Key. 없으면 빈 문자열(Authorization 헤더 생략)
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String baseUrl,
        String model,
        Duration connectTimeout,
        Duration readTimeout,
        int maxContextChars,
        int maxTokens,
        double temperature,
        String apiKey
) {

    public LlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("llm.base-url must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("llm.model must not be blank");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalStateException("llm.connect-timeout must be positive: " + connectTimeout);
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalStateException("llm.read-timeout must be positive: " + readTimeout);
        }
        if (maxContextChars <= 0) {
            throw new IllegalStateException("llm.max-context-chars must be positive: " + maxContextChars);
        }
        if (maxTokens <= 0) {
            throw new IllegalStateException("llm.max-tokens must be positive: " + maxTokens);
        }
        if (temperature < 0 || temperature > 2) {
            throw new IllegalStateException("llm.temperature must be in [0, 2]: " + temperature);
        }
        if (apiKey == null) {
            apiKey = "";
        }
    }
}
