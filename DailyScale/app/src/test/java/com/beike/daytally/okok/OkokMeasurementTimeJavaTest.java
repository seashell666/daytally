package com.beike.daytally.okok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OkokMeasurementTimeJavaTest {
    public static class FakeEntity {
        private final String weightTime;

        public FakeEntity(String weightTime) {
            this.weightTime = weightTime;
        }

        public String getWeight_time() {
            return weightTime;
        }
    }

    @Test
    public void readsWeightTimeFromOkokEntity() {
        assertEquals(
            "2026-11-26 13:13:12",
            OkokMeasurementPolicy.INSTANCE.readWeightTimeRaw(new FakeEntity("2026-11-26 13:13:12"))
        );
        assertNull(OkokMeasurementPolicy.INSTANCE.readWeightTimeRaw(new FakeEntity("")));
    }

    @Test
    public void parsesOkokWeightTimeFormats() {
        assertTrue(OkokMeasurementPolicy.INSTANCE.parseWeightTime("2026-11-26 13:13:12") > 0L);
        assertTrue(OkokMeasurementPolicy.INSTANCE.parseWeightTime("2026-11-26T13:13:12") > 0L);
        assertTrue(OkokMeasurementPolicy.INSTANCE.parseWeightTime("2026/11/26 13:13:12") > 0L);
    }

    @Test
    public void rejectsUnknownWeightTime() {
        assertNull(OkokMeasurementPolicy.INSTANCE.parseWeightTime("not-a-time"));
        assertNull(OkokMeasurementPolicy.INSTANCE.parseWeightTime(""));
        assertNull(OkokMeasurementPolicy.INSTANCE.parseWeightTime(null));
    }

    @Test
    public void sameOkokRecordMeansDuplicateDelivery() {
        assertTrue(OkokMeasurementPolicy.INSTANCE.isSameOkokRecord(
                "2026-11-26 13:13:12", "2026-11-26 13:13:12"));
    }

    @Test
    public void newOkokRecordIsNotDuplicateEvenWhenValuesLookIdentical() {
        assertFalse(OkokMeasurementPolicy.INSTANCE.isSameOkokRecord(
                "2026-11-26 13:27:21", "2026-11-26 13:13:12"));
        assertFalse(OkokMeasurementPolicy.INSTANCE.isSameOkokRecord(
                "2026-11-26 14:24:36", null));
    }

    @Test
    public void missingWeightTimeNeverCountsAsDuplicate() {
        assertFalse(OkokMeasurementPolicy.INSTANCE.isSameOkokRecord(null, null));
        assertFalse(OkokMeasurementPolicy.INSTANCE.isSameOkokRecord("", ""));
        assertFalse(OkokMeasurementPolicy.INSTANCE.isSameOkokRecord(null, "2026-11-26 13:13:12"));
    }
}
