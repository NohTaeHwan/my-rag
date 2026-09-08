package com.nohtaehwan.rag.retrieval;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nohtaehwan.rag.exception.InvalidRequestException;
import com.nohtaehwan.rag.exception.RagException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 질문과 관련된 Chunk를 검색하는 API. LLM 답변 생성·Reranker는 이번 범위가 아니다.
 */
@Slf4j
@RestController
@Tag(name = "검색", description = "질문 embedding 기반 유사도 검색 API")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(summary = "유사도 검색", description = "질문과 관련된 Chunk를 cosine distance 기준으로 검색한다.")
    @ApiResponse(responseCode = "200", description = "검색 성공")
    @ApiResponse(responseCode = "400", description = "query가 없거나 비어 있음")
    @ApiResponse(responseCode = "500", description = "Embedding 또는 DB 검색 실패")
    @GetMapping("/api/search")
    public SearchResponse search(
            @Parameter(description = "검색 질문", example = "결제 취소 방법", required = true) @RequestParam String query) {
        return searchService.search(query);
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
