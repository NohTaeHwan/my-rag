package com.nohtaehwan.rag.llm;

import com.nohtaehwan.rag.exception.RagException;

/**
 * LLM provider와 애플리케이션 사이의 추상화. 구현체는 특정 provider의 HTTP 계약을 숨긴다.
 */
public interface LlmClient {

    /**
     * prompt로 LLM 답변을 생성한다.
     *
     * @return LLM이 생성한 답변 텍스트
     * @throws RagException 호출 실패, timeout, 응답 형식 오류 시 발생
     */
    String complete(Prompt prompt);
}
