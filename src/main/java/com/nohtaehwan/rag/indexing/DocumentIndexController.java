package com.nohtaehwan.rag.indexing;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nohtaehwan.rag.exception.RagException;

import lombok.extern.slf4j.Slf4j;

/**
 * Markdown 문서 색인 API. 요청 본문은 없으며, 설정된 디렉터리 전체를 색인한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
public class DocumentIndexController {

    private final DocumentIndexService documentIndexService;

    public DocumentIndexController(DocumentIndexService documentIndexService) {
        this.documentIndexService = documentIndexService;
    }

    @PostMapping("/index")
    public DocumentIndexResponse index() {
        return documentIndexService.indexDocuments();
    }

    /**
     * 색인 중 발생한 비즈니스 예외를 처리한다. 원인 상세(내용/벡터 포함 가능)는 로그에만 남기고
     * 응답 본문에는 노출하지 않는다. 전역 예외 처리기가 아직 없어 이 API에 한정해 최소한으로 둔다.
     */
    @ExceptionHandler(RagException.class)
    public ResponseEntity<Map<String, String>> handleRagException(RagException e) {
        log.warn("문서 색인 실패", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "문서 색인에 실패했습니다."));
    }

    /**
     * DB 접근 중 발생한 예외(연결 실패, 제약 조건 위반 등)를 처리한다. DB URL·SQL 상세는 응답에
     * 노출하지 않고 로그에만 남긴다. {@link RagException}으로 감싸지 않은 Spring Data 계층 예외가
     * 대상이며, 그 외 RuntimeException은 이 handler가 가로채지 않는다.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccessException(DataAccessException e) {
        log.warn("문서 색인 중 DB 오류 발생", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "문서 색인에 실패했습니다."));
    }
}
