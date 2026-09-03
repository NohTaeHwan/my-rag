package com.nohtaehwan.rag.document;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * fenced code block(``` 또는 ~~~) 상태를 한 줄씩 추적하는 상태 머신.
 * MarkdownParser와 MarkdownChunker가 동일한 fence 판정 정책을 공유하기 위해 분리했다.
 *
 * <p>판정 규칙:
 * <ul>
 *   <li>여는 fence의 종류(backtick/tilde)와 길이를 기억한다.</li>
 *   <li>닫는 fence는 같은 종류이고 길이가 여는 fence 이상이어야 인정한다.</li>
 *   <li>닫는 fence 줄에 fence marker 외의 텍스트가 있으면 닫는 것으로 인정하지 않는다
 *       (여는 fence의 info string, 예: {@code ```java}는 허용한다).</li>
 *   <li>문서 끝까지 닫히지 않은 fence는 끝까지 fence 내부로 취급한다.</li>
 * </ul>
 * 이 클래스는 한 번의 파싱/추출 호출마다 새 인스턴스를 사용해야 한다(상태를 재사용하지 않음).
 */
final class FenceTracker {

    private static final Pattern FENCE_LINE = Pattern.compile("^(`{3,}|~{3,})(.*)$");

    private char openChar;
    private int openLength;
    private boolean inFence;

    /**
     * 한 줄을 처리해 fence 상태를 갱신한다.
     *
     * @param line 원본 한 줄 (strip 전)
     * @return 이 줄이 유효한 fence marker(여는 줄 또는 정상적으로 닫는 줄)이면 true.
     *         fence-like 하지만 실제로 닫는 조건을 만족하지 못하면 false를 반환하고
     *         fence 내부 상태는 유지된다(호출자는 이 줄을 fence 본문으로 취급해야 한다).
     */
    boolean isFenceLine(String line) {
        Matcher matcher = FENCE_LINE.matcher(line.strip());
        if (!matcher.matches()) {
            return false;
        }

        String marker = matcher.group(1);
        String trailing = matcher.group(2);

        if (!inFence) {
            openChar = marker.charAt(0);
            openLength = marker.length();
            inFence = true;
            return true;
        }

        boolean sameType = marker.charAt(0) == openChar;
        boolean longEnough = marker.length() >= openLength;
        boolean noTrailingText = trailing.isBlank();
        if (sameType && longEnough && noTrailingText) {
            inFence = false;
            return true;
        }
        return false;
    }

    /**
     * 가장 최근 {@link #isFenceLine(String)} 호출 이후의 fence 내부 여부.
     */
    boolean isInFence() {
        return inFence;
    }

    /**
     * 주어진 한 줄이 fence marker 형태(backtick/tilde 3개 이상으로 시작)인지만 확인한다.
     * 상태를 갖지 않는 순수 판정으로, 이미 만들어진 블록의 첫 줄이 fence인지 확인할 때 사용한다.
     */
    static boolean looksLikeFenceMarker(String line) {
        return FENCE_LINE.matcher(line.strip()).matches();
    }
}
