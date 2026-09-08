package com.nohtaehwan.rag.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class LlmPropertiesTest {

    private static LlmProperties valid(String baseUrl, String model, Duration connectTimeout, Duration readTimeout,
            int maxContextChars, int maxTokens, double temperature, String apiKey) {
        return new LlmProperties(baseUrl, model, connectTimeout, readTimeout, maxContextChars, maxTokens, temperature, apiKey);
    }

    @Test
    void 정상_값이면_생성된다() {
        LlmProperties properties = valid(
                "http://localhost:8002/v1", "local-model", Duration.ofSeconds(2), Duration.ofSeconds(60),
                12000, 512, 0.0, "secret");

        assertThat(properties.baseUrl()).isEqualTo("http://localhost:8002/v1");
        assertThat(properties.apiKey()).isEqualTo("secret");
    }

    @Test
    void apiKey가_null이면_빈_문자열로_채워진다() {
        LlmProperties properties = valid(
                "http://localhost:8002/v1", "local-model", Duration.ofSeconds(2), Duration.ofSeconds(60),
                12000, 512, 0.0, null);

        assertThat(properties.apiKey()).isEmpty();
    }

    @Test
    void baseUrl이_비어있으면_예외() {
        assertThatThrownBy(() -> valid(" ", "m", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 0, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void model이_비어있으면_예외() {
        assertThatThrownBy(() -> valid("http://x", " ", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 0, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void connectTimeout이_0이하이면_예외() {
        assertThatThrownBy(() -> valid("http://x", "m", Duration.ZERO, Duration.ofSeconds(1), 1, 1, 0, ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> valid("http://x", "m", Duration.ofSeconds(-1), Duration.ofSeconds(1), 1, 1, 0, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readTimeout이_0이하이면_예외() {
        assertThatThrownBy(() -> valid("http://x", "m", Duration.ofSeconds(1), Duration.ZERO, 1, 1, 0, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void maxContextChars가_0이하이면_예외() {
        assertThatThrownBy(() -> valid("http://x", "m", Duration.ofSeconds(1), Duration.ofSeconds(1), 0, 1, 0, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void maxTokens가_0이하이면_예외() {
        assertThatThrownBy(() -> valid("http://x", "m", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 0, 0, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void temperature가_범위를_벗어나면_예외() {
        assertThatThrownBy(() -> valid("http://x", "m", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, -0.1, ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> valid("http://x", "m", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 2.1, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void temperature_경계값_0과_2는_허용된다() {
        assertThat(valid("http://x", "m", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 0, "").temperature())
                .isZero();
        assertThat(valid("http://x", "m", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 2, "").temperature())
                .isEqualTo(2);
    }
}
