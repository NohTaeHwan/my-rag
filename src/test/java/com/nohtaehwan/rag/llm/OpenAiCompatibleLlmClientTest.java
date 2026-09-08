package com.nohtaehwan.rag.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.nohtaehwan.rag.exception.RagException;

/**
 * OpenAiCompatibleLlmClient의 성공/실패 시나리오를 MockRestServiceServer로 검증한다.
 * 실제 LLM 서버는 호출하지 않는다.
 */
class OpenAiCompatibleLlmClientTest {

    private static final String BASE_URL = "http://test-llm:8002/v1";

    private MockRestServiceServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private LlmProperties properties(String apiKey) {
        return new LlmProperties(BASE_URL, "local-model", Duration.ofSeconds(1), Duration.ofSeconds(1), 12000, 512, 0.0, apiKey);
    }

    @Test
    void complete_요청_body에_model_messages_temperature_maxTokens를_담아_보낸다() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(jsonPath("$.model").value("local-model"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("system-prompt"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("user-prompt"))
                .andExpect(jsonPath("$.temperature").value(0.0))
                .andExpect(jsonPath("$.max_tokens").value(512))
                .andRespond(withSuccess("""
                        {"choices": [{"message": {"role": "assistant", "content": "답변"}}]}
                        """, MediaType.APPLICATION_JSON));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(restClient, properties(""));

        String answer = client.complete(new Prompt("system-prompt", "user-prompt"));

        assertThat(answer).isEqualTo("답변");
        server.verify();
    }

    @Test
    void complete_apiKey가_있으면_Authorization_헤더를_붙인다() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess("""
                        {"choices": [{"message": {"role": "assistant", "content": "답변"}}]}
                        """, MediaType.APPLICATION_JSON));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(restClient, properties("test-key"));

        client.complete(new Prompt("s", "u"));

        server.verify();
    }

    @Test
    void complete_HTTP_오류시_RagException으로_변환한다() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(restClient, properties(""));

        assertThatThrownBy(() -> client.complete(new Prompt("s", "u")))
                .isInstanceOf(RagException.class);
        server.verify();
    }

    @Test
    void complete_choices가_비어있으면_RagException() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"choices": []}
                        """, MediaType.APPLICATION_JSON));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(restClient, properties(""));

        assertThatThrownBy(() -> client.complete(new Prompt("s", "u")))
                .isInstanceOf(RagException.class);
        server.verify();
    }

    @Test
    void complete_content가_비어있으면_RagException() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"choices": [{"message": {"role": "assistant", "content": "  "}}]}
                        """, MediaType.APPLICATION_JSON));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(restClient, properties(""));

        assertThatThrownBy(() -> client.complete(new Prompt("s", "u")))
                .isInstanceOf(RagException.class);
        server.verify();
    }

    @Test
    void complete_choices가_null이면_RagException() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"choices": null}
                        """, MediaType.APPLICATION_JSON));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(restClient, properties(""));

        assertThatThrownBy(() -> client.complete(new Prompt("s", "u")))
                .isInstanceOf(RagException.class);
        server.verify();
    }

    @Test
    void complete_choices의_첫_항목이_null이면_RagException() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"choices": [null]}
                        """, MediaType.APPLICATION_JSON));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(restClient, properties(""));

        assertThatThrownBy(() -> client.complete(new Prompt("s", "u")))
                .isInstanceOf(RagException.class);
        server.verify();
    }

    @Test
    void complete_choice의_message가_null이면_RagException() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"choices": [{"message": null}]}
                        """, MediaType.APPLICATION_JSON));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(restClient, properties(""));

        assertThatThrownBy(() -> client.complete(new Prompt("s", "u")))
                .isInstanceOf(RagException.class);
        server.verify();
    }

    @Test
    void complete_message_content가_null이면_RagException() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"choices": [{"message": {"role": "assistant", "content": null}}]}
                        """, MediaType.APPLICATION_JSON));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(restClient, properties(""));

        assertThatThrownBy(() -> client.complete(new Prompt("s", "u")))
                .isInstanceOf(RagException.class);
        server.verify();
    }
}
