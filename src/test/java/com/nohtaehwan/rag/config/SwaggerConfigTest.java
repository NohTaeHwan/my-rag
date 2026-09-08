package com.nohtaehwan.rag.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.swagger.v3.oas.models.OpenAPI;

/**
 * SwaggerConfig의 Bean이 Spring context에 정상 등록되는지 확인한다.
 */
@SpringBootTest
class SwaggerConfigTest {

    @Autowired
    private OpenAPI openAPI;

    @Autowired
    private GroupedOpenApi allApi;

    @Test
    void openAPI_Bean이_등록되고_기본_정보가_설정된다() {
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("my-rag API");
        assertThat(openAPI.getInfo().getVersion()).isEqualTo("v1.0.0");
    }

    @Test
    void groupedOpenApi_Bean이_api_경로만_대상으로_등록된다() {
        assertThat(allApi.getGroup()).isEqualTo("00. 전체");
        assertThat(allApi.getPathsToMatch()).containsExactly("/api/**");
    }
}
