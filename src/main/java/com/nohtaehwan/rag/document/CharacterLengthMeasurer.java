package com.nohtaehwan.rag.document;

import org.springframework.stereotype.Component;

/**
 * 문자 수(code point 기준) 근사치로 길이를 측정한다. 실제 BGE-M3 토큰 수가 아니다.
 */
@Component
public class CharacterLengthMeasurer implements LengthMeasurer {

    @Override
    public int length(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }
}
