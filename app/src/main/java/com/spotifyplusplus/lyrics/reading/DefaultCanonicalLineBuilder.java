package com.spotifyplusplus.lyrics.reading;

import com.spotifyplusplus.lyrics.reading.ReadingContracts.CanonicalLineBuilder;
import com.spotifyplusplus.lyrics.reading.ReadingModels.CanonicalLine;
import com.spotifyplusplus.lyrics.reading.ReadingModels.ParsedLine;

public final class DefaultCanonicalLineBuilder implements CanonicalLineBuilder {
    private final ProviderBoundaryResolver resolver = new ProviderBoundaryResolver();

    @Override
    public CanonicalLine build(ParsedLine line) {
        return resolver.resolve(line).canonical;
    }
}
