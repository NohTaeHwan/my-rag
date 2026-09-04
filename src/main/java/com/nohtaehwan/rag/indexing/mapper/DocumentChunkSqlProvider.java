package com.nohtaehwan.rag.indexing.mapper;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.ibatis.annotations.Param;

/**
 * {@link DocumentChunkMapper#insertAll}의 multi-row INSERT SQL을 순수 Java로 만든다.
 * chunks 개수만큼 {@code VALUES} 절을 늘리는 것뿐이며, 실제 값은 전부 {@code #{...}} 파라미터
 * 바인딩이다(문자열 연결로 값 자체를 SQL에 넣지 않는다 — SQL Injection 위험 없음).
 */
public class DocumentChunkSqlProvider {

    public String insertAll(@Param("documentId") Long documentId, @Param("chunks") List<ChunkRow> chunks) {
        String values = IntStream.range(0, chunks.size())
                .mapToObj("(#{documentId}, #{chunks[%1$d].content}, #{chunks[%1$d].chunkIndex}, #{chunks[%1$d].embeddingLiteral}::vector)"::formatted)
                .collect(Collectors.joining(", "));
        return "INSERT INTO tb_document_chunk (document_id, content, chunk_index, embedding) VALUES " + values;
    }
}
