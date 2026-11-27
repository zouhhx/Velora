package com.zouhu.speedlimit.feature.accel.core;

/**
 * 五种加速测试项目。
 *
 * displayStartKmh 是分段显示的起点（0 / 60 / 100）；
 * startThresholdKmh 是实际计时起点（静止项目 0.5，滚动项目等于显示起点）。
 */
public enum TestMode {

    ZERO_100("0–100 km/h", Kind.STATIONARY_SPEED, 0.0, 0.5, 100.0, 0.0, 30L),
    SIXTY_160("60–160 km/h", Kind.ROLLING_SPEED, 60.0, 60.0, 160.0, 0.0, 40L),
    HUNDRED_200("100–200 km/h", Kind.ROLLING_SPEED, 100.0, 100.0, 200.0, 0.0, 50L),
    ZERO_200("0–200 km/h", Kind.STATIONARY_SPEED, 0.0, 0.5, 200.0, 0.0, 60L),
    ZERO_400M("0–400 m", Kind.STATIONARY_DISTANCE, 0.0, 0.5, 0.0, 400.0, 60L);

    public enum Kind {
        STATIONARY_SPEED,
        ROLLING_SPEED,
        STATIONARY_DISTANCE
    }

    public final String label;
    public final Kind kind;
    public final double displayStartKmh;
    public final double startThresholdKmh;
    public final double endKmh;
    public final double distanceMeters;
    public final long timeoutNs;

    TestMode(String label, Kind kind, double displayStartKmh, double startThresholdKmh,
             double endKmh, double distanceMeters, long timeoutSeconds) {
        this.label = label;
        this.kind = kind;
        this.displayStartKmh = displayStartKmh;
        this.startThresholdKmh = startThresholdKmh;
        this.endKmh = endKmh;
        this.distanceMeters = distanceMeters;
        this.timeoutNs = timeoutSeconds * 1_000_000_000L;
    }

    public boolean isStationary() {
        return kind == Kind.STATIONARY_SPEED || kind == Kind.STATIONARY_DISTANCE;
    }

    public boolean isRolling() {
        return kind == Kind.ROLLING_SPEED;
    }

    public boolean isDistance() {
        return kind == Kind.STATIONARY_DISTANCE;
    }

    public boolean hasSegments() {
        return kind == Kind.STATIONARY_SPEED || kind == Kind.ROLLING_SPEED;
    }

    public int segmentCount() {
        if (!hasSegments()) return 0;
        return (int) Math.round((endKmh - displayStartKmh) / 10.0);
    }

    public String endDescription() {
        if (isDistance()) {
            return PerformanceCalculator.formatSpeed(distanceMeters) + " m";
        }
        return PerformanceCalculator.formatSpeed(endKmh) + " km/h";
    }

    public static TestMode fromName(String name) {
        if (name != null) {
            for (TestMode mode : values()) {
                if (mode.name().equals(name)) return mode;
            }
        }
        return ZERO_100;
    }
}
