package com.nohtaehwan.rag.exception;

/**
 * 프로젝트 전역에서 사용하는 단일 비즈니스 예외.
 * 외부 API 호출 실패, 데이터 검증 실패 등 복구 불가능한 비즈니스 오류를 표현한다.
 */
public class RagException extends RuntimeException {

    public RagException(String message) {
        super(message);
    }

    public RagException(String message, Throwable cause) {
        super(message, cause);
    }
}
