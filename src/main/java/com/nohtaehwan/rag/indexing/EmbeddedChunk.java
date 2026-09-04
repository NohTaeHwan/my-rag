package com.nohtaehwan.rag.indexing;

import java.util.List;

import com.nohtaehwan.rag.document.DocumentChunk;

/**
 * Embedding 생성이 끝난 Chunk. Service가 저장 계층(DocumentRepository/DocumentChunkRepository)에
 * 넘기는 내부 전달용 타입이며, 패키지 밖으로 노출하지 않는다.
 *
 * @param chunk     원본 Chunk (내용·순번·heading 정보 포함)
 * @param embedding chunk.content()에 대해 생성된 dense embedding 벡터
 */
record EmbeddedChunk(DocumentChunk chunk, List<Double> embedding) {
}
