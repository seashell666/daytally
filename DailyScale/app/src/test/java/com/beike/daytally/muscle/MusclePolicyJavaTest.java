package com.beike.daytally.muscle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MusclePolicyJavaTest {
    @Test
    public void acceptsUserRangeAndRoundsToOneDecimal() {
        assertTrue(MusclePolicy.INSTANCE.isValidValue(50.7));
        assertTrue(MusclePolicy.INSTANCE.isValidValue(47.0));
        assertTrue(MusclePolicy.INSTANCE.isValidValue(72.0));
        assertEquals(50.7, MusclePolicy.INSTANCE.round1(50.74), 0.0001);
        assertEquals("50.7", MusclePolicy.INSTANCE.format(50.7));
    }

    @Test
    public void rejectsHeightLikeAndMissingValues() {
        assertFalse(MusclePolicy.INSTANCE.isValidValue(175.0));
        assertFalse(MusclePolicy.INSTANCE.isValidValue(0.0));
        assertFalse(MusclePolicy.INSTANCE.isValidValue(Double.NaN));
        assertFalse(MusclePolicy.INSTANCE.isValidValue(Double.POSITIVE_INFINITY));
    }

    @Test
    public void dayKeyUsesMeasurementDate() {
        long ts = 1795651200000L; // 2026-11-26 08:00 CST
        assertEquals("2026-11-26", MusclePolicy.INSTANCE.dayKey(ts, java.time.ZoneId.of("Asia/Shanghai")));
    }
}
