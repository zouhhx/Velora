package com.zouhu.speedlimit.feature.accel.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 纯 Java 的加速计算口径。
 *
 * 速度测试：
 * - 起点：静止项目为 0.5 km/h，滚动项目为项目起始速度（60/100）。
 * - 终点：项目结束速度（100/160/200）。
 * - 总 G = ((end - start) / 3.6) / 总秒数 / 9.80665。
 * - 分段：从显示起点开始每 10 km/h 一段；首段实际从 startKmh 算起。
 * - 起步/终点/区间穿越都用相邻样本线性插值，不直接取采样时刻。
 *
 * 距离测试：
 * - 从 t0 起按梯形积分累计距离，到达目标距离时插值时刻与终点速度。
 */
public final class PerformanceCalculator {

    public static final double G = 9.80665;
    public static final double START_KMH = 0.5;
    public static final double END_KMH = 100.0;
    public static final long LOW_CONFIDENCE_GAP_MS = 800L;
    public static final long MAX_SEGMENT_GAP_MS = 2500L;

    private PerformanceCalculator() {
    }

    public static final class Crossing {
        public final double tMs;
        public final long gapMs;
        public final boolean lowConfidence;

        public Crossing(double tMs, long gapMs) {
            this.tMs = tMs;
            this.gapMs = gapMs;
            this.lowConfidence = gapMs > LOW_CONFIDENCE_GAP_MS;
        }
    }

    /**
     * 找速度曲线穿过 threshold 的插值时刻。找不到相邻的上下样本时返回 null。
     */
    public static Crossing crossing(List<TestSample> samples, double threshold) {
        if (samples == null || samples.size() < 2) return null;

        TestSample prev = null;
        for (TestSample sample : samples) {
            if (sample == null) continue;
            double v = sample.speedKmh();
            if (!Double.isFinite(v) || v < 0.0) continue;

            if (Math.abs(v - threshold) < 1e-7) {
                long gap = prev == null ? 0L : Math.max(0L, sample.tMs - prev.tMs);
                return new Crossing(sample.tMs, gap);
            }

            if (prev != null) {
                double pv = prev.speedKmh();
                if (pv < threshold && v > threshold) {
                    long gap = sample.tMs - prev.tMs;
                    if (gap <= 0L) {
                        prev = sample;
                        continue;
                    }
                    double fraction = (threshold - pv) / (v - pv);
                    double t = prev.tMs + fraction * gap;
                    return new Crossing(t, gap);
                }
            }
            prev = sample;
        }
        return null;
    }

    public static double totalG(double startKmh, double endKmh, double durationMs) {
        if (durationMs <= 0.0) return Double.NaN;
        double deltaV = (endKmh - startKmh) / 3.6;
        return deltaV / (durationMs / 1000.0) / G;
    }

    public static double segmentG(double lowerKmh, double upperKmh, double durationMs) {
        if (durationMs <= 0.0) return Double.NaN;
        double deltaV = (upperKmh - lowerKmh) / 3.6;
        return deltaV / (durationMs / 1000.0) / G;
    }

    /**
     * 兼容原 0–100 入口。
     */
    public static List<SegmentResult> segments(List<TestSample> samples, double t0Ms, double endMs) {
        return segments(samples, t0Ms, endMs, START_KMH, END_KMH);
    }

    /**
     * 独立计算区间。起点/终点由测试项目决定，每 10 km/h 一段；
     * 首段从实际起始速度（静止起步为 0.5）算起。
     */
    public static List<SegmentResult> segments(List<TestSample> samples, double t0Ms, double endMs,
                                               double startKmh, double endKmh) {
        List<SegmentResult> out = new ArrayList<>();
        if (!(endKmh > startKmh)) return out;

        double base = Math.floor(startKmh / 10.0) * 10.0;
        int count = (int) Math.round((endKmh - base) / 10.0);
        if (count <= 0) return out;

        for (int i = 0; i < count; i++) {
            double lower = i == 0 ? startKmh : base + i * 10.0;
            double upper = Math.min(base + (i + 1) * 10.0, endKmh);

            Crossing lo = i == 0 ? new Crossing(t0Ms, 0L) : crossing(samples, lower);
            Crossing hi = crossing(samples, upper);

            if (lo == null || hi == null) {
                out.add(new SegmentResult(
                        i, lower, upper,
                        null, null,
                        SegmentResult.INSUFFICIENT,
                        "缺样本"
                ));
                continue;
            }

            double duration = hi.tMs - lo.tMs;
            if (duration <= 50.0) {
                out.add(new SegmentResult(
                        i, lower, upper,
                        null, null,
                        SegmentResult.INVALID,
                        "时间异常"
                ));
                continue;
            }

            long maxGap = Math.max(lo.gapMs, hi.gapMs);
            if (maxGap > MAX_SEGMENT_GAP_MS) {
                out.add(new SegmentResult(
                        i, lower, upper,
                        null, null,
                        SegmentResult.INSUFFICIENT,
                        "间隔过大"
                ));
                continue;
            }

            double g = segmentG(lower, upper, duration);
            if (!Double.isFinite(g) || g <= 0.0 || g > 1.5) {
                out.add(new SegmentResult(
                        i, lower, upper,
                        Math.round(duration), null,
                        SegmentResult.INVALID,
                        "G 异常"
                ));
                continue;
            }

            String status = maxGap > LOW_CONFIDENCE_GAP_MS
                    ? SegmentResult.LOW
                    : SegmentResult.OK;
            String note = maxGap > LOW_CONFIDENCE_GAP_MS
                    ? "间隔 " + String.format(Locale.US, "%.1f", maxGap / 1000.0) + " s"
                    : "";

            out.add(new SegmentResult(
                    i, lower, upper,
                    Math.round(duration),
                    g,
                    status,
                    note
            ));
        }

        return out;
    }

    public static long maxGapMs(List<TestSample> samples, double fromMs, double toMs) {
        if (samples == null || samples.size() < 2) return Long.MAX_VALUE;
        long max = 0L;
        TestSample prev = null;
        for (TestSample sample : samples) {
            if (sample == null || sample.tMs < fromMs || sample.tMs > toMs) continue;
            if (prev != null) {
                long gap = sample.tMs - prev.tMs;
                if (gap > max) max = gap;
            }
            prev = sample;
        }
        return prev == null ? Long.MAX_VALUE : max;
    }

    public static double gpsRateHz(List<TestSample> samples, double fromMs, double toMs) {
        if (samples == null) return 0.0;
        int count = 0;
        double first = Double.NaN;
        double last = Double.NaN;
        for (TestSample sample : samples) {
            if (sample == null || sample.tMs < fromMs || sample.tMs > toMs) continue;
            if (Double.isNaN(first)) first = sample.tMs;
            last = sample.tMs;
            count++;
        }
        if (count < 2 || last <= first) return 0.0;
        return (count - 1) / ((last - first) / 1000.0);
    }

    /**
     * 从 t0 起按梯形积分累计距离，找到首次达到 targetMeters 的时刻。
     * 返回的 Crossing.gapMs 是穿越所在样本间隔，用于质量判断。
     */
    public static Crossing distanceCrossing(List<TestSample> samples, double t0Ms,
                                           double t0Kmh, double targetMeters) {
        if (samples == null || samples.isEmpty()) return null;
        if (targetMeters <= 0.0) return new Crossing(t0Ms, 0L);

        double dist = 0.0;
        double v0Mps = t0Kmh / 3.6;
        TestSample prev = null;

        for (TestSample sample : samples) {
            if (sample == null || sample.tMs < t0Ms) continue;

            if (prev == null) {
                double dtSec = (sample.tMs - t0Ms) / 1000.0;
                if (dtSec <= 0.0) continue;
                double d = (v0Mps + sample.speedMps) / 2.0 * dtSec;
                if (dist + d >= targetMeters) {
                    double f = (targetMeters - dist) / d;
                    f = Math.max(0.0, Math.min(1.0, f));
                    return new Crossing(t0Ms + f * (sample.tMs - t0Ms),
                            Math.round(dtSec * 1000.0));
                }
                dist += d;
                prev = sample;
                continue;
            }

            double dtSec = (sample.tMs - prev.tMs) / 1000.0;
            if (dtSec <= 0.0) {
                prev = sample;
                continue;
            }
            double d = (prev.speedMps + sample.speedMps) / 2.0 * dtSec;
            if (dist + d >= targetMeters) {
                double f = (targetMeters - dist) / d;
                f = Math.max(0.0, Math.min(1.0, f));
                return new Crossing(prev.tMs + f * (sample.tMs - prev.tMs),
                        Math.round(dtSec * 1000.0));
            }
            dist += d;
            prev = sample;
        }
        return null;
    }

    /**
     * 在样本时间轴上线性插值出 tMs 时刻的速度（km/h）。
     */
    public static double interpolateSpeed(List<TestSample> samples, double tMs) {
        if (samples == null || samples.isEmpty()) return Double.NaN;
        TestSample prev = null;
        for (TestSample sample : samples) {
            if (sample == null) continue;
            if (sample.tMs >= tMs) {
                if (prev == null) return sample.speedKmh();
                if (sample.tMs == prev.tMs) return prev.speedKmh();
                double f = (tMs - prev.tMs) / (double) (sample.tMs - prev.tMs);
                return prev.speedKmh() + f * (sample.speedKmh() - prev.speedKmh());
            }
            prev = sample;
        }
        return prev != null ? prev.speedKmh() : Double.NaN;
    }

    public static String formatSpeed(double kmh) {
        if (Math.abs(kmh - Math.rint(kmh)) < 1e-6) {
            return String.format(Locale.US, "%.0f", kmh);
        }
        return String.format(Locale.US, "%.1f", kmh);
    }

    public static String formatG(double g) {
        if (!Double.isFinite(g)) return "—";
        return String.format(Locale.US, "%.2f g", g);
    }
}
