package com.nohtaehwan.rag.document;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * MarkdownParser가 만든 heading별 section을 Chunk 목록으로 분할한다.
 *
 * <p>정책:
 * <ul>
 *   <li>heading 경계를 우선 보존하고, 섹션이 목표 길이(targetLength)를 넘으면 문단 단위로 나눈다.</li>
 *   <li>fenced code block은 항상 원자 단위로 취급하며, targetLength를 넘어도 쪼개지 않는다(유일한 예외).</li>
 *   <li>코드 블록이 아닌 문단 하나가 그래도 targetLength를 넘으면 문자 단위로 최후 분할한다. 이때 적용되는
 *       overlap은 문자 단위 분할 내부에서만 한 번 적용되며, 이후 문단 packing 단계에서 중복 적용하지 않는다.</li>
 *   <li>overlap은 "직전 chunk 본문 끝에서부터 overlapLength만큼의 문자"를 의미하며, 그 구간에 포함된 줄바꿈도
 *       그대로 포함한다(줄바꿈을 별도로 제외하지 않음).</li>
 *   <li>fenced code block을 제외한 일반 Chunk는 overlap을 적용한 뒤에도 targetLength를 넘지 않는다. overlap을
 *       붙였을 때 targetLength를 넘게 되면 그 overlap은 적용하지 않는다(원본 문단 우선, overlap 생략).</li>
 *   <li>빈 Chunk는 만들지 않는다.</li>
 *   <li>minLength 미만인 Chunk는 같은 section 내 이전 Chunk에, 이전 Chunk가 없으면(section의 첫 Chunk인 경우)
 *       다음 Chunk에 병합한다. 병합 결과가 targetLength를 넘으면 병합하지 않고 그대로 둔다. fenced code block은
 *       병합 대상도, 병합받는 대상도 되지 않는다(항상 원자 단위 유지). 병합할 대상이 전혀 없으면(section에 Chunk가
 *       하나뿐이면) 짧더라도 그대로 남긴다. 병합은 같은 section 내에서만 일어난다.</li>
 * </ul>
 */
@Component
public class MarkdownChunker {

    private final DocumentProperties properties;
    private final LengthMeasurer lengthMeasurer;

    public MarkdownChunker(DocumentProperties properties, LengthMeasurer lengthMeasurer) {
        this.properties = properties;
        this.lengthMeasurer = lengthMeasurer;
    }

    /**
     * 문서를 Chunk 목록으로 분할한다. 동일한 입력에는 항상 동일한 결과(순서·개수·내용)를 반환한다.
     *
     * @param document MarkdownParser의 파싱 결과
     * @return document 등장 순서를 따르는 Chunk 목록. 빈 Chunk는 포함하지 않는다.
     */
    public List<DocumentChunk> chunk(DocumentContent document) {
        List<DocumentChunk> result = new ArrayList<>();
        int index = 0;
        for (DocumentContent.HeadingSection section : document.sections()) {
            for (String content : chunkSection(section.content())) {
                result.add(new DocumentChunk(
                        document.sourceKey(),
                        document.sourceKey() + "#" + index,
                        index,
                        section.headingPath(),
                        content,
                        lengthMeasurer.length(content)));
                index++;
            }
        }
        return result;
    }

    private List<String> chunkSection(String content) {
        int target = properties.chunk().targetLength();
        int overlap = properties.chunk().overlapLength();

        List<String> blocks = extractBlocks(content);
        if (blocks.isEmpty()) {
            return List.of();
        }

        return mergeShort(pack(blocks, target, overlap));
    }

    private List<String> extractBlocks(String content) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        FenceTracker fenceTracker = new FenceTracker();

        for (String line : content.split("\n", -1)) {
            boolean wasInFence = fenceTracker.isInFence();
            boolean isMarker = fenceTracker.isFenceLine(line);

            if (isMarker && !wasInFence) {
                if (!current.isEmpty()) {
                    blocks.add(current.toString());
                    current = new StringBuilder();
                }
                current.append(line).append('\n');
                continue;
            }
            if (isMarker) {
                current.append(line).append('\n');
                blocks.add(current.toString());
                current = new StringBuilder();
                continue;
            }
            if (fenceTracker.isInFence()) {
                current.append(line).append('\n');
                continue;
            }
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    blocks.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(line).append('\n');
            }
        }
        if (!current.isEmpty()) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    /**
     * 문단(및 fenced code block) 목록을 targetLength를 지키는 Chunk 목록으로 묶는다.
     * fenced code block은 원자 단위로 그대로 통과시키고, targetLength를 넘는 일반 블록은
     * 문자 단위로 먼저 분할해 최종 Chunk로 바로 추가한다(뒤 이은 overlap 중복 적용 없음).
     */
    private List<String> pack(List<String> blocks, int target, int overlap) {
        List<String> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String block : blocks) {
            if (isFence(block)) {
                flush(chunks, buffer);
                buffer = new StringBuilder();
                chunks.add(block.strip());
                continue;
            }

            if (lengthMeasurer.length(block) > target) {
                flush(chunks, buffer);
                buffer = new StringBuilder();
                chunks.addAll(splitByCharacters(block.strip(), target, overlap));
                continue;
            }

            String candidate = buffer.isEmpty() ? block : buffer + "\n\n" + block;
            if (buffer.isEmpty() || lengthMeasurer.length(candidate) <= target) {
                buffer = new StringBuilder(candidate);
                continue;
            }

            String finished = buffer.toString().strip();
            chunks.add(finished);
            String overlapTail = tail(finished, overlap);
            String next = overlapTail.isEmpty() ? block : overlapTail + "\n\n" + block;
            if (!overlapTail.isEmpty() && lengthMeasurer.length(next) > target) {
                // overlap을 붙이면 targetLength를 넘음 -> overlap을 생략하고 원본 블록만 사용한다.
                next = block;
            }
            buffer = new StringBuilder(next);
        }
        flush(chunks, buffer);
        return chunks;
    }

    private void flush(List<String> chunks, StringBuilder buffer) {
        if (!buffer.isEmpty()) {
            chunks.add(buffer.toString().strip());
        }
    }

    /**
     * minLength 미만인 Chunk를 같은 section 내 이웃 Chunk에 병합한다. 정책은 클래스 문서를 참고한다.
     */
    private List<String> mergeShort(List<String> rawChunks) {
        List<String> stripped = new ArrayList<>();
        for (String raw : rawChunks) {
            String trimmed = raw.strip();
            if (!trimmed.isEmpty()) {
                stripped.add(trimmed);
            }
        }
        if (stripped.size() <= 1) {
            return stripped;
        }

        int minLength = properties.chunk().minLength();
        int target = properties.chunk().targetLength();
        List<String> result = new ArrayList<>();

        for (String current : stripped) {
            if (!result.isEmpty()
                    && lengthMeasurer.length(current) < minLength
                    && !isFence(current)
                    && !isFence(result.get(result.size() - 1))) {
                String merged = result.get(result.size() - 1) + "\n\n" + current;
                if (lengthMeasurer.length(merged) <= target) {
                    result.set(result.size() - 1, merged);
                    continue;
                }
            }
            result.add(current);
        }

        if (result.size() > 1
                && lengthMeasurer.length(result.get(0)) < minLength
                && !isFence(result.get(0))
                && !isFence(result.get(1))) {
            String merged = result.get(0) + "\n\n" + result.get(1);
            if (lengthMeasurer.length(merged) <= target) {
                result.set(1, merged);
                result.remove(0);
            }
        }

        return result;
    }

    private List<String> splitByCharacters(String text, int target, int overlap) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + target, text.length());
            pieces.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return pieces;
    }

    private String tail(String text, int n) {
        if (n <= 0) {
            return "";
        }
        return text.length() <= n ? text : text.substring(text.length() - n);
    }

    private boolean isFence(String block) {
        int newlineIndex = block.indexOf('\n');
        String firstLine = newlineIndex >= 0 ? block.substring(0, newlineIndex) : block;
        return FenceTracker.looksLikeFenceMarker(firstLine);
    }
}
