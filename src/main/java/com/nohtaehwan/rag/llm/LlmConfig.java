package com.nohtaehwan.rag.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.nohtaehwan.rag.config.Http1RestClients;

/**
 * llm.* 설정을 바인딩하고, OpenAI-compatible LLM API 호출용 RestClient를 구성한다.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public RestClient llmRestClient(RestClient.Builder builder, LlmProperties properties) {
        return Http1RestClients.build(builder, properties.baseUrl(), properties.connectTimeout(), properties.readTimeout());
    }
}
