package com.beike.daytally.bodyfat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OkokResultPolicyJavaTest {
    @Test
    public void convertsMusclePercentToKilograms() {
        OkokMeasurement m = OkokResultPolicy.INSTANCE.fromWeightEntity(70.0, 22.3, 72.43);
        assertEquals(22.3, m.getBodyFat(), 0.0001);
        assertEquals(50.7, m.getMuscleKg(), 0.0001);
        assertEquals(70.0, m.getWeightKg(), 0.0001);
        assertTrue(m.getHasBodyFat());
        assertTrue(m.getHasMuscle());
    }

    @Test
    public void keepsValidHalfWhenTheOtherIsMissing() {
        OkokMeasurement m = OkokResultPolicy.INSTANCE.fromWeightEntity(70.0, -1.0, 72.43);
        assertFalse(m.getHasBodyFat());
        assertTrue(m.getHasMuscle());

        OkokMeasurement badWeight = OkokResultPolicy.INSTANCE.fromWeightEntity(Double.NaN, 22.0, 72.0);
        assertTrue(badWeight.getHasBodyFat());
        assertFalse(badWeight.getHasMuscle());
    }

    @Test
    public void sessionExpiresAfterThirtyMinutes() {
        long start = 1000L;
        assertTrue(OkokResultPolicy.INSTANCE.isSessionActive(start, start));
        assertTrue(OkokResultPolicy.INSTANCE.isSessionActive(start, start + 30L * 60L * 1000L));
        assertFalse(OkokResultPolicy.INSTANCE.isSessionActive(start, start + 31L * 60L * 1000L));
        assertFalse(OkokResultPolicy.INSTANCE.isSessionActive(0L, start));
    }

    @Test
    public void bodyFatRecordIdIsStablePerSession() {
        assertEquals("abc-bf", OkokResultPolicy.INSTANCE.bodyFatRecordId("abc"));
    }
}
