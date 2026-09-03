package com.nohtaehwan.rag.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * MarkdownChunker의 분할 크기, overlap, targetLength invariant, code fence 원자성,
 * 빈 Chunk 금지, 최소 길이 병합, 결정성을 검증한다.
 */
class MarkdownChunkerTest {

    private final LengthMeasurer measurer = new CharacterLengthMeasurer();

    private DocumentProperties properties(int target, int overlap, int min) {
        return new DocumentProperties("data/markdown", new DocumentProperties.Chunk(target, overlap, min));
    }

    private DocumentContent singleSection(String content) {
        return new DocumentContent("doc.md", "doc",
                List.of(new DocumentContent.HeadingSection(List.of("H"), content)));
    }

    private boolean isFenceChunk(String content) {
        String stripped = content.strip();
        return stripped.startsWith("```") || stripped.startsWith("~~~");
    }

    @Test
    void chunk_returnsSingleChunk_whenSectionShorterThanTarget() {
        MarkdownChunker chunker = new MarkdownChunker(properties(100, 10, 5), measurer);

        List<DocumentChunk> chunks = chunker.chunk(singleSection("짧은 본문입니다."));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("짧은 본문입니다.");
        assertThat(chunks.get(0).chunkKey()).isEqualTo("doc.md#0");
    }

    @Test
    void chunk_splitsLongSectionAndAppliesOverlapBetweenChunks() {
        String paragraphA = "a".repeat(60);
        String paragraphB = "b".repeat(60);
        String content = paragraphA + "\n\n" + paragraphB;

        MarkdownChunker chunker = new MarkdownChunker(properties(90, 10, 5), measurer);

        List<DocumentChunk> chunks = chunker.chunk(singleSection(content));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks).allMatch(c -> c.content().length() <= 90);
        String firstTail = chunks.get(0).content().substring(chunks.get(0).content().length() - 10);
        assertThat(chunks.get(1).content()).startsWith(firstTail);
    }

    @Test
    void chunk_generalChunksNeverExceedTargetLength_forVeryLongParagraphWithOverlap() {
        String content = "x".repeat(500);

        MarkdownChunker chunker = new MarkdownChunker(properties(100, 20, 5), measurer);

        List<DocumentChunk> chunks = chunker.chunk(singleSection(content));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allMatch(c -> c.content().length() <= 100);
    }

    @Test
    void chunk_generalChunksNeverExceedTargetLength_forMultipleNearTargetParagraphs() {
        String p1 = "a".repeat(90);
        String p2 = "b".repeat(90);
        String p3 = "c".repeat(90);
        String p4 = "d".repeat(90);
        String content = String.join("\n\n", p1, p2, p3, p4);

        MarkdownChunker chunker = new MarkdownChunker(properties(100, 15, 5), measurer);

        List<DocumentChunk> chunks = chunker.chunk(singleSection(content));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(4);
        assertThat(chunks).allMatch(c -> c.content().length() <= 100);
    }

    @Test
    void chunk_splitByCharactersAppliesOverlapExactlyOnce_notDoubled() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            text.append((char) ('a' + i % 26));
        }
        String original = text.toString();

        MarkdownChunker chunker = new MarkdownChunker(properties(50, 10, 5), measurer);

        List<DocumentChunk> chunks = chunker.chunk(singleSection(original));

        assertThat(chunks).extracting(DocumentChunk::content).containsExactly(
                original.substring(0, 50),
                original.substring(40, 90),
                original.substring(80, 130),
                original.substring(120, 150)
        );
        assertThat(chunks).allMatch(c -> c.content().length() <= 50);
    }

    @Test
    void chunk_keepsFencedCodeBlockAtomic_evenWhenOversized_whileOtherChunksRespectTarget() {
        String bigCode = "line\n".repeat(30);
        String content = "설명\n\n```java\n" + bigCode + "```\n\n" + "일반 문단 ".repeat(3);

        MarkdownChunker chunker = new MarkdownChunker(properties(20, 5, 5), measurer);

        List<DocumentChunk> chunks = chunker.chunk(singleSection(content));

        List<DocumentChunk> codeChunks = chunks.stream().filter(c -> isFenceChunk(c.content())).toList();
        List<DocumentChunk> normalChunks = chunks.stream().filter(c -> !isFenceChunk(c.content())).toList();

        assertThat(codeChunks).hasSize(1);
        assertThat(codeChunks.get(0).content()).contains(bigCode);
        assertThat(codeChunks.get(0).content().length()).isGreaterThan(20);
        assertThat(normalChunks).isNotEmpty();
        assertThat(normalChunks).allMatch(c -> c.content().length() <= 20);
    }

    @Test
    void chunk_doesNotSplitCodeBlockOnMismatchedInnerFenceLine() {
        String content = "```java\n~~~\nclass Foo {}\n```\n";

        MarkdownChunker chunker = new MarkdownChunker(properties(10, 2, 2), measurer);

        List<DocumentChunk> chunks = chunker.chunk(singleSection(content));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("~~~").contains("class Foo {}");
        assertThat(isFenceChunk(chunks.get(0).content())).isTrue();
    }

    @Test
    void chunk_neverProducesEmptyChunks() {
        DocumentContent document = new DocumentContent("doc.md", "doc", List.of(
                new DocumentContent.HeadingSection(List.of("빈 섹션"), "   \n\n  "),
                new DocumentContent.HeadingSection(List.of("실제 섹션"), "실제 본문")
        ));

        MarkdownChunker chunker = new MarkdownChunker(properties(100, 10, 5), measurer);

        List<DocumentChunk> chunks = chunker.chunk(document);

        assertThat(chunks).noneMatch(c -> c.content().isBlank());
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isEqualTo(List.of("실제 섹션"));
    }

    @Test
    void mergeShort_lastChunkShort_mergesBackwardIntoPrevious() {
        String longParagraph = "a".repeat(45);
        String shortParagraph = "bb";
        String content = longParagraph + "\n\n" + shortParagraph;

        MarkdownChunker chunker = new MarkdownChunker(properties(50, 0, 10), measurer);

        List<DocumentChunk> chunks = chunker.chunk(singleSection(content));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains(longParagraph).contains(shortParagraph);
    }

    @Test
    void mergeShort_firstChunkShort_mergesForwardIntoNext() {
        String content = "hi" + "\n\n" + "b".repeat(46);

        MarkdownChunker chunker = new MarkdownChunker(properties(50, 0, 10), measurer);

        List<DocumentChunk> chunks = chunker.chunk(singleSection(content));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("hi").contains("b".repeat(46));
        assertThat(chunks.get(0).content().length()).isLessThanOrEqualTo(50);
    }

    @Test
    void mergeShort_onlyShortChunkInSection_staysStandalone() {
        MarkdownChunker chunker = new MarkdownChunker(properties(100, 0, 20), measurer);

        List<DocumentChunk> chunks = chunker.chunk(singleSection("hi"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("hi");
    }

    @Test
    void mergeShort_doesNotMergeAcrossSectionBoundaries() {
        DocumentContent document = new DocumentContent("doc.md", "doc", List.of(
                new DocumentContent.HeadingSection(List.of("섹션 A"), "hi"),
                new DocumentContent.HeadingSection(List.of("섹션 B"), "yo")
        ));

        MarkdownChunker chunker = new MarkdownChunker(properties(100, 0, 20), measurer);

        List<DocumentChunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).isEqualTo("hi");
        assertThat(chunks.get(0).headingPath()).isEqualTo(List.of("섹션 A"));
        assertThat(chunks.get(1).content()).isEqualTo("yo");
        assertThat(chunks.get(1).headingPath()).isEqualTo(List.of("섹션 B"));
    }

    @Test
    void chunk_isDeterministic_forSameInput() {
        String content = "a".repeat(120) + "\n\n" + "b".repeat(80);
        MarkdownChunker chunker = new MarkdownChunker(properties(60, 15, 5), measurer);
        DocumentContent document = singleSection(content);

        List<DocumentChunk> first = chunker.chunk(document);
        List<DocumentChunk> second = chunker.chunk(document);

        assertThat(first).isEqualTo(second);
    }
}
