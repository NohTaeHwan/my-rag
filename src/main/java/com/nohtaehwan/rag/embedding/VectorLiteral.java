package com.nohtaehwan.rag.embedding;

import java.util.List;
import java.util.stream.Collectors;

/**
 * embedding 벡터를 pgvector 리터럴 문자열({@code [0.1,0.2,...]})로 변환한다.
 * 색인(문서 Chunk 저장)과 검색(질문 embedding) 양쪽에서 공용으로 쓴다.
 */
public final class VectorLiteral {

    private VectorLiteral() {
    }

    public static String of(List<Double> embedding) {
        return embedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
