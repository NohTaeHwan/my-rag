package com.nohtaehwan.rag.retrieval.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * pgvector cosine distance 기반 Chunk 검색 mapper. SQL은 annotation에 직접 작성한다(XML 없음).
 */
@Mapper
public interface SearchMapper {

    /**
     * queryEmbeddingLiteral과 cosine distance가 가까운 순으로 Chunk를 topK개 검색한다.
     * distance는 pgvector {@code <=>} 연산자 결과이며 낮을수록 유사하다.
     *
     * @param queryEmbeddingLiteral {@code [0.1,0.2,...]} 형태의 pgvector 리터럴 문자열
     * @param topK                  반환할 최대 결과 수
     */
    @Select("""
            SELECT c.document_id,
                   d.title,
                   d.source,
                   c.content,
                   c.chunk_index,
                   c.embedding <=> #{queryEmbeddingLiteral}::vector AS distance
            FROM tb_document_chunk c
            JOIN tb_document d ON d.id = c.document_id
            ORDER BY c.embedding <=> #{queryEmbeddingLiteral}::vector
            LIMIT #{topK}
            """)
    List<SearchRow> search(@Param("queryEmbeddingLiteral") String queryEmbeddingLiteral, @Param("topK") int topK);
}
