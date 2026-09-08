package com.nohtaehwan.rag.answer;

/**
 * 답변 근거로 실제 prompt에 포함된 검색 결과 1건. content는 포함하지 않는다(출처 식별과 distance만).
 *
 * @param documentId Chunk가 속한 tb_document.id
 * @param title      문서 제목
 * @param source     문서 source key
 * @param chunkIndex 문서 내 Chunk 순서
 * @param distance   pgvector cosine distance (낮을수록 유사)
 */
public record AnswerSource(Long documentId, String title, String source, Integer chunkIndex, Double distance) {
}
