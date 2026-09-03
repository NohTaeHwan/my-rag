package com.nohtaehwan.rag.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * FenceTracker의 fence open/close 판정 정책을 검증한다.
 */
class FenceTrackerTest {

    @Test
    void backtickFence_opensAndCloses() {
        FenceTracker tracker = new FenceTracker();

        assertThat(tracker.isFenceLine("```java")).isTrue();
        assertThat(tracker.isInFence()).isTrue();

        assertThat(tracker.isFenceLine("code line")).isFalse();
        assertThat(tracker.isInFence()).isTrue();

        assertThat(tracker.isFenceLine("```")).isTrue();
        assertThat(tracker.isInFence()).isFalse();
    }

    @Test
    void tildeFence_opensAndCloses() {
        FenceTracker tracker = new FenceTracker();

        assertThat(tracker.isFenceLine("~~~python")).isTrue();
        assertThat(tracker.isInFence()).isTrue();

        assertThat(tracker.isFenceLine("~~~")).isTrue();
        assertThat(tracker.isInFence()).isFalse();
    }

    @Test
    void mismatchedFenceType_doesNotClose() {
        FenceTracker tracker = new FenceTracker();

        tracker.isFenceLine("```java");
        assertThat(tracker.isInFence()).isTrue();

        assertThat(tracker.isFenceLine("~~~")).isFalse();
        assertThat(tracker.isInFence()).isTrue();
    }

    @Test
    void shorterClosingFence_doesNotClose() {
        FenceTracker tracker = new FenceTracker();

        tracker.isFenceLine("````");
        assertThat(tracker.isInFence()).isTrue();

        assertThat(tracker.isFenceLine("```")).isFalse();
        assertThat(tracker.isInFence()).isTrue();
    }

    @Test
    void longerOrEqualClosingFence_closes() {
        FenceTracker tracker = new FenceTracker();

        tracker.isFenceLine("```");
        assertThat(tracker.isFenceLine("````")).isTrue();
        assertThat(tracker.isInFence()).isFalse();
    }

    @Test
    void closingFenceWithTrailingText_doesNotClose() {
        FenceTracker tracker = new FenceTracker();

        tracker.isFenceLine("```java");
        assertThat(tracker.isFenceLine("``` not a real close")).isFalse();
        assertThat(tracker.isInFence()).isTrue();
    }

    @Test
    void unclosedFence_staysInFenceUntilEnd() {
        FenceTracker tracker = new FenceTracker();

        tracker.isFenceLine("```java");
        tracker.isFenceLine("class Foo {}");
        tracker.isFenceLine("# 이건 heading이 아님");

        assertThat(tracker.isInFence()).isTrue();
    }

    @Test
    void looksLikeFenceMarker_matchesFenceLinesOnly() {
        assertThat(FenceTracker.looksLikeFenceMarker("```java")).isTrue();
        assertThat(FenceTracker.looksLikeFenceMarker("~~~")).isTrue();
        assertThat(FenceTracker.looksLikeFenceMarker("일반 문단")).isFalse();
    }
}
