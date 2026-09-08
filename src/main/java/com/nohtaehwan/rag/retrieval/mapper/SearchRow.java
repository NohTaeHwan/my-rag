package com.nohtaehwan.rag.retrieval.mapper;

/**
 * {@link SearchMapper#search}가 반환하는 조회 결과 1행. 쓰기 없이 조회만 하는 순수 값이라 record로 둔다.
 * MyBatis 3.5.15+는 record를 결과 매핑 대상으로 정식 지원하며(생성자 파라미터명 기반), 컬럼명은
 * {@code mybatis.configuration.map-underscore-to-camel-case}로 자동 변환된다.
 */
public record SearchRow(
        Long documentId,
        String title,
        String source,
        String content,
        Integer chunkIndex,
        Double distance
) {
}
