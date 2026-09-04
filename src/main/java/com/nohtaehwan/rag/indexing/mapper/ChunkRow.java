package com.nohtaehwan.rag.indexing.mapper;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * tb_document_chunk insert 1행에 필요한 값. embeddingLiteral은 이미 {@code [0.1,0.2,...]}
 * 형태의 pgvector 문자열로 변환된 값이며, {@code DocumentChunkSqlProvider}가 생성하는 SQL에서
 * {@code ::vector}로 캐스팅해 바인딩한다.
 */
@Getter
@RequiredArgsConstructor
public class ChunkRow {

    private final String content;
    private final int chunkIndex;
    private final String embeddingLiteral;
}
