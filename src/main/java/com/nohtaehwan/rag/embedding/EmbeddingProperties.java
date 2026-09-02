package com.nohtaehwan.rag.embedding;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * embedding.* 설정 값을 바인딩한다.
 *
 * @param baseUrl        BGE-M3 Embedding API base URL (예: http://host:8003/v1)
 * @param model          Embedding 모델명 (예: BAAI/bge-m3)
 * @param dimension      기대하는 벡터 차원 (BGE-M3 Dense 기준 1024)
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout    응답 대기 타임아웃
 */
@ConfigurationProperties(prefix = "embedding")
public record EmbeddingProperties(
        String baseUrl,
        String model,
        int dimension,
        Duration connectTimeout,
        Duration readTimeout
) {

    public EmbeddingProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("embedding.base-url must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("embedding.model must not be blank");
        }
        if (dimension <= 0) {
            throw new IllegalStateException("embedding.dimension must be positive: " + dimension);
        }
    }
}
