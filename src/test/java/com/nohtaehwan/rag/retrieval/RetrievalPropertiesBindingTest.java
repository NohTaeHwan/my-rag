package com.nohtaehwan.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * application.yml의 retrieval.* 기본값이 실제로 바인딩되는지 확인한다.
 */
@SpringBootTest
class RetrievalPropertiesBindingTest {

    @Autowired
    private RetrievalProperties retrievalProperties;

    @Test
    void retrievalProperties_bindsApplicationYmlDefaults() {
        assertThat(retrievalProperties.topK()).isEqualTo(5);
    }
}
