package com.nohtaehwan.rag.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * document.* 설정 값을 바인딩한다.
 *
 * @param sourceDirectory Markdown 문서를 읽어올 디렉터리 (상대/절대 경로 모두 허용)
 * @param chunk           Chunking 길이 설정
 */
@ConfigurationProperties(prefix = "document")
public record DocumentProperties(String sourceDirectory, Chunk chunk) {

    public DocumentProperties {
        if (sourceDirectory == null || sourceDirectory.isBlank()) {
            throw new IllegalStateException("document.source-directory must not be blank");
        }
        if (chunk == null) {
            throw new IllegalStateException("document.chunk must not be null");
        }
    }

    /**
     * Chunk 길이 설정. 단위는 실제 tokenizer 기반 토큰이 아니라 문자 수 근사치(approximate character length)다.
     *
     * @param targetLength  Chunk가 목표로 하는 최대 길이(문자 수 근사치)
     * @param overlapLength 인접 Chunk 사이에 겹치는 본문 길이(문자 수 근사치)
     * @param minLength     이 길이 미만인 Chunk는 같은 섹션 내 이전 Chunk에 병합한다
     */
    public record Chunk(int targetLength, int overlapLength, int minLength) {

        public Chunk {
            if (targetLength <= 0) {
                throw new IllegalStateException("document.chunk.target-length must be positive: " + targetLength);
            }
            if (overlapLength < 0 || overlapLength >= targetLength) {
                throw new IllegalStateException(
                        "document.chunk.overlap-length must be in [0, target-length): " + overlapLength);
            }
            if (minLength < 0 || minLength >= targetLength) {
                throw new IllegalStateException(
                        "document.chunk.min-length must be in [0, target-length): " + minLength);
            }
        }
    }
}
