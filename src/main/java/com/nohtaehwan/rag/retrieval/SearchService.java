package com.nohtaehwan.rag.retrieval;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.nohtaehwan.rag.embedding.EmbeddingClient;
import com.nohtaehwan.rag.embedding.VectorLiteral;
import com.nohtaehwan.rag.exception.InvalidRequestException;
import com.nohtaehwan.rag.exception.RagException;
import com.nohtaehwan.rag.retrieval.mapper.SearchMapper;
import com.nohtaehwan.rag.retrieval.mapper.SearchRow;

import lombok.extern.slf4j.Slf4j;

/**
 * 질문을 embedding으로 변환해 pgvector cosine distance로 관련 Chunk를 검색한다.
 *
 * <p>질문 embedding 생성(외부 API 호출)과 DB 검색은 하나의 트랜잭션으로 묶지 않는다 — 이 흐름은
 * 읽기 전용이라 색인의 delete+insert 같은 원자성 요구가 없다.
 */
@Slf4j
@Service
@EnableConfigurationProperties(RetrievalProperties.class)
public class SearchService {

    private final EmbeddingClient embeddingClient;
    private final SearchMapper searchMapper;
    private final RetrievalProperties retrievalProperties;

    public SearchService(
            EmbeddingClient embeddingClient, SearchMapper searchMapper, RetrievalProperties retrievalProperties) {
        this.embeddingClient = embeddingClient;
        this.searchMapper = searchMapper;
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * 질문과 관련된 Chunk를 distance 오름차순으로 검색한다.
     *
     * @param query 사용자 질문
     * @return 검색 결과 (결과가 없으면 빈 목록을 담은 응답, 오류 아님)
     * @throws InvalidRequestException query가 null이거나 공백만 있을 때
     * @throws RagException            embedding 생성에 실패했을 때({@link EmbeddingClient}가 던짐)
     */
    public SearchResponse search(String query) {
        if (query == null || query.isBlank()) {
            throw new InvalidRequestException("query는 비어 있을 수 없습니다.");
        }

        List<Double> queryEmbedding = embeddingClient.embed(query);
        String queryEmbeddingLiteral = VectorLiteral.of(queryEmbedding);

        List<SearchRow> rows = searchMapper.search(queryEmbeddingLiteral, retrievalProperties.topK());

        List<SearchResult> results = rows.stream()
                .map(row -> new SearchResult(
                        row.documentId(), row.title(), row.source(), row.content(), row.chunkIndex(), row.distance()))
                .toList();

        log.info("검색 완료: queryLength={}, topK={}, resultCount={}",
                query.length(), retrievalProperties.topK(), results.size());
        return new SearchResponse(results);
    }
}
