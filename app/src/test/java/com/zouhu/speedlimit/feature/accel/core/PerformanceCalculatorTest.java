package com.zouhu.speedlimit.feature.accel.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯算法测试：用恒定加速度和缺失样本验证插值、总 G 与分段 G。
 * 这不是真车数据，只用于验证计算口径和异常处理。
 */
public class PerformanceCalculatorTest {

    private static final double A = 5.0; // m/s^2

    @Test
    public void constantAccelerationAt10Hz() {
        List<TestSample> samples = constantAccelerationSamples(10, 6.0);
        PerformanceCalculator.Crossing crossing =
                PerformanceCalculator.crossing(samples, PerformanceCalculator.END_KMH);
        assertNotNull(crossing);
        double expectedDurationMs = (100.0 - 0.5) / 3.6 / A * 1000.0;
        assertEquals(expectedDurationMs, crossing.tMs, 2.0);

        List<SegmentResult> segments = PerformanceCalculator.segments(
                samples, 0.0, crossing.tMs);
        assertEquals(10, segments.size());

        double sum = 0.0;
        for (SegmentResult segment : segments) {
            assertEquals(SegmentResult.OK, segment.status);
            assertTrue(segment.hasValues());
            sum += segment.durationMs;
            assertEquals(A / PerformanceCalculator.G, segment.avgG, 0.002);
        }
        assertEquals(expectedDurationMs, sum, 5.0);

        double totalG = PerformanceCalculator.totalG(
                PerformanceCalculator.START_KMH, PerformanceCalculator.END_KMH,
                expectedDurationMs);
        assertEquals(A / PerformanceCalculator.G, totalG, 0.002);
    }

    @Test
    public void constantAccelerationAt1HzStillInterpolates() {
        List<TestSample> samples = constantAccelerationSamples(1, 7.0);
        PerformanceCalculator.Crossing crossing =
                PerformanceCalculator.crossing(samples, PerformanceCalculator.END_KMH);
        assertNotNull(crossing);
        double expectedDurationMs = (100.0 - 0.5) / 3.6 / A * 1000.0;
        assertEquals(expectedDurationMs, crossing.tMs, 40.0);

        List<SegmentResult> segments = PerformanceCalculator.segments(
                samples, 0.0, crossing.tMs);
        for (SegmentResult segment : segments) {
            assertTrue(segment.hasValues());
            assertTrue(segment.status.equals(SegmentResult.OK)
                    || segment.status.equals(SegmentResult.LOW));
        }
    }

    @Test
    public void missingSamplesDoNotCreateFakeValues() {
        List<TestSample> samples = new ArrayList<>();
        samples.add(sample(0L, 0.5));
        samples.add(sample(1000L, 20.0));
        samples.add(sample(10000L, 100.0));

        List<SegmentResult> segments = PerformanceCalculator.segments(samples, 0.0, 10000.0);
        int insufficient = 0;
        for (SegmentResult segment : segments) {
            if (SegmentResult.INSUFFICIENT.equals(segment.status)) {
                insufficient++;
                assertNull(segment.durationMs);
                assertNull(segment.avgG);
            }
        }
        assertTrue("expected missing segments to be flagged", insufficient >= 8);
    }

    @Test
    public void interpolationAtThreshold() {
        List<TestSample> samples = new ArrayList<>();
        samples.add(sample(0L, 0.0));
        samples.add(sample(1000L, 10.0));

        PerformanceCalculator.Crossing crossing =
                PerformanceCalculator.crossing(samples, 5.0);
        assertNotNull(crossing);
        assertEquals(500.0, crossing.tMs, 0.001);
        assertEquals(1000L, crossing.gapMs);
    }

    @Test
    public void gapAndRateAreMeasuredFromRawSamples() {
        List<TestSample> samples = new ArrayList<>();
        samples.add(sample(0L, 0.5));
        samples.add(sample(1000L, 20.0));
        samples.add(sample(10000L, 100.0));

        assertEquals(9000L, PerformanceCalculator.maxGapMs(samples, 0.0, 10000.0));
        assertEquals(0.2, PerformanceCalculator.gpsRateHz(samples, 0.0, 10000.0), 0.001);
    }

    @Test
    public void zeroTo200HasTwentySegments() {
        List<TestSample> samples = constantAccelerationSamples(10, 12.0);
        List<SegmentResult> segments = PerformanceCalculator.segments(
                samples, 0.0, 12000.0, 0.5, 200.0);
        assertEquals(20, segments.size());
        assertEquals("0–10", segments.get(0).label());
        assertEquals("190–200", segments.get(19).label());
        for (SegmentResult segment : segments) {
            assertEquals(SegmentResult.OK, segment.status);
        }
    }

    @Test
    public void rollingSixtyTo160SegmentsStartAtSixty() {
        List<TestSample> samples = new ArrayList<>();
        for (int i = 0; i <= 60; i++) {
            double t = i / 10.0;
            double kmh = 60.0 + A * 3.6 * t;
            samples.add(sample(Math.round(t * 1000.0), kmh));
        }
        List<SegmentResult> segments = PerformanceCalculator.segments(
                samples, 0.0, 6000.0, 60.0, 160.0);
        assertEquals(10, segments.size());
        assertEquals("60–70", segments.get(0).label());
        assertEquals("150–160", segments.get(9).label());
        for (SegmentResult segment : segments) {
            assertEquals(SegmentResult.OK, segment.status);
            assertEquals(A / PerformanceCalculator.G, segment.avgG, 0.002);
        }
    }

    @Test
    public void distanceCrossingAndEndSpeed() {
        List<TestSample> samples = new ArrayList<>();
        for (int i = 0; i <= 140; i++) {
            double t = i / 10.0;
            double kmh = 0.5 + A * 3.6 * t;
            samples.add(sample(Math.round(t * 1000.0), kmh));
        }
        PerformanceCalculator.Crossing crossing = PerformanceCalculator.distanceCrossing(
                samples, 0.0, 0.5, 400.0);
        assertNotNull(crossing);
        double expected = Math.sqrt(2.0 * 400.0 / A) * 1000.0;
        assertEquals(expected, crossing.tMs, 100.0);
        double endSpeed = PerformanceCalculator.interpolateSpeed(samples, crossing.tMs);
        assertEquals(A * Math.sqrt(2.0 * 400.0 / A) * 3.6, endSpeed, 2.0);
    }

    private static List<TestSample> constantAccelerationSamples(int hz, double seconds) {
        List<TestSample> samples = new ArrayList<>();
        int count = (int) Math.round(hz * seconds);
        for (int i = 0; i <= count; i++) {
            double t = i / (double) hz;
            double kmh = 0.5 + A * 3.6 * t;
            samples.add(sample(Math.round(t * 1000.0), kmh));
        }
        return samples;
    }

    private static TestSample sample(long tMs, double kmh) {
        return new TestSample(
                tMs,
                System.currentTimeMillis() + tMs,
                kmh / 3.6,
                3.0,
                0.2,
                0.0,
                0.0
        );
    }
}
