package com.zouhu.speedlimit.feature.accel.core;

/**
 * 一条 GNSS 原始定位，时间使用 elapsedRealtimeNanos，和传感器时间戳同一时基。
 */
public final class GnssFix {
    public final long elapsedNanos;
    public final long wallMillis;
    public final double speedMps;
    public final double accuracyM;
    public final double speedAccuracyMps;
    public final double latitude;
    public final double longitude;

    public GnssFix(long elapsedNanos, long wallMillis, double speedMps,
                   double accuracyM, double speedAccuracyMps,
                   double latitude, double longitude) {
        this.elapsedNanos = elapsedNanos;
        this.wallMillis = wallMillis;
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
