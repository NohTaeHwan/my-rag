package com.nohtaehwan.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RetrievalPropertiesTest {

    @Test
    void topK가_1_이상_50_이하이면_생성된다() {
        RetrievalProperties properties = new RetrievalProperties(5);

        assertThat(properties.topK()).isEqualTo(5);
    }

    @Test
    void topK가_50이면_허용된다() {
        RetrievalProperties properties = new RetrievalProperties(50);

        assertThat(properties.topK()).isEqualTo(50);
    }

    @Test
    void topK가_0_이하이면_예외() {
        assertThatThrownBy(() -> new RetrievalProperties(0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RetrievalProperties(-1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void topK가_50_초과이면_예외() {
        assertThatThrownBy(() -> new RetrievalProperties(51))
                .isInstanceOf(IllegalStateException.class);
    }
}
