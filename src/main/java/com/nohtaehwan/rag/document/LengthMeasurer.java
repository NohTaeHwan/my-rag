package com.nohtaehwan.rag.document;

/**
 * Chunk 길이를 측정하는 인터페이스. 실제 BGE-M3 tokenizer 결과가 아니라 근사치임을 구현체 이름에 명시한다.
 * 나중에 실제 tokenizer 기반 구현으로 교체할 수 있도록 MarkdownChunker와 분리한다.
 */
public interface LengthMeasurer {

    int length(String text);
}
