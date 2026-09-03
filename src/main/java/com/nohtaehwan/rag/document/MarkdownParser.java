package com.nohtaehwan.rag.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Markdown 원문에서 title, heading 계층, 본문을 line-based로 추출한다.
 * 전체 CommonMark 문법을 구현하지 않으며, fenced code block(```, ~~~) 내부의
 * heading-like text(#으로 시작하는 줄)는 heading으로 인식하지 않는다.
 */
@Component
public class MarkdownParser {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern FRONT_MATTER_DELIMITER = Pattern.compile("^-{3}\\s*$");

    /**
     * Markdown 원문을 파싱해 title과 heading별 section 목록을 만든다.
     *
     * @param sourceKey  base directory 기준 정규화된 상대 경로 (title fallback에 사용)
     * @param rawContent 파일 원문 (UTF-8로 이미 읽은 문자열)
     * @return 파싱 결과. 빈 section도 포함될 수 있으며, 빈/짧은 section 제거는 MarkdownChunker의 책임이다.
     */
    public DocumentContent parse(String sourceKey, String rawContent) {
        String[] lines = normalize(rawContent).split("\n", -1);
        int start = skipFrontMatter(lines);

        List<DocumentContent.HeadingSection> sections = new ArrayList<>();
        String[] headingStack = new String[7];
        List<String> currentPath = List.of();
        StringBuilder buffer = new StringBuilder();
        String title = null;
        FenceTracker fenceTracker = new FenceTracker();

        for (int i = start; i < lines.length; i++) {
            String line = lines[i];

            if (fenceTracker.isFenceLine(line)) {
                buffer.append(line).append('\n');
                continue;
            }

            Matcher headingMatcher = fenceTracker.isInFence() ? null : HEADING_PATTERN.matcher(line);
            if (headingMatcher != null && headingMatcher.matches()) {
                sections.add(new DocumentContent.HeadingSection(currentPath, buffer.toString()));
                buffer = new StringBuilder();

                int level = headingMatcher.group(1).length();
                String text = headingMatcher.group(2);
                headingStack[level] = text;
                for (int l = level + 1; l <= 6; l++) {
                    headingStack[l] = null;
                }
                if (title == null && level == 1) {
                    title = text;
                }
                currentPath = buildPath(headingStack, level);
                continue;
            }

            buffer.append(line).append('\n');
        }
        sections.add(new DocumentContent.HeadingSection(currentPath, buffer.toString()));

        if (title == null) {
            title = titleFromSourceKey(sourceKey);
        }

        return new DocumentContent(sourceKey, title, sections);
    }

    private static String normalize(String rawContent) {
        return rawContent.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static int skipFrontMatter(String[] lines) {
        if (lines.length == 0 || !FRONT_MATTER_DELIMITER.matcher(lines[0]).matches()) {
            return 0;
        }
        for (int i = 1; i < lines.length; i++) {
            if (FRONT_MATTER_DELIMITER.matcher(lines[i]).matches()) {
                return i + 1;
            }
        }
        return 0;
    }

    private static List<String> buildPath(String[] headingStack, int level) {
        List<String> path = new ArrayList<>();
        for (int l = 1; l <= level; l++) {
            if (headingStack[l] != null) {
                path.add(headingStack[l]);
            }
        }
        return List.copyOf(path);
    }

    private static String titleFromSourceKey(String sourceKey) {
        String fileName = sourceKey.substring(sourceKey.lastIndexOf('/') + 1);
        String withoutExtension = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        return withoutExtension.replace('-', ' ').replace('_', ' ');
    }
}
