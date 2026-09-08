package com.nohtaehwan.rag.answer;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nohtaehwan.rag.exception.InvalidRequestException;
import com.nohtaehwan.rag.exception.RagException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 질문과 관련된 검색 근거를 바탕으로 LLM 답변을 생성하는 API.
 */
@Slf4j
@RestController
@Tag(name = "답변", description = "질문에 대해 검색 근거 기반 LLM 답변을 생성하는 API")
public class AnswerController {

    private final AnswerService answerService;

    public AnswerController(AnswerService answerService) {
        this.answerService = answerService;
    }

    @Operation(
            summary = "답변 생성",
            description = "질문과 관련된 Chunk를 검색해 근거로 삼아 LLM 답변을 생성한다. 근거가 없으면 LLM을 호출하지 않고 고정 응답을 반환한다.")
    @ApiResponse(responseCode = "200", description = "답변 생성 성공(근거 부족 시에도 200)")
    @ApiResponse(responseCode = "400", description = "question이 없거나 비어 있음")
    @ApiResponse(responseCode = "500", description = "검색 또는 LLM 호출 실패")
    @PostMapping("/api/answers")
    public AnswerResponse answer(@Valid @RequestBody AnswerRequest request) {
        return answerService.answer(request.question());
    }

    /**
     * {@code @Valid} 검증 실패(question 누락·공백)를 처리한다. 프로젝트의 다른 400 응답과 형식을
     * 맞추기 위해 Spring 기본 오류 형식 대신 {@code {"message": "..."}}로 통일한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(MethodArgumentNotValidException e) {
        log.info("답변 요청 검증 실패");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", "question은 비어 있을 수 없습니다."));
    }

    /**
     * 잘못된 요청을 처리한다. 메시지 자체가 항상 우리가 직접 작성한 안전한 문자열이므로 그대로 노출한다.
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequestException(InvalidRequestException e) {
        log.info("답변 요청이 유효하지 않음: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", e.getMessage()));
    }

    /**
     * 검색(embedding/DB) 또는 LLM 호출 실패를 처리한다. URL·API Key·prompt·원인 상세는 로그에만
     * 남기고 응답에는 노출하지 않는다.
     */
    @ExceptionHandler(RagException.class)
    public ResponseEntity<Map<String, String>> handleRagException(RagException e) {
        log.warn("답변 생성 실패", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "답변 생성에 실패했습니다."));
    }

    /**
     * DB 접근 중 발생한 예외를 처리한다. SQL·DB 접속정보는 응답에 노출하지 않는다.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccessException(DataAccessException e) {
        log.warn("답변 생성 중 DB 오류 발생", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "답변 생성에 실패했습니다."));
    }
}
