package com.nohtaehwan.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nohtaehwan.rag.exception.RagException;

/**
 * DocumentSourceReader의 파일 수집 정책(재귀 탐색, 정렬, 필터, 실패 처리)을 검증한다.
 */
class DocumentSourceReaderTest {

    @TempDir
    Path tempDir;

    private DocumentProperties properties(Path base) {
        return new DocumentProperties(base.toString(), new DocumentProperties.Chunk(1200, 150, 80));
    }

    private void write(Path base, String relativePath, String content) throws IOException {
        Path file = base.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void readAll_returnsEmptyList_whenDirectoryMissing() {
        Path missing = tempDir.resolve("does-not-exist");
        DocumentSourceReader reader = new DocumentSourceReader(properties(missing));

        assertThat(reader.readAll()).isEmpty();
    }

    @Test
    void readAll_collectsMarkdownFilesRecursivelyInSortedOrder() throws IOException {
        write(tempDir, "b.md", "b content");
        write(tempDir, "a.md", "a content");
        write(tempDir, "sub/c.md", "c content");

        DocumentSourceReader reader = new DocumentSourceReader(properties(tempDir));

        List<SourceFile> files = reader.readAll();

        assertThat(files).extracting(SourceFile::sourceKey)
                .containsExactly("a.md", "b.md", "sub/c.md");
        assertThat(files.get(0).content()).isEqualTo("a content");
    }

    @Test
    void readAll_ignoresNonMarkdownFiles() throws IOException {
        write(tempDir, "note.md", "note");
        write(tempDir, "readme.txt", "not markdown");

        DocumentSourceReader reader = new DocumentSourceReader(properties(tempDir));

        assertThat(reader.readAll()).extracting(SourceFile::sourceKey).containsExactly("note.md");
    }

    @Test
    void readAll_ignoresHiddenFilesAndDirectories() throws IOException {
        write(tempDir, "visible.md", "visible");
        write(tempDir, ".hidden.md", "hidden file");
        write(tempDir, ".git/config.md", "hidden dir");

        DocumentSourceReader reader = new DocumentSourceReader(properties(tempDir));

        assertThat(reader.readAll()).extracting(SourceFile::sourceKey).containsExactly("visible.md");
    }

    @Test
    void readAll_ignoresSymlinks() throws IOException {
        write(tempDir, "real.md", "real content");
        Path link = tempDir.resolve("link.md");
        try {
            Files.createSymbolicLink(link, tempDir.resolve("real.md"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }

        DocumentSourceReader reader = new DocumentSourceReader(properties(tempDir));

        assertThat(reader.readAll()).extracting(SourceFile::sourceKey).containsExactly("real.md");
    }

    @Test
    void readAll_throwsRagException_whenFileHasInvalidUtf8() throws IOException {
        Path file = tempDir.resolve("broken.md");
        Files.write(file, new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0x00});

        DocumentSourceReader reader = new DocumentSourceReader(properties(tempDir));

        assertThatThrownBy(reader::readAll).isInstanceOf(RagException.class);
    }

    @Test
    void readAll_returnsEmptyList_whenDirectoryExistsButIsEmpty() throws IOException {
        Files.createDirectories(tempDir);

        DocumentSourceReader reader = new DocumentSourceReader(properties(tempDir));

        assertThat(reader.readAll()).isEmpty();
    }

    @Test
    void readAll_extensionMatchIsCaseSensitive_uppercaseMdIgnored() throws IOException {
        write(tempDir, "lower.md", "matched");
        write(tempDir, "upper.MD", "not matched");
        write(tempDir, "mixed.Md", "not matched");

        DocumentSourceReader reader = new DocumentSourceReader(properties(tempDir));

        assertThat(reader.readAll()).extracting(SourceFile::sourceKey).containsExactly("lower.md");
    }

    @Test
    void readAll_stripsLeadingUtf8Bom() throws IOException {
        Path file = tempDir.resolve("bom.md");
        Files.writeString(file, "﻿# 제목\n본문");

        DocumentSourceReader reader = new DocumentSourceReader(properties(tempDir));

        List<SourceFile> files = reader.readAll();

        assertThat(files).hasSize(1);
        assertThat(files.get(0).content()).doesNotContain("﻿").startsWith("# 제목");
    }
}
