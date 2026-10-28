package com.spotifyplusplus.lyrics.ai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

public class AiModelLiveStateTest {
    @After
    public void reset() {
        AiModelLiveState.invalidate();
    }

    @Test
    public void missingConfigurationNeverLooksLive() {
        // begin() now returns an Attempt (upstream v2.1.4), so a missing configuration is null.
        assertNull(AiModelLiveState.begin(null, false));
        assertNull(AiModelLiveState.begin(null, true));
        assertFalse(AiModelLiveState.isLive(null));
    }
}
