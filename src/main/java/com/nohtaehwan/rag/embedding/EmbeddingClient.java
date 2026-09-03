package com.nohtaehwan.rag.embedding;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.nohtaehwan.rag.exception.RagException;

import lombok.extern.slf4j.Slf4j;

/**
 * BGE-M3 Embedding API(OpenAI 호환 /v1/embeddings)를 호출해 문장을 벡터로 변환한다.
 * 문서 색인과 질문 검색 모두 이 클라이언트를 통해 동일한 모델·차원 기준을 사용한다.
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final EmbeddingProperties properties;
    private final RestClient restClient;

    public EmbeddingClient(RestClient embeddingRestClient, EmbeddingProperties properties) {
        this.restClient = embeddingRestClient;
        this.properties = properties;
    }

    /**
     * 입력 문장에 대한 Embedding 벡터를 생성한다.
     *
     * @param text Embedding을 생성할 단일 문장 (문서 Chunk 또는 사용자 질문)
     * @return BGE-M3가 생성한 dense embedding 벡터
     * @throws RagException API 호출 실패, 응답 파싱 실패, 응답 모델 불일치, 벡터 차원 불일치 시 발생
     */
    public List<Double> embed(String text) {
        EmbeddingResponse response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .body(new EmbeddingRequest(properties.model(), text))
                    .retrieve()
                    .body(EmbeddingResponse.class);
        } catch (RestClientException e) {
            log.warn("Embedding API 호출 실패: baseUrl={}, model={}", properties.baseUrl(), properties.model(), e);
            throw new RagException("Embedding API 호출에 실패했습니다.", e);
        }

        if (response == null || response.data() == null || response.data().isEmpty()) {
            log.warn("Embedding API 응답이 비어 있음: baseUrl={}", properties.baseUrl());
            throw new RagException("Embedding API 응답이 비어 있습니다.");
        }

        if (response.model() != null && !response.model().equals(properties.model())) {
            log.warn("Embedding 응답 모델 불일치: expected={}, actual={}", properties.model(), response.model());
            throw new RagException(
                    "Embedding 응답 모델이 설정과 다릅니다. expected=%s, actual=%s"
                            .formatted(properties.model(), response.model()));
        }

        List<Double> embedding = response.data().get(0).embedding();
        if (embedding == null || embedding.isEmpty()) {
            log.warn("Embedding API 응답에 embedding 필드가 없음: baseUrl={}", properties.baseUrl());
            throw new RagException("Embedding API 응답에 embedding 값이 없습니다.");
        }
        if (embedding.size() != properties.dimension()) {
            log.warn("Embedding 벡터 차원 불일치: expected={}, actual={}", properties.dimension(), embedding.size());
            throw new RagException(
                    "Embedding 벡터 차원이 설정과 다릅니다. expected=%d, actual=%d"
                            .formatted(properties.dimension(), embedding.size()));
        }

        log.debug("Embedding 생성 성공: model={}, dimension={}", properties.model(), embedding.size());
        return embedding;
    }
}
