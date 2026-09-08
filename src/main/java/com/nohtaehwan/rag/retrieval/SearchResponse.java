package com.nohtaehwan.rag.retrieval;

import java.util.List;

/**
 * 검색 API 응답. 결과가 없으면 오류가 아니라 빈 목록을 담는다.
 *
 * @param results distance 오름차순 검색 결과
 */
public record SearchResponse(List<SearchResult> results) {
}
