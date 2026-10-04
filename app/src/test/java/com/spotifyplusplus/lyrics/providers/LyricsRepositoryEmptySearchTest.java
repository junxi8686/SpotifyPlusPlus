package com.spotifyplusplus.lyrics.providers;

import static org.junit.Assert.assertEquals;

import com.spotifyplusplus.lyrics.catalog.AcquisitionPlanner;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.ProviderStatus;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.catalog.ProviderFailureClassifier;
import com.spotifyplusplus.lyrics.catalog.ProviderRecord;
import org.junit.Test;

public class LyricsRepositoryEmptySearchTest {
    @Test public void successfulEmptySearchGetsTheDurableAbsenceHorizon() {
        String error = LyricsRepository.lrclibEmptyResponseError("strict LRCLIB", 200, true);
        ProviderStatus status = ProviderFailureClassifier.classify(SourceId.LRCLIB, error);
        assertEquals(ProviderStatus.NOT_FOUND, status);
        ProviderRecord outcome = ProviderRecord.notChecked(SourceId.LRCLIB).failed(status, 1000L);
        assertEquals(1000L + AcquisitionPlanner.NOT_FOUND_RETRY_MS,
                AcquisitionPlanner.dueAtMs(outcome, false));
    }

    @Test public void serverFailureAndMissingBodyRemainTransient() {
        assertEquals(ProviderStatus.TRANSIENT_ERROR, ProviderFailureClassifier.classify(SourceId.LRCLIB,
                LyricsRepository.lrclibEmptyResponseError("strict LRCLIB", 503, false)));
        assertEquals(ProviderStatus.TRANSIENT_ERROR, ProviderFailureClassifier.classify(SourceId.LRCLIB,
                LyricsRepository.lrclibEmptyResponseError("strict LRCLIB", 200, false)));
    }
}
