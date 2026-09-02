package com.nohtaehwan.rag.embedding;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * BGE-M3 Embedding API(OpenAI 호환 /v1/embeddings) 응답 본문.
 * 응답에 포함된 usage 등 사용하지 않는 필드는 무시한다.
 *
 * @param data  생성된 Embedding 목록 (단일 입력 요청 시 1건)
 * @param model 실제로 사용된 모델명
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingResponse(List<EmbeddingData> data, String model) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingData(List<Double> embedding, int index) {
    }
}
