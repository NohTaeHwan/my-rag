package com.nohtaehwan.rag.document;

/**
 * 수집된 Markdown 원본 파일 하나를 표현한다.
 *
 * @param sourceKey base directory 기준 정규화된 상대 경로 (예: "guide/order.md"). 절대 경로를 노출하지 않는다.
 * @param content   UTF-8로 읽은 파일 본문
 */
public record SourceFile(String sourceKey, String content) {
}
