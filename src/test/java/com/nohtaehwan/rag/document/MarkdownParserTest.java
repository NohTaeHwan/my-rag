package com.nohtaehwan.rag.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * MarkdownParser의 title fallback, heading 계층, code fence 안전 처리, front matter 제외를 검증한다.
 */
class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    @Test
    void parse_extractsFirstH1AsTitle() {
        String raw = "# 주문 관리\n\n본문 내용";

        DocumentContent result = parser.parse("guide/order.md", raw);

        assertThat(result.title()).isEqualTo("주문 관리");
    }

    @Test
    void parse_fallsBackToFileNameTitle_whenNoH1() {
        String raw = "## 부제목만 있음\n\n본문";

        DocumentContent result = parser.parse("guide/order-cancel.md", raw);

        assertThat(result.title()).isEqualTo("order cancel");
    }

    @Test
    void parse_buildsNestedHeadingPathAndResetsOnHigherLevel() {
        String raw = """
                # 주문
                intro

                ## 생성
                생성 본문

                ### 검증
                검증 본문

                ## 취소
                취소 본문
                """;

        DocumentContent result = parser.parse("guide/order.md", raw);

        List<List<String>> paths = result.sections().stream()
                .map(DocumentContent.HeadingSection::headingPath)
                .toList();

        assertThat(paths).contains(
                List.of(),
                List.of("주문"),
                List.of("주문", "생성"),
                List.of("주문", "생성", "검증"),
                List.of("주문", "취소")
        );
    }

    @Test
    void parse_doesNotTreatHeadingInsideCodeFenceAsHeading() {
        String raw = """
                # 제목

                ```java
                // # 이건 heading이 아님
                class Foo {}
                ```

                ## 진짜 heading
                본문
                """;

        DocumentContent result = parser.parse("guide/code.md", raw);

        List<List<String>> paths = result.sections().stream()
                .map(DocumentContent.HeadingSection::headingPath)
                .toList();

        assertThat(paths).doesNotContain(List.of("제목", "이건 heading이 아님"));
        assertThat(paths).contains(List.of("제목", "진짜 heading"));

        String codeSection = result.sections().stream()
                .filter(s -> s.headingPath().equals(List.of("제목")))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(codeSection).contains("// # 이건 heading이 아님");
    }

    @Test
    void parse_mismatchedFenceCloseIsIgnored_headingInsideStillHidden() {
        String raw = """
                # 제목

                ```java
                ~~~
                ## fake heading (mismatched fence는 안 닫힘)
                ```

                ## 진짜 heading
                본문
                """;

        DocumentContent result = parser.parse("guide/mismatched-fence.md", raw);

        List<List<String>> paths = result.sections().stream()
                .map(DocumentContent.HeadingSection::headingPath)
                .toList();

        assertThat(paths).doesNotContain(List.of("제목", "fake heading (mismatched fence는 안 닫힘)"));
        assertThat(paths).contains(List.of("제목", "진짜 heading"));
    }

    @Test
    void parse_unclosedFence_suppressesHeadingsUntilEndOfDocument() {
        String raw = """
                # 제목

                ```java
                class Foo {}
                ## 닫히지 않은 fence 안이라 heading 아님
                """;

        DocumentContent result = parser.parse("guide/unclosed-fence.md", raw);

        List<List<String>> paths = result.sections().stream()
                .map(DocumentContent.HeadingSection::headingPath)
                .toList();

        assertThat(paths).doesNotContain(List.of("제목", "닫히지 않은 fence 안이라 heading 아님"));
        assertThat(result.sections()).anyMatch(s -> s.content().contains("class Foo {}"));
    }

    @Test
    void parse_excludesFrontMatterFromBody() {
        String raw = """
                ---
                title: 메타데이터
                draft: true
                ---
                # 실제 제목

                본문
                """;

        DocumentContent result = parser.parse("guide/front-matter.md", raw);

        assertThat(result.title()).isEqualTo("실제 제목");
        assertThat(result.sections()).noneMatch(s -> s.content().contains("draft: true"));
    }

    @Test
    void parse_unclosedFrontMatter_isTreatedAsRegularBody() {
        String raw = """
                ---
                title: 닫히지 않음
                # 실제 제목처럼 보이는 본문
                """;

        DocumentContent result = parser.parse("guide/unclosed-front-matter.md", raw);

        assertThat(result.title()).isEqualTo("실제 제목처럼 보이는 본문");
        assertThat(result.sections()).anyMatch(s -> s.content().contains("title: 닫히지 않음"));
    }
}
