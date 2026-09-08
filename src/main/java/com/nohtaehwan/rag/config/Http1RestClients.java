package com.nohtaehwan.rag.config;

import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP/2 협상 시 요청 body가 소실되는 문제(3단계 EmbeddingClient에서 실제 발견)를 피하기 위해
 * HTTP/1.1을 강제하는 RestClient를 만든다. 외부 API를 호출하는 Config 클래스(EmbeddingConfig,
 * LlmConfig 등)가 공유한다.
 */
public final class Http1RestClients {

    private Http1RestClients() {
    }

    public static RestClient build(RestClient.Builder builder, String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
        requestFactory.setReadTimeout((int) readTimeout.toMillis());

        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
