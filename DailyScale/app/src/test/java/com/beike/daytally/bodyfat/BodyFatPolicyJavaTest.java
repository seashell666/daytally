package com.beike.daytally.bodyfat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 体脂率的纯规则测试放在 Java 测试源集里，避免当前 AGP/Kotlin 测试源集的类路径问题；
 * 被测试的仍然是 Android 正式代码里的同一套 BodyFatPolicy / BodyFatRecord。
 */
public class BodyFatPolicyJavaTest {

    @Test
    public void rangeIsExplicitAndNeverSilentlyClamped() {
        assertTrue(BodyFatPolicy.INSTANCE.isValidValue(1.0));
        assertTrue(BodyFatPolicy.INSTANCE.isValidValue(23.4));
        assertTrue(BodyFatPolicy.INSTANCE.isValidValue(75.0));
        assertFalse(BodyFatPolicy.INSTANCE.isValidValue(0.9));
        assertFalse(BodyFatPolicy.INSTANCE.isValidValue(75.1));
        assertFalse(BodyFatPolicy.INSTANCE.isValidValue(Double.NaN));
        assertFalse(BodyFatPolicy.INSTANCE.isValidValue(Double.POSITIVE_INFINITY));
    }

    @Test
    public void sourceNormalizationKeepsOkokAndManualDistinct() {
        assertEquals(BodyFatPolicy.SOURCE_OKOK, BodyFatPolicy.INSTANCE.normalizeSource("OKOK"));
        assertEquals(BodyFatPolicy.SOURCE_OKOK_MANUAL, BodyFatPolicy.INSTANCE.normalizeSource("okok_manual"));
        assertEquals(BodyFatPolicy.SOURCE_MANUAL, BodyFatPolicy.INSTANCE.normalizeSource("com.chipsea.btcontrol"));
        assertEquals(BodyFatPolicy.SOURCE_MANUAL, BodyFatPolicy.INSTANCE.normalizeSource(null));
        assertEquals("OKOK 测量", BodyFatPolicy.INSTANCE.sourceLabel(BodyFatPolicy.SOURCE_OKOK));
    }

    @Test
    public void formatUsesOneDecimal() {
        assertEquals("23.4", BodyFatPolicy.INSTANCE.format(23.36));
        assertEquals("23.5", BodyFatPolicy.INSTANCE.format(23.45));
    }

    @Test
    public void serverJsonRoundTripPreservesRecord() throws Exception {
        JSONObject raw = new JSONObject()
                .put("recordId", "bf-1")
                .put("metricType", "body_fat")
                .put("value", 23.4)
                .put("unit", "%")
                .put("measuredAt", 1763700000000L)
                .put("source", "okok")
                .put("deviceId", "phone-a")
                .put("createdAt", 1763700001000L)
                .put("updatedAt", 1763700001000L);
        BodyFatRecord parsed = BodyFatRecord.Companion.fromJson(raw);
        assertNotNull(parsed);
        assertEquals("bf-1", parsed.getRecordId());
        assertEquals(23.4, parsed.getValue(), 0.0001);
        assertEquals("body_fat", parsed.getMetricType());
        assertEquals("%", parsed.getUnit());
        assertEquals("okok", parsed.getSource());
        assertEquals("phone-a", parsed.getDeviceId());
        assertEquals(1763700000000L, parsed.getMeasuredAt());
    }

    @Test
    public void invalidServerRecordsAreRejected() throws Exception {
        JSONObject base = new JSONObject()
                .put("recordId", "bf-1")
                .put("metricType", "body_fat")
                .put("value", 23.4)
                .put("unit", "%")
                .put("measuredAt", 1763700000000L)
                .put("source", "okok")
                .put("deviceId", "phone-a");
        assertNull(BodyFatRecord.Companion.fromJson(new JSONObject(base.toString()).put("unit", "kg")));
        assertNull(BodyFatRecord.Companion.fromJson(new JSONObject(base.toString()).put("metricType", "weight")));
        assertNull(BodyFatRecord.Companion.fromJson(new JSONObject(base.toString()).put("value", 80.0)));
        assertNull(BodyFatRecord.Companion.fromJson(new JSONObject(base.toString()).put("recordId", "")));
    }
}
