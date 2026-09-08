package com.nohtaehwan.rag.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.nohtaehwan.rag.retrieval.SearchResult;

class ContextBuilderTest {

    private final ContextBuilder contextBuilder = new ContextBuilder();

    private static SearchResult result(long id, String title, String source, String content, int index, double distance) {
        return new SearchResult(id, title, source, content, index, distance);
    }

    @Test
    void 검색_결과_순서와_메타데이터_content를_그대로_포함한다() {
        SearchResult a = result(1L, "문서A", "a.md", "내용A", 0, 0.1);
        SearchResult b = result(2L, "문서B", "b.md", "내용B", 1, 0.2);

        Context context = contextBuilder.build(List.of(a, b), 10_000);

        assertThat(context.includedResults()).containsExactly(a, b);
        assertThat(context.text())
                .contains("[검색 근거 1]", "title: 문서A", "source: a.md", "chunkIndex: 0", "내용A")
                .contains("[검색 근거 2]", "title: 문서B", "내용B");
        assertThat(context.text().indexOf("문서A")).isLessThan(context.text().indexOf("문서B"));
    }

    @Test
    void maxContextChars를_초과하면_그_뒤_결과부터_제외한다() {
        SearchResult a = result(1L, "A", "a.md", "content-a", 0, 0.1);
        SearchResult b = result(2L, "B", "b.md", "content-b", 1, 0.2);
        int lengthForAOnly = contextBuilder.build(List.of(a), Integer.MAX_VALUE).text().length();

        Context context = contextBuilder.build(List.of(a, b), lengthForAOnly);

        assertThat(context.includedResults()).containsExactly(a);
        assertThat(context.text()).doesNotContain("content-b");
    }

    @Test
    void 첫_결과가_한도를_초과하면_빈_context를_반환한다() {
        SearchResult a = result(1L, "A", "a.md", "content-a", 0, 0.1);
        int lengthForAOnly = contextBuilder.build(List.of(a), Integer.MAX_VALUE).text().length();

        Context context = contextBuilder.build(List.of(a), lengthForAOnly - 1);

        assertThat(context.text()).isEmpty();
        assertThat(context.includedResults()).isEmpty();
    }

    @Test
    void 검색_결과가_없으면_빈_context를_반환한다() {
        Context context = contextBuilder.build(List.of(), 10_000);

        assertThat(context.text()).isEmpty();
        assertThat(context.includedResults()).isEmpty();
    }
}
