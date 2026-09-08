package com.nohtaehwan.rag.llm;

/**
 * {@link LlmClient}에 전달하는 system/user 메시지 쌍. OpenAI-compatible request 형식 등
 * 특정 provider의 payload 구조는 이 타입 뒤에 숨긴다.
 */
public record Prompt(String systemMessage, String userMessage) {
}
