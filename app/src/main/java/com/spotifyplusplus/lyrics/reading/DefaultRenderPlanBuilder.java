package com.spotifyplusplus.lyrics.reading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.spotifyplusplus.lyrics.reading.ReadingContracts.RenderPlanBuilder;
import com.spotifyplusplus.lyrics.reading.ReadingModels.CanonicalLine;
import com.spotifyplusplus.lyrics.reading.ReadingModels.ParsedLine;
import com.spotifyplusplus.lyrics.reading.ReadingModels.ReadingAnnotation;
import com.spotifyplusplus.lyrics.reading.ReadingModels.ReadingUnit;
import com.spotifyplusplus.lyrics.reading.ReadingModels.RenderPlan;
import com.spotifyplusplus.lyrics.reading.ReadingModels.TimedReadingUnit;
import com.spotifyplusplus.lyrics.reading.ReadingModels.ValidationResult;

public final class DefaultRenderPlanBuilder implements RenderPlanBuilder {
    @Override
    public RenderPlan build(ParsedLine line, CanonicalLine canonical, List<ReadingAnnotation> annotations) {
        List<ReadingUnit> readingUnits = new ArrayList<>();
        for (ReadingAnnotation annotation : annotations) readingUnits.addAll(annotation.units);
        readingUnits.sort(Comparator.comparingInt(unit -> unit.canonicalRange.startCp));
        List<TimedReadingUnit> timed = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        for (ReadingUnit unit : readingUnits) {
            joined.append(unit.text);
            for (String spanId : unit.timingRefs) {
                timed.add(new TimedReadingUnit(spanId, unit.canonicalRange, unit.text, unit.logicalGroupId));
            }
        }
        return new RenderPlan(line.id, canonical.spanMappings, readingUnits, timed, joined.toString(), null);
    }

    public static ValidationResult validate(RenderPlan plan) {
        List<String> errors = new ArrayList<>();
        Set<String> owners = new HashSet<>();
        for (TimedReadingUnit unit : plan.timedReadingUnits) {
            if (!owners.add(unit.spanId)) errors.add("duplicate timing owner:" + unit.spanId);
        }
        StringBuilder joined = new StringBuilder();
        for (ReadingUnit unit : plan.readingUnits) joined.append(unit.text);
        if (!joined.toString().equals(plan.joinedDisplayText)) errors.add("joined display mismatch");
        return new ValidationResult(errors.isEmpty(), errors);
    }
}
