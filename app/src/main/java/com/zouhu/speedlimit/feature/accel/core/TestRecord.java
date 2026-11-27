package com.zouhu.speedlimit.feature.accel.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次零百测试的完整记录，包含用于复查的原始 GNSS 样本。
 */
public final class TestRecord {
    public static final String QUALITY_GOOD = "GOOD";
    public static final String QUALITY_FAIR = "FAIR";
    public static final String QUALITY_POOR = "POOR";
    public static final String QUALITY_INSUFFICIENT = "INSUFFICIENT";
    public static final String QUALITY_INTERRUPTED = "INTERRUPTED";
    public static final String QUALITY_ABORTED = "ABORTED";

    public long id;
    public long startedAtMs;
    public long endedAtMs;
    public long createdAtMs;
    public long durationMs = -1L;
    public double totalG = Double.NaN;
    public double startSpeedKmh = PerformanceCalculator.START_KMH;
    public double endSpeedKmh = PerformanceCalculator.END_KMH;
    public TestMode mode = TestMode.ZERO_100;
    public boolean forcedStart;
    public boolean assistEnabled;
    public boolean assistTriggered;
    public boolean assistValidated;
    public long assistLeadMs;
    public int assistRejected;
    public boolean valid;
    public String quality = QUALITY_INSUFFICIENT;
    public String reason = "";
    public double gpsRateHz;
    public int sampleCount;

    public final List<TestSample> samples = new ArrayList<>();
    public final List<SegmentResult> segments = new ArrayList<>();

    public boolean hasTotal() {
        return durationMs > 0L && !Double.isNaN(totalG);
    }

    public boolean hasResult() {
        return valid && durationMs > 0L;
    }

    public void addSample(TestSample sample) {
        samples.add(sample);
    }

    public void addSegment(SegmentResult segment) {
        segments.add(segment);
    }
}
