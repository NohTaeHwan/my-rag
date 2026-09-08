package com.nohtaehwan.rag.answer;

import java.util.List;

import com.nohtaehwan.rag.retrieval.SearchResult;

/**
 * {@link ContextBuilder}가 만든 prompt용 근거 context.
 *
 * @param text            LLM prompt에 삽입할 근거 텍스트(비어 있으면 근거 부족)
 * @param includedResults text에 실제로 포함된 검색 결과(순서 유지, {@code AnswerResponse.sources} 구성에 사용)
 */
public record Context(String text, List<SearchResult> includedResults) {
}
