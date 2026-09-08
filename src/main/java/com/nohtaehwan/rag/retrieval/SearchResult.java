package com.nohtaehwan.rag.retrieval;

/**
 * 검색 결과 1건. 응답 전용 DTO이며 embedding 원본 벡터는 포함하지 않는다.
 *
 * @param documentId Chunk가 속한 tb_document.id
 * @param title      문서 제목
 * @param source     문서 source key
 * @param content    Chunk 본문
 * @param chunkIndex 문서 내 Chunk 순서
 * @param distance   pgvector cosine distance (낮을수록 유사, similarity로 변환하지 않음)
 */
public record SearchResult(
        Long documentId,
        String title,
        String source,
        String content,
        Integer chunkIndex,
        Double distance
) {
}
