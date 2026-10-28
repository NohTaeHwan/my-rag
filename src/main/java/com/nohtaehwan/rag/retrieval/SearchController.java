package com.nohtaehwan.rag.retrieval;

import java.util.Map;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.nohtaehwan.rag.exception.InvalidRequestException;
import com.nohtaehwan.rag.exception.RagException;
import com.nohtaehwan.rag.user.CurrentUserProvider;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 질문과 관련된 Chunk를 현재 사용자 소유의 단일 Knowledge Base 안에서 검색하는 API.
 * LLM 답변 생성·Reranker는 이번 범위가 아니다.
 */
@Slf4j
@RestController
@Tag(name = "검색", description = "질문 embedding 기반 유사도 검색 API")
public class SearchController {

    private static final String INVALID_QUERY_MESSAGE = "query는 비어 있을 수 없습니다.";
    private static final String INVALID_KNOWLEDGE_BASE_ID_MESSAGE = "knowledgeBaseId는 양의 정수여야 합니다.";
    private static final String KNOWLEDGE_BASE_NOT_FOUND_MESSAGE = "Knowledge Base를 찾을 수 없습니다.";

    private final SearchService searchService;
    private final CurrentUserProvider currentUserProvider;

    public SearchController(SearchService searchService, CurrentUserProvider currentUserProvider) {
        this.searchService = searchService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * 현재 사용자 소유 Knowledge Base 안에서 질문과 관련된 Chunk를 검색한다. 사용자 ID는 요청에서 받지 않고
     * {@link CurrentUserProvider}가 결정한다(요청에 userId가 있어도 무시).
     *
     * @param request query와 knowledgeBaseId(둘 다 필수)
     */
    @Operation(
            summary = "유사도 검색",
            description = "현재 사용자 소유의 지정 Knowledge Base에 색인된(INDEXED) 문서 안에서 질문과 관련된 Chunk를 "
                    + "cosine distance 기준으로 검색한다.")
    @ApiResponse(responseCode = "200", description = "검색 성공(유효한 KB에 결과가 없으면 빈 목록)")
    @ApiResponse(responseCode = "400", description = "query가 없거나 비어 있음, knowledgeBaseId가 없거나 양의 정수가 아님")
    @ApiResponse(responseCode = "404", description = "Knowledge Base가 없거나 현재 사용자 소유가 아님(구분하지 않음)")
    @ApiResponse(responseCode = "500", description = "Embedding 또는 DB 검색 실패")
    @GetMapping("/api/search")
    public SearchResponse search(@Valid @ParameterObject SearchRequest request) {
        return searchService.search(currentUserProvider.currentUserId(), request.knowledgeBaseId(), request.query());
    }

    /**
     * 요청 파라미터 검증·변환 실패(query 누락·공백, knowledgeBaseId 누락·비숫자·0 이하)를 처리한다.
     * Spring 기본 메시지(예외·타입 정보 포함)는 노출하지 않고 필드별 고정 메시지만 반환한다.
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Map<String, String>> handleBindException(BindException e) {
        String message = e.hasFieldErrors("query") ? INVALID_QUERY_MESSAGE : INVALID_KNOWLEDGE_BASE_ID_MESSAGE;
        log.info("검색 요청 검증 실패: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", message));
    }

    /**
     * 없는 KB와 다른 사용자 소유 KB를 같은 404·같은 메시지로 처리해 존재 여부와 소유자를 노출하지 않는다.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatusException(ResponseStatusException e) {
        log.info("검색 대상 Knowledge Base 없음 또는 접근 불가");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", KNOWLEDGE_BASE_NOT_FOUND_MESSAGE));
    }

    /**
     * 잘못된 요청(빈 query 등)을 처리한다. 메시지 자체가 항상 우리가 직접 작성한 안전한 문자열이므로
     * 그대로 노출한다. 전역 예외 처리기가 아직 없어 이 API에 한정해 최소한으로 둔다.
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequestException(InvalidRequestException e) {
        log.info("검색 요청이 유효하지 않음: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", e.getMessage()));
    }

    /**
     * embedding 생성 실패 등 비즈니스 예외를 처리한다. 원인 상세(질문 내용·벡터 포함 가능)는 로그에만
     * 남기고 응답 본문에는 노출하지 않는다.
     */
    @ExceptionHandler(RagException.class)
    public ResponseEntity<Map<String, String>> handleRagException(RagException e) {
        log.warn("검색 실패", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "검색에 실패했습니다."));
    }

    /**
     * DB 접근 중 발생한 예외를 처리한다. SQL·DB 접속정보는 응답에 노출하지 않는다.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccessException(DataAccessException e) {
        log.warn("검색 중 DB 오류 발생", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "검색에 실패했습니다."));
    }
}
