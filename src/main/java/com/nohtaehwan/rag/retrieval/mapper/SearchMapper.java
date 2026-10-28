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
     * 주어진 사용자·Knowledge Base의 {@code INDEXED} 문서 Chunk 중에서 queryEmbeddingLiteral과
     * cosine distance가 가까운 순으로 topK개 검색한다. distance는 pgvector {@code <=>} 연산자 결과이며
     * 낮을수록 유사하다. 범위 조건은 WHERE에서 ORDER BY·LIMIT보다 먼저 적용되므로 범위 밖 Chunk가 더
     * 가까워도 결과·topK 자리를 차지하지 않는다.
     *
     * @param queryEmbeddingLiteral {@code [0.1,0.2,...]} 형태의 pgvector 리터럴 문자열
     * @param userId                현재 사용자 ID
     * @param knowledgeBaseId       검색 대상 Knowledge Base ID
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
            WHERE d.user_id = #{userId}
              AND d.knowledge_base_id = #{knowledgeBaseId}
              AND d.index_status = 'INDEXED'
            ORDER BY c.embedding <=> #{queryEmbeddingLiteral}::vector
            LIMIT #{topK}
            """)
    List<SearchRow> search(
            @Param("queryEmbeddingLiteral") String queryEmbeddingLiteral,
            @Param("userId") Long userId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("topK") int topK);
}
