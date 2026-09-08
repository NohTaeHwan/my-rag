package com.nohtaehwan.rag.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * application.yml의 llm.* 기본값이 실제로 바인딩되는지 확인한다. 실제 LLM_API_KEY 값은 이 테스트에서
 * 검증하지 않는다(로그·출력에 노출하지 않기 위함).
 */
@SpringBootTest
class LlmPropertiesBindingTest {

    @Autowired
    private LlmProperties llmProperties;

    @Test
    void llmProperties_bindsApplicationYmlDefaults() {
        assertThat(llmProperties.model()).isNotBlank();
        assertThat(llmProperties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(llmProperties.readTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(llmProperties.maxContextChars()).isEqualTo(12000);
        assertThat(llmProperties.maxTokens()).isEqualTo(512);
        assertThat(llmProperties.temperature()).isZero();
        assertThat(llmProperties.apiKey()).isNotNull();
    }
}
