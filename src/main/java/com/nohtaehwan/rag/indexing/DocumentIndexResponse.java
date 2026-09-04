package com.nohtaehwan.rag.indexing;

/**
 * 문서 색인 API 응답.
 *
 * @param documentCount     이번 색인에서 처리한 문서 수
 * @param chunkCount        이번 색인에서 생성한 Chunk 총 수
 * @param embeddingDimension 사용된 Embedding 벡터 차원
 */
public record DocumentIndexResponse(int documentCount, int chunkCount, int embeddingDimension) {
}
