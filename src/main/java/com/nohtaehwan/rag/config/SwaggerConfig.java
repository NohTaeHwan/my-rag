package com.nohtaehwan.rag.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Swagger(OpenAPI 3) 설정. 현재 API 2개(`/api/documents/index`, `/api/search`)만 문서화 대상이라
 * 그룹은 전체 하나만 둔다. 인증이 없는 프로젝트라 Security Scheme은 추가하지 않는다.
 *
 * <p>접속 경로: {@code http://localhost:8080/swagger-ui.html}
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("my-rag API")
                        .description("Markdown 문서를 색인하고 질문과 관련된 Chunk를 검색하는 RAG Backend API")
                        .version("v1.0.0"));
    }

    @Bean
    public GroupedOpenApi allApi() {
        return GroupedOpenApi.builder()
                .group("00. 전체")
                .pathsToMatch("/api/**")
                .build();
    }
}
