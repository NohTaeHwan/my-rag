package com.nohtaehwan.rag.indexing.mapper;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * tb_document insert용 파라미터. MyBatis가 {@code useGeneratedKeys}로 생성된 id를
 * 다시 채워 넣어야 하므로(record는 불가) 일반 클래스로 둔다.
 */
@Getter
@RequiredArgsConstructor
public class DocumentInsertParameter {

    private final String title;
    private final String source;

    @Setter
    private Long id;
}
