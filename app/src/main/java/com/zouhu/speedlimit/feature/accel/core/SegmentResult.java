package com.zouhu.speedlimit.feature.accel.core;

/**
 * 一个 10 km/h 区间的计算结果。
 * durationMs / avgG 为 null 表示该区间无法可靠计算。
 */
public final class SegmentResult {
    public static final String OK = "OK";
    public static final String LOW = "LOW";
    public static final String INSUFFICIENT = "INSUFFICIENT";
    public static final String INVALID = "INVALID";

    public final int index;
    public final double lowerKmh;
    public final double upperKmh;
    public final Long durationMs;
    public final Double avgG;
    public final String status;
    public final String note;

    public SegmentResult(int index, double lowerKmh, double upperKmh,
                         Long durationMs, Double avgG, String status, String note) {
        this.index = index;
        this.lowerKmh = lowerKmh;
        this.upperKmh = upperKmh;
        this.durationMs = durationMs;
        this.avgG = avgG;
        this.status = status;
        this.note = note;
    }

    public boolean hasValues() {
        return durationMs != null && avgG != null;
    }

    public String label() {
        if (index == 0 && Math.abs(lowerKmh - 0.5) < 1e-6) return "0–10";
        return String.format(java.util.Locale.US, "%.0f–%.0f", lowerKmh, upperKmh);
    }
}
