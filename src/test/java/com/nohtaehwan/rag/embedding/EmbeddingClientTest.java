package com.nohtaehwan.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.nohtaehwan.rag.exception.RagException;

/**
 * EmbeddingClient의 성공/실패/차원 불일치 시나리오를 MockRestServiceServer로 검증한다.
 * 실제 DGX Spark BGE-M3 서버는 호출하지 않는다.
 */
class EmbeddingClientTest {

    private static final String BASE_URL = "http://test-embedding:8003/v1";

    private EmbeddingProperties properties(int dimension) {
        return new EmbeddingProperties(BASE_URL, "BAAI/bge-m3", dimension, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @Test
    void embed_returns1024DimensionVector_whenApiRespondsSuccessfully() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        String body = """
                {
                  "data": [{"embedding": [0.1, 0.2, 0.3, 0.4], "index": 0}],
                  "model": "BAAI/bge-m3"
                }
                """;
        server.expect(requestTo(BASE_URL + "/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        EmbeddingClient client = new EmbeddingClient(restClient, properties(4));

        List<Double> embedding = client.embed("연결 테스트");

        assertThat(embedding).hasSize(4).containsExactly(0.1, 0.2, 0.3, 0.4);
        server.verify();
    }

    @Test
    void embed_throwsRagException_whenApiCallFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(BASE_URL + "/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        EmbeddingClient client = new EmbeddingClient(restClient, properties(1024));

        assertThatThrownBy(() -> client.embed("연결 테스트"))
                .isInstanceOf(RagException.class);
        server.verify();
    }

    @Test
    void embed_throwsRagException_whenEmbeddingFieldMissing() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        String body = """
                {
                  "data": [{"index": 0}],
                  "model": "BAAI/bge-m3"
                }
                """;
        server.expect(requestTo(BASE_URL + "/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        EmbeddingClient client = new EmbeddingClient(restClient, properties(1024));

        assertThatThrownBy(() -> client.embed("연결 테스트"))
                .isInstanceOf(RagException.class);
        server.verify();
    }

    @Test
    void embed_throwsRagException_whenVectorDimensionMismatches() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        String body = """
                {
                  "data": [{"embedding": [0.1, 0.2, 0.3, 0.4], "index": 0}],
                  "model": "BAAI/bge-m3"
                }
                """;
        server.expect(requestTo(BASE_URL + "/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        EmbeddingClient client = new EmbeddingClient(restClient, properties(1024));

        assertThatThrownBy(() -> client.embed("연결 테스트"))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("차원");
        server.verify();
    }
}
