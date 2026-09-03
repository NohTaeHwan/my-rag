package com.nohtaehwan.rag.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 디렉터리 수집 → 파싱 → Chunk 생성까지 전체 흐름을 Spring context 없이 검증한다.
 * stage 4는 여기까지만 다루며, Embedding 호출·DB 저장은 하지 않는다.
 */
class DocumentPipelineIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void pipeline_readsParsesAndChunksMarkdownFilesDeterministically() throws IOException {
        Files.writeString(tempDir.resolve("order.md"), """
                # 주문 관리

                ## 주문 생성
                %s

                ## 주문 취소
                결제 완료 후 주문을 취소하려면 고객센터에 문의합니다.
                """.formatted("주문 생성 절차 설명. ".repeat(20)));

        DocumentProperties properties = new DocumentProperties(
                tempDir.toString(), new DocumentProperties.Chunk(200, 30, 20));
        DocumentSourceReader reader = new DocumentSourceReader(properties);
        MarkdownParser parser = new MarkdownParser();
        MarkdownChunker chunker = new MarkdownChunker(properties, new CharacterLengthMeasurer());

        List<SourceFile> files = reader.readAll();
        assertThat(files).hasSize(1);

        SourceFile file = files.get(0);
        DocumentContent parsed = parser.parse(file.sourceKey(), file.content());
        List<DocumentChunk> chunks = chunker.chunk(parsed);

        assertThat(parsed.title()).isEqualTo("주문 관리");
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).noneMatch(c -> c.content().isBlank());
        assertThat(chunks).allMatch(c -> c.documentSourceKey().equals("order.md"));
        assertThat(chunks.get(0).headingPath()).isEqualTo(List.of("주문 관리", "주문 생성"));

        List<DocumentChunk> secondRun = chunker.chunk(parser.parse(file.sourceKey(), file.content()));
        assertThat(chunks).isEqualTo(secondRun);
    }

    @Test
    void pipeline_returnsNoChunks_whenSourceDirectoryIsEmpty() {
        DocumentProperties properties = new DocumentProperties(
                tempDir.toString(), new DocumentProperties.Chunk(200, 30, 20));
        DocumentSourceReader reader = new DocumentSourceReader(properties);

        assertThat(reader.readAll()).isEmpty();
    }
}
