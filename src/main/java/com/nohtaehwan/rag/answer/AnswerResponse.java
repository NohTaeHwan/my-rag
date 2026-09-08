package com.nohtaehwan.rag.answer;

import java.util.List;

/**
 * {@code POST /api/answers} 응답. 검색 근거가 없으면 오류가 아니라 고정 근거 부족 메시지와 빈
 * sources를 담는다.
 *
 * @param answer  LLM 최종 답변 텍스트(prompt 전문은 포함하지 않음)
 * @param sources 실제 prompt에 포함된 검색 결과만(null 아닌 목록, 없으면 빈 목록)
 */
public record AnswerResponse(String answer, List<AnswerSource> sources) {
}
