package com.nohtaehwan.rag.document;

import java.util.List;

/**
 * MarkdownChunker의 분할 결과 Chunk 하나. stage 4에서는 DB id를 생성하지 않는다.
 *
 * @param documentSourceKey 소속 문서의 source key (base directory 기준 정규화된 상대 경로)
 * @param chunkKey          "{documentSourceKey}#{index}" 형태의 결정적 식별자
 * @param index             문서 내 Chunk 순번 (0부터 시작, 등장 순서 그대로)
 * @param headingPath       이 Chunk가 속한 heading 경로 (heading이 없으면 빈 목록)
 * @param content           Chunk 본문 (heading 텍스트는 포함하지 않음, 별도 메타데이터로 취급)
 * @param length             본문의 길이 근사치 (LengthMeasurer 기준, 실제 토큰 수 아님)
 */
public record DocumentChunk(
        String documentSourceKey,
        String chunkKey,
        int index,
        List<String> headingPath,
        String content,
        int length
) {
}
