package com.nohtaehwan.rag.indexing.mapper;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * tb_document insert용 파라미터. MyBatis가 {@code useGeneratedKeys}로 생성된 id를
 * 다시 채워 넣어야 하므로(record는 불가) 일반 클래스로 둔다.
 *
 * <p>{@code userId}/{@code knowledgeBaseId}는 V3 migration 이후 필수 컬럼이다(NOT NULL).
 */
@Getter
@RequiredArgsConstructor
public class DocumentInsertParameter {

    private final Long userId;
    private final Long knowledgeBaseId;
    private final String title;
    private final String source;

    @Setter
    private Long id;
}
