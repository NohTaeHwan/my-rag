package com.nohtaehwan.rag.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * DocumentProperties의 fail-fast 검증(compact constructor)을 확인한다.
 */
class DocumentPropertiesTest {

    private DocumentProperties.Chunk validChunk() {
        return new DocumentProperties.Chunk(1200, 150, 80);
    }

    @Test
    void constructor_throws_whenSourceDirectoryBlank() {
        assertThatThrownBy(() -> new DocumentProperties(" ", validChunk()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source-directory");
    }

    @Test
    void constructor_throws_whenChunkNull() {
        assertThatThrownBy(() -> new DocumentProperties("data/markdown", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chunk");
    }

    @Test
    void chunkConstructor_throws_whenTargetLengthNotPositive() {
        assertThatThrownBy(() -> new DocumentProperties.Chunk(0, 0, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("target-length");
    }

    @Test
    void chunkConstructor_throws_whenOverlapLengthNotLessThanTarget() {
        assertThatThrownBy(() -> new DocumentProperties.Chunk(100, 100, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overlap-length");
    }

    @Test
    void chunkConstructor_throws_whenMinLengthNotLessThanTarget() {
        assertThatThrownBy(() -> new DocumentProperties.Chunk(100, 10, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("min-length");
    }
}
