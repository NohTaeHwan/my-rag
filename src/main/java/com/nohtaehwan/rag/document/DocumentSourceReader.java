package com.nohtaehwan.rag.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.nohtaehwan.rag.exception.RagException;

import lombok.extern.slf4j.Slf4j;

/**
 * 설정된 디렉터리에서 Markdown(.md) 파일을 결정적 순서로 안전하게 수집한다.
 * 심볼릭 링크와 숨김 파일/디렉터리는 제외하고, 읽기 실패는 조용히 건너뛰지 않고 예외로 알린다.
 *
 * <p>확장자 비교는 대소문자를 구분한다(소문자 {@code .md}만 인식). OS의 파일시스템 대소문자
 * 구분 여부와 무관하게 어떤 환경에서도 동일한 결과를 내기 위한 결정이다.
 * 읽은 내용 맨 앞에 UTF-8 BOM이 있으면 제거한다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentSourceReader {

    private static final String MARKDOWN_EXTENSION = ".md";
    private static final char BOM = '\uFEFF';

    private final DocumentProperties properties;

    public DocumentSourceReader(DocumentProperties properties) {
        this.properties = properties;
    }

    /**
     * 설정된 디렉터리 하위의 모든 Markdown 파일을 정규화된 상대 경로 오름차순으로 읽는다.
     *
     * @return 수집된 파일 목록. 디렉터리가 없으면 빈 목록을 반환한다(아직 색인할 문서가 없는 정상 상태로 취급).
     * @throws RagException 디렉터리 탐색 또는 파일 읽기 실패 시 발생. 실패를 조용히 건너뛰지 않는다.
     */
    public List<SourceFile> readAll() {
        Path base = Path.of(properties.sourceDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            log.warn("Markdown 소스 디렉터리가 없음: {}", base);
            return List.of();
        }

        List<Path> paths;
        try (Stream<Path> walk = Files.walk(base)) {
            paths = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !isHidden(base, path))
                    .filter(path -> path.toString().endsWith(MARKDOWN_EXTENSION))
                    .sorted(Comparator.comparing(path -> relativeKey(base, path)))
                    .toList();
        } catch (IOException e) {
            throw new RagException("Markdown 소스 디렉터리를 탐색하지 못했습니다: " + base, e);
        }

        return paths.stream()
                .map(path -> readFile(base, path))
                .toList();
    }

    private SourceFile readFile(Path base, Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (!content.isEmpty() && content.charAt(0) == BOM) {
                content = content.substring(1);
            }
            return new SourceFile(relativeKey(base, path), content);
        } catch (IOException e) {
            throw new RagException("Markdown 파일을 읽지 못했습니다: " + relativeKey(base, path), e);
        }
    }

    private static boolean isHidden(Path base, Path path) {
        for (Path segment : base.relativize(path)) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String relativeKey(Path base, Path path) {
        return base.relativize(path).toString().replace('\\', '/');
    }
}
