package org.dromara.heron.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendLearnStateTest {

    @Test
    void nullStateStartsAsSeen() {
        assertEquals(TrendLearnState.SEEN, TrendLearnState.of(null));
        assertEquals(TrendLearnState.SEEN, TrendLearnState.of(" "));
    }

    @Test
    void onlySeenAndFailedStatesCanBeSelectedAgain() {
        assertTrue(TrendLearnState.SEEN.retryable());
        assertTrue(TrendLearnState.DOWNLOAD_FAILED.retryable());
        assertTrue(TrendLearnState.DECOMPOSE_FAILED.retryable());
        assertTrue(TrendLearnState.LEARN_FAILED.retryable());
        assertFalse(TrendLearnState.SELECTED.retryable());
        assertFalse(TrendLearnState.DOWNLOADED.retryable());
        assertFalse(TrendLearnState.DECOMPOSED.retryable());
        assertFalse(TrendLearnState.LEARNED.retryable());
    }

    @Test
    void learnedIsTerminal() {
        assertTrue(TrendLearnState.LEARNED.terminal());
        assertFalse(TrendLearnState.LEARN_FAILED.terminal());
    }
}
