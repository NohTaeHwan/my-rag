package com.nohtaehwan.rag.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * retrieval.* 설정 값을 바인딩한다.
 *
 * @param topK 검색 결과로 반환할 최대 Chunk 수 (요청 파라미터로 받지 않고 설정으로 고정한다)
 */
@ConfigurationProperties(prefix = "retrieval")
public record RetrievalProperties(int topK) {

    private static final int MAX_TOP_K = 50;

    public RetrievalProperties {
        if (topK <= 0) {
            throw new IllegalStateException("retrieval.top-k must be positive: " + topK);
        }
        if (topK > MAX_TOP_K) {
            throw new IllegalStateException("retrieval.top-k must not exceed " + MAX_TOP_K + ": " + topK);
        }
    }
}
