package com.nohtaehwan.rag.embedding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * EmbeddingProperties의 fail-fast 검증(compact constructor)을 확인한다.
 */
class EmbeddingPropertiesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Test
    void constructor_throws_whenBaseUrlBlank() {
        assertThatThrownBy(() -> new EmbeddingProperties(" ", "BAAI/bge-m3", 1024, TIMEOUT, TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    void constructor_throws_whenModelBlank() {
        assertThatThrownBy(() -> new EmbeddingProperties("http://test/v1", "", 1024, TIMEOUT, TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
    }

    @Test
    void constructor_throws_whenDimensionNotPositive() {
        assertThatThrownBy(() -> new EmbeddingProperties("http://test/v1", "BAAI/bge-m3", 0, TIMEOUT, TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension");
    }
}
