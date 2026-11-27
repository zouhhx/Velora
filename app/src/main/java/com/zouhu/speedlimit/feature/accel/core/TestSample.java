package com.zouhu.speedlimit.feature.accel.core;

/**
 * 一条原始 GNSS 样本。tMs 是相对于测试 t0 的时间，允许为负值（起步前预滚）。
 */
public final class TestSample {
    public final long tMs;
    public final long wallMs;
    public final double speedMps;
    public final double accuracyM;
    public final double speedAccuracyMps;
    public final double latitude;
    public final double longitude;

    public TestSample(long tMs, long wallMs, double speedMps,
                      double accuracyM, double speedAccuracyMps,
                      double latitude, double longitude) {
        this.tMs = tMs;
        this.wallMs = wallMs;
        this.speedMps = speedMps;
        this.accuracyM = accuracyM;
        this.speedAccuracyMps = speedAccuracyMps;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double speedKmh() {
        return speedMps * 3.6;
    }
}
