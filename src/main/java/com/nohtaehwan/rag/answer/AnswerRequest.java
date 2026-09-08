package com.nohtaehwan.rag.answer;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/answers} 요청 본문.
 *
 * @param question 사용자 질문. null·빈 문자열·공백만 있으면 400.
 */
public record AnswerRequest(@NotBlank String question) {
}
