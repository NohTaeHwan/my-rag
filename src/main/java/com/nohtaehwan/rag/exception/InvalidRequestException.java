package com.nohtaehwan.rag.exception;

/**
 * 클라이언트 요청이 애플리케이션 검증 규칙을 위반했을 때 사용하는 단일 예외.
 * {@link RagException}이 500(서버/외부 실패)을 표현하는 것과 대칭으로, 이 예외는 400(잘못된 요청)을
 * 표현한다. 특정 필드에 묶이지 않은 범용 이름으로, 새 검증 규칙이 생겨도 이 예외를 재사용한다.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
