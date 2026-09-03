package com.nohtaehwan.rag.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * document.* 설정이 실제 Spring Boot Context에서 application.yml 기본값대로 바인딩되는지 검증한다.
 */
@SpringBootTest
class DocumentPropertiesBindingTest {

    @Autowired
    private DocumentProperties documentProperties;

    @Test
    void documentProperties_bindsApplicationYmlDefaults() {
        assertThat(documentProperties.sourceDirectory()).isEqualTo("data/markdown");
        assertThat(documentProperties.chunk().targetLength()).isEqualTo(1200);
        assertThat(documentProperties.chunk().overlapLength()).isEqualTo(150);
        assertThat(documentProperties.chunk().minLength()).isEqualTo(80);
    }
}
