package com.nohtaehwan.rag.document;

import java.util.List;

/**
 * MarkdownParser의 파싱 결과. 문서를 heading 계층별 section으로 나눈다.
 *
 * @param sourceKey base directory 기준 정규화된 상대 경로
 * @param title     첫 번째 H1 텍스트 또는(없으면) 파일명 기반 fallback
 * @param sections  heading 경로별 본문 section 목록 (문서에 등장한 순서 그대로)
 */
public record DocumentContent(String sourceKey, String title, List<HeadingSection> sections) {

    /**
     * @param headingPath 최상위(H1)부터 현재 heading까지의 텍스트 경로. heading이 아직 없으면 빈 목록.
     * @param content     이 heading 아래, 다음 heading 전까지의 원본 본문(줄바꿈 포함)
     */
    public record HeadingSection(List<String> headingPath, String content) {
    }
}
