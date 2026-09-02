package com.nohtaehwan.rag.embedding;

/**
 * BGE-M3 Embedding API(OpenAI 호환 /v1/embeddings) 요청 본문.
 *
 * @param model 사용할 Embedding 모델명
 * @param input Embedding을 생성할 단일 문장
 */
public record EmbeddingRequest(String model, String input) {
}
