package com.nohtaehwan.rag.retrieval;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.nohtaehwan.rag.embedding.EmbeddingClient;
import com.nohtaehwan.rag.embedding.VectorLiteral;
import com.nohtaehwan.rag.exception.InvalidRequestException;
import com.nohtaehwan.rag.exception.RagException;
import com.nohtaehwan.rag.knowledgebase.mapper.KnowledgeBaseMapper;
import com.nohtaehwan.rag.retrieval.mapper.SearchMapper;
import com.nohtaehwan.rag.retrieval.mapper.SearchRow;

import lombok.extern.slf4j.Slf4j;

/**
 * 질문을 embedding으로 변환해 pgvector cosine distance로 관련 Chunk를 검색한다. 검색 범위는 항상
 * 현재 사용자 소유의 단일 Knowledge Base에 속한 {@code INDEXED} 문서로 제한된다 — 범위 없는 검색 경로는 없다.
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
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RetrievalProperties retrievalProperties;

    public SearchService(
            EmbeddingClient embeddingClient,
            SearchMapper searchMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            RetrievalProperties retrievalProperties) {
        this.embeddingClient = embeddingClient;
        this.searchMapper = searchMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * 현재 사용자 소유 Knowledge Base 안에서 질문과 관련된 Chunk를 distance 오름차순으로 검색한다.
     * KB 소유 확인은 embedding 생성(외부 호출) 전에 수행한다.
     *
     * @param userId          현재 사용자 ID(서버가 결정한 값, 클라이언트 입력 아님)
     * @param knowledgeBaseId 검색할 Knowledge Base ID
     * @param query           사용자 질문
     * @return 검색 결과 (유효한 KB에 결과가 없으면 빈 목록을 담은 응답, 오류 아님)
     * @throws NullPointerException    userId가 null일 때(서버 코드 오류)
     * @throws InvalidRequestException query가 null·공백이거나 knowledgeBaseId가 양의 정수가 아닐 때
     * @throws ResponseStatusException KB가 없거나 다른 사용자 소유일 때(둘 다 404, 구분하지 않음)
     * @throws RagException            embedding 생성에 실패했을 때({@link EmbeddingClient}가 던짐)
     */
    public SearchResponse search(Long userId, Long knowledgeBaseId, String query) {
        Objects.requireNonNull(userId, "userId");
        if (query == null || query.isBlank()) {
            throw new InvalidRequestException("query는 비어 있을 수 없습니다.");
        }
        if (knowledgeBaseId == null || knowledgeBaseId <= 0) {
            throw new InvalidRequestException("knowledgeBaseId는 양의 정수여야 합니다.");
        }
        if (!knowledgeBaseMapper.existsByIdAndUserId(knowledgeBaseId, userId)) {
            log.warn("검색 거부: 없거나 접근할 수 없는 Knowledge Base, userId={}, knowledgeBaseId={}",
                    userId, knowledgeBaseId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        List<Double> queryEmbedding = embeddingClient.embed(query);
        String queryEmbeddingLiteral = VectorLiteral.of(queryEmbedding);

        List<SearchRow> rows = searchMapper.search(
                queryEmbeddingLiteral, userId, knowledgeBaseId, retrievalProperties.topK());

        List<SearchResult> results = rows.stream()
                .map(row -> new SearchResult(
                        row.documentId(), row.title(), row.source(), row.content(), row.chunkIndex(), row.distance()))
                .toList();

        log.info("검색 완료: userId={}, knowledgeBaseId={}, queryLength={}, topK={}, resultCount={}",
                userId, knowledgeBaseId, query.length(), retrievalProperties.topK(), results.size());
        return new SearchResponse(results);
    }
}
