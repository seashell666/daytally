package com.beike.daytally.bodyfat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class BodyFatMergeJavaTest {
    private static BodyFatRecord rec(String id, double value, long measured, long updated) {
        return new BodyFatRecord(id, value, measured, "okok", "phone", measured, updated, "%", "body_fat");
    }

    @Test
    public void newerServerWinsSameId() {
        BodyFatRecord local = rec("a", 21.0, 100L, 100L);
        BodyFatRecord server = rec("a", 20.0, 100L, 200L);
        List<BodyFatRecord> merged = BodyFatMerge.merge(Arrays.asList(local), Arrays.asList(server));
        assertEquals(1, merged.size());
        assertEquals(20.0, merged.get(0).getValue(), 0.001);
    }

    @Test
    public void newerLocalIsNotOverwrittenByStaleServer() {
        BodyFatRecord local = rec("a", 21.0, 100L, 300L);
        BodyFatRecord server = rec("a", 20.0, 100L, 200L);
        List<BodyFatRecord> merged = BodyFatMerge.merge(Arrays.asList(local), Arrays.asList(server));
        assertEquals(21.0, merged.get(0).getValue(), 0.001);
    }

    @Test
    public void offlineRecordSurvivesMergeAndStaysInOutbox() {
        BodyFatRecord offline = rec("offline", 22.0, 500L, 500L);
        BodyFatRecord serverOnly = rec("server", 19.0, 400L, 400L);
        List<BodyFatRecord> merged = BodyFatMerge.merge(Arrays.asList(offline), Arrays.asList(serverOnly));
        assertEquals(2, merged.size());
        assertEquals(1, BodyFatMerge.remainingOutbox(Arrays.asList(offline), Arrays.asList(serverOnly)).size());
    }

    @Test
    public void ackedOutboxItemIsRemoved() {
        BodyFatRecord pending = rec("a", 21.0, 100L, 100L);
        BodyFatRecord confirmed = rec("a", 21.0, 100L, 150L);
        assertEquals(0, BodyFatMerge.remainingOutbox(Arrays.asList(pending), Arrays.asList(confirmed)).size());
    }

    @Test
    public void duplicateRecordsKeepLatestSnapshot() {
        BodyFatRecord older = rec("a", 21.0, 100L, 100L);
        BodyFatRecord newer = rec("a", 20.5, 120L, 300L);
        assertEquals(1, BodyFatMerge.dedupe(Arrays.asList(older, newer)).size());
        assertEquals(120L, BodyFatMerge.dedupe(Arrays.asList(older, newer)).get(0).getMeasuredAt());
    }
}
