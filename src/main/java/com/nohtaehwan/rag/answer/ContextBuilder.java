package com.nohtaehwan.rag.answer;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.nohtaehwan.rag.retrieval.SearchResult;

/**
 * 검색 결과를 LLM prompt에 넣을 근거 context로 구성한다. distance 오름차순(검색 결과 순서)을 유지하며,
 * 다음 결과를 추가했을 때 maxContextChars를 넘기면 그 결과부터(그리고 그 뒤 전부) 제외한다 — 결과를
 * 잘라서 일부만 넣지 않는다. 첫 결과부터 넘치면 빈 context를 반환한다.
 */
@Component
public class ContextBuilder {

    private static final String BLOCK_SEPARATOR = "\n\n";

    public Context build(List<SearchResult> results, int maxContextChars) {
        StringBuilder text = new StringBuilder();
        List<SearchResult> included = new ArrayList<>();

        for (SearchResult result : results) {
            String block = formatBlock(included.size() + 1, result);
            int separatorLength = text.isEmpty() ? 0 : BLOCK_SEPARATOR.length();
            if (text.length() + separatorLength + block.length() > maxContextChars) {
                break;
            }
            if (!text.isEmpty()) {
                text.append(BLOCK_SEPARATOR);
            }
            text.append(block);
            included.add(result);
        }

        return new Context(text.toString(), included);
    }

    private String formatBlock(int index, SearchResult result) {
        return """
                [검색 근거 %d]
                title: %s
                source: %s
                chunkIndex: %d
                content:
                %s""".formatted(index, result.title(), result.source(), result.chunkIndex(), result.content());
    }
}
