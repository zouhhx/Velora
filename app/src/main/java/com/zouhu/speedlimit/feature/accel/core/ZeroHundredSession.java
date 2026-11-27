package com.zouhu.speedlimit.feature.accel.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 加速测试状态机（通用版，保留原 0–100 算法）。
 *
 * - 0–100 / 0–200：严格静止起步，沿用原静止确认、0.5 km/h 插值、
 *   终点确认和加速度计辅助起步。
 * - 60–160 / 100–200：滚动起步，不要求停稳；从低于起点速度连续加速，
 *   跨过起点速度时插值 t0，跨过终点速度时插值结束。
 * - 0–400 m：严格静止起步，GNSS 速度梯形积分到 400 m，结果含终点速度。
 *
 * 所有成绩以 GNSS 速度为准；加速度计只辅助静止起步候选，不参与终点。
 */
public final class ZeroHundredSession {

    public enum State {
        WAITING_STANDSTILL,
        WAITING_LAUNCH,
        WAITING_ROLL_START,
        RUNNING,
        FINISH_PENDING,
        FINISHED,
        INVALID
    }

    public interface Listener {
        void onSessionUpdated();
        void onTestFinished(TestRecord record);
    }

    private static final double STANDSTILL_KMH = 0.8;
    private static final double LAUNCH_KMH = 1.0;
    private static final long STANDSTILL_HOLD_NS = 2_000_000_000L;
    private static final long MAX_GAP_NS = 2_500_000_000L;
    private static final long PRE_ROLL_NS = 6_000_000_000L;
    private static final long STORED_PRE_ROLL_MS = 4_000L;
    private static final long ACCEL_SUSTAIN_NS = 150_000_000L;
    private static final long ACCEL_CONFIRM_NS = 2_000_000_000L;
    private static final double ACCEL_THRESHOLD_MPS2 = 1.2;
    private static final double GYRO_MAX_RAD_S = 0.8;

    private final List<GnssFix> roll = new ArrayList<>();
    private final List<TestSample> samples = new ArrayList<>();
    private final Listener listener;
    private final TestMode mode;

    private State state;
    private boolean assistEnabled;
    private boolean forcedStart;
    private boolean startConfirmed;
    private boolean resultDelivered;

    private long stationarySinceNs;
    private int stationaryCount;
    private long lastFixNs;
    private double lastSpeedKmh;

    private long t0Ns;
    private long tEndNs;
    private long startWallMs;
    private long endWallMs;
    private long finishCandidateFixNs;
    private long finishCandidateTEndNs;
    private long finishCandidateWallMs;
    private double maxRunSpeedKmh;
    private double endSpeedKmhAtFinish = Double.NaN;

    private double distanceAtLastSample;
    private long distanceSampleTMs;
    private double distanceSampleSpeedMps;

    private long accelSustainStartNs;
    private boolean assistCandidateActive;
    private long assistCandidateNs;
    private long assistDeadlineNs;
    private double assistPeak;
    private boolean assistTriggeredEver;
    private boolean assistValidated;
    private long assistLeadMs;
    private int assistRejected;
    private boolean assistSensorMissing;

    private String invalidReason = "";
    private String quality = TestRecord.QUALITY_INSUFFICIENT;

    public ZeroHundredSession(Listener listener) {
        this(listener, TestMode.ZERO_100);
    }

    public ZeroHundredSession(Listener listener, TestMode mode) {
        this.listener = listener;
        this.mode = mode == null ? TestMode.ZERO_100 : mode;
        this.state = this.mode.isRolling()
                ? State.WAITING_ROLL_START : State.WAITING_STANDSTILL;
    }

    public TestMode getMode() {
        return mode;
    }

    public State getState() {
        return state;
    }

    public boolean isAssistEnabled() {
        return assistEnabled;
    }

    public void setAssistEnabled(boolean enabled) {
        if (mode.isRolling()) {
            assistEnabled = false;
            return;
        }
        if (state == State.RUNNING || state == State.FINISH_PENDING) return;
        this.assistEnabled = enabled;
        if (!enabled) {
            accelSustainStartNs = 0L;
            assistCandidateActive = false;
            assistDeadlineNs = 0L;
        }
        notifyUpdate();
    }

    public boolean isForcedStart() {
        return forcedStart;
    }

    public void setForcedStart(boolean forcedStart) {
        this.forcedStart = forcedStart;
    }

    public boolean isRunningLike() {
        return state == State.RUNNING || state == State.FINISH_PENDING;
    }

    public boolean isFinishedLike() {
        return state == State.FINISHED || state == State.INVALID;
    }

    public boolean hasStarted() {
        return t0Ns > 0L;
    }

    public long getT0Ns() {
        return t0Ns;
    }

    /**
     * 兼容原 UI：返回终点时刻（0–100 时就是 t100）。
     */
    public long getT100Ns() {
        return tEndNs;
    }

    public long getTEndNs() {
        return tEndNs;
    }

    public long getStartWallMs() {
        return startWallMs;
    }

    public double getLastSpeedKmh() {
        return lastSpeedKmh;
    }

    public double getEndSpeedKmh() {
        return mode.isDistance() && Double.isFinite(endSpeedKmhAtFinish)
                ? endSpeedKmhAtFinish : mode.endKmh;
    }

    public String getInvalidReason() {
        return invalidReason;
    }

    public String getQuality() {
        return quality;
    }

    public boolean isAssistTriggeredEver() {
        return assistTriggeredEver;
    }

    public boolean isAssistValidated() {
        return assistValidated;
    }

    public long getAssistLeadMs() {
        return assistLeadMs;
    }

    public int getAssistRejected() {
        return assistRejected;
    }

    public boolean isAssistSensorMissing() {
        return assistSensorMissing;
    }

    public void setAssistSensorMissing(boolean missing) {
        this.assistSensorMissing = missing;
    }

    public List<TestSample> getSamples() {
        return samples;
    }

    public double getGpsRateHz() {
        double end = tEndNs > 0L
                ? (tEndNs - t0Ns) / 1e6
                : (lastFixNs > 0L && t0Ns > 0L ? (lastFixNs - t0Ns) / 1e6 : 0.0);
        if (end <= 0.0) return 0.0;
        return PerformanceCalculator.gpsRateHz(samples, 0.0, end);
    }

    public int getSampleCount() {
        return samples.size();
    }

    public double getElapsedMs(long nowElapsedNs) {
        if (t0Ns <= 0L) return 0.0;
        if (tEndNs > 0L && (state == State.FINISH_PENDING
                || state == State.FINISHED || state == State.INVALID)) {
            return (tEndNs - t0Ns) / 1e6;
        }
        if (state == State.RUNNING) {
            return Math.max(0L, nowElapsedNs - t0Ns) / 1e6;
        }
        if (state == State.INVALID) {
            return Math.max(0L, lastFixNs - t0Ns) / 1e6;
        }
        return 0.0;
    }

    public String getStatusTitle() {
        switch (state) {
            case WAITING_STANDSTILL:
                return "请停稳";
            case WAITING_LAUNCH:
                if (assistCandidateActive) return "等 GPS 确认";
                return "等待起步";
            case WAITING_ROLL_START:
                if (lastSpeedKmh >= mode.startThresholdKmh) {
                    return "请降到 " + PerformanceCalculator.formatSpeed(mode.startThresholdKmh)
                            + " 以下";
                }
                return "请加速";
            case RUNNING:
                return startConfirmed ? "计时中" : "确认起步";
            case FINISH_PENDING:
                return "确认终点";
            case FINISHED:
                return "完成";
            case INVALID:
                return "无效";
            default:
                return "";
        }
    }

    public String getStatusDetail() {
        switch (state) {
            case WAITING_STANDSTILL:
                return "停稳 2 秒后开始";
            case WAITING_LAUNCH:
                if (assistCandidateActive) {
                    return "加速度计已触发，等 GPS 速度确认";
                }
                return "起步即计时";
            case WAITING_ROLL_START:
                if (lastSpeedKmh >= mode.startThresholdKmh) {
                    return "先降到 " + PerformanceCalculator.formatSpeed(mode.startThresholdKmh)
                            + " 以下，再加速";
                }
                return "越过 " + PerformanceCalculator.formatSpeed(mode.startThresholdKmh)
                        + " 自动计时";
            case RUNNING:
                return String.format(Locale.US,
                        "GPS %.1f Hz · 目标 %s",
                        getGpsRateHz(), mode.endDescription());
            case FINISH_PENDING:
                if (mode.isDistance()) {
                    return "已到 " + PerformanceCalculator.formatSpeed(mode.distanceMeters)
                            + " m，等定位确认";
                }
                return "已过终点，等定位确认";
            case FINISHED:
                return "已保存";
            case INVALID:
                return invalidReason;
            default:
                return "";
        }
    }

    public void onGnssFix(GnssFix fix) {
        if (fix == null) return;

        double kmh = fix.speedKmh();
        if (!Double.isFinite(kmh) || kmh < 0.0) return;

        if (fix.accuracyM > 50.0 || (Double.isFinite(fix.speedAccuracyMps)
                && fix.speedAccuracyMps > 5.0)) {
            addToRoll(fix);
            lastFixNs = fix.elapsedNanos;
            lastSpeedKmh = kmh;
            if (state == State.RUNNING || state == State.FINISH_PENDING) {
                addSample(fix, Math.round((fix.elapsedNanos - t0Ns) / 1e6));
                invalidate("定位误差过大");
            } else {
                stationarySinceNs = 0L;
                stationaryCount = 0;
                notifyUpdate();
            }
            return;
        }

        long gapNs = lastFixNs == 0L ? 0L : fix.elapsedNanos - lastFixNs;
        addToRoll(fix);
        lastSpeedKmh = kmh;

        switch (state) {
            case WAITING_STANDSTILL:
            case WAITING_LAUNCH:
                handleStationaryWaiting(fix, kmh, gapNs);
                break;
            case WAITING_ROLL_START:
                handleRollingWaiting(fix, kmh, gapNs);
                break;
            case RUNNING:
                handleRunning(fix, kmh, gapNs);
                break;
            case FINISH_PENDING:
                handleFinishPending(fix, kmh, gapNs);
                break;
            default:
                break;
        }

        lastFixNs = fix.elapsedNanos;
    }

    private void handleStationaryWaiting(GnssFix fix, double kmh, long gapNs) {
        if (gapNs > MAX_GAP_NS) {
            stationarySinceNs = 0L;
            stationaryCount = 0;
        }

        if (kmh <= STANDSTILL_KMH) {
            if (stationarySinceNs == 0L) {
                stationarySinceNs = fix.elapsedNanos;
                stationaryCount = 1;
            } else {
                stationaryCount++;
            }

            if (state == State.WAITING_STANDSTILL
                    && stationaryCount >= 2
                    && fix.elapsedNanos - stationarySinceNs >= STANDSTILL_HOLD_NS) {
                state = State.WAITING_LAUNCH;
                notifyUpdate();
            }
            return;
        }

        if (state == State.WAITING_LAUNCH
                && kmh > LAUNCH_KMH
                && stationarySinceNs > 0L
                && gapNs <= MAX_GAP_NS) {
            GnssFix reference = findLastBelow(fix, PerformanceCalculator.START_KMH);
            beginRun(fix, reference, PerformanceCalculator.START_KMH, false);
            return;
        }

        stationarySinceNs = 0L;
        stationaryCount = 0;
        if (state == State.WAITING_LAUNCH) {
            state = State.WAITING_STANDSTILL;
        }
        notifyUpdate();
    }

    private void handleRollingWaiting(GnssFix fix, double kmh, long gapNs) {
        if (kmh < mode.startThresholdKmh) {
            notifyUpdate();
            return;
        }

        GnssFix reference = findLastBelow(fix, mode.startThresholdKmh);
        if (reference == null) {
            // 用户进入测试时已经高于起点速度；等待其降到起点以下，不生成记录。
            notifyUpdate();
            return;
        }
        if (gapNs > MAX_GAP_NS) {
            notifyUpdate();
            return;
        }
        beginRun(fix, reference, mode.startThresholdKmh, true);
    }

    private GnssFix findLastBelow(GnssFix current, double thresholdKmh) {
        for (int i = roll.size() - 1; i >= 0; i--) {
            GnssFix f = roll.get(i);
            if (f.elapsedNanos >= current.elapsedNanos) continue;
            if (f.speedKmh() <= thresholdKmh + 1e-9) return f;
        }
        return null;
    }

    private void beginRun(GnssFix firstMoving, GnssFix referenceBelow, double thresholdKmh,
                          boolean rolling) {
        if (referenceBelow == null) {
            if (rolling) {
                // 还没降到起点以下：保持等待，不产生无效记录。
                return;
            }
            failWithoutRecord("起步前没有速度 ≤0.5 km/h 的静止基准，无法可靠确定起步时刻");
            return;
        }

        long gapNs = firstMoving.elapsedNanos - referenceBelow.elapsedNanos;
        if (gapNs <= 0L || gapNs > MAX_GAP_NS) {
            if (rolling) {
                notifyUpdate();
                return;
            }
            failWithoutRecord("静止样本与起步样本间隔过大，无法插值起步时刻");
            return;
        }

        double v0 = referenceBelow.speedKmh();
        double v1 = firstMoving.speedKmh();
        if (v1 - v0 < 0.2) {
            if (rolling) {
                notifyUpdate();
                return;
            }
            failWithoutRecord("起步样本速度增量不足，疑似定位噪声");
            return;
        }
        if (!rolling && v1 > 50.0) {
            failWithoutRecord("检测到起步时速度已超过 50 km/h，疑似未从静止起步");
            return;
        }

        double fraction = (thresholdKmh - v0) / (v1 - v0);
        double t0 = referenceBelow.elapsedNanos + fraction * gapNs;
        if (t0 < referenceBelow.elapsedNanos - 1e6 || t0 > firstMoving.elapsedNanos + 1e6) {
            failWithoutRecord("起点阈值插值超出相邻样本范围");
            return;
        }

        t0Ns = Math.round(t0);
        startWallMs = interpolateWall(referenceBelow, firstMoving, t0);
        tEndNs = 0L;
        endWallMs = 0L;
        finishCandidateTEndNs = 0L;
        finishCandidateFixNs = 0L;
        finishCandidateWallMs = 0L;
        startConfirmed = false;
        maxRunSpeedKmh = v1;
        endSpeedKmhAtFinish = Double.NaN;
        invalidReason = "";
        quality = TestRecord.QUALITY_FAIR;

        samples.clear();
        for (GnssFix f : roll) {
            if (f.elapsedNanos > firstMoving.elapsedNanos) continue;
            long tMs = Math.round((f.elapsedNanos - t0Ns) / 1e6);
            if (tMs >= -STORED_PRE_ROLL_MS) {
                addSample(f, tMs);
            }
        }
        if (samples.isEmpty()) {
            addSample(firstMoving, Math.round((firstMoving.elapsedNanos - t0Ns) / 1e6));
        }

        // 距离模式的初始累计：t0 到第一个运动样本。
        if (mode.isDistance()) {
            double dtSec = (firstMoving.elapsedNanos - t0Ns) / 1e9;
            distanceAtLastSample = (thresholdKmh / 3.6 + firstMoving.speedMps) / 2.0 * dtSec;
            distanceSampleTMs = Math.round((firstMoving.elapsedNanos - t0Ns) / 1e6);
            distanceSampleSpeedMps = firstMoving.speedMps;
        }

        if (!rolling && assistCandidateActive) {
            long delta = firstMoving.elapsedNanos - assistCandidateNs;
            assistValidated = delta >= -200_000_000L && delta <= ACCEL_CONFIRM_NS;
            if (assistValidated) {
                assistLeadMs = Math.round((t0Ns - assistCandidateNs) / 1e6);
            }
            assistCandidateActive = false;
            assistDeadlineNs = 0L;
        }

        state = State.RUNNING;
        notifyUpdate();
    }

    private void handleRunning(GnssFix fix, double kmh, long gapNs) {
        long tMs = Math.round((fix.elapsedNanos - t0Ns) / 1e6);
        addSample(fix, tMs);

        if (gapNs > MAX_GAP_NS) {
            invalidate("GPS 中断");
            return;
        }

        if (!startConfirmed) {
            double confirmThreshold = mode.isRolling()
                    ? mode.startThresholdKmh
                    : PerformanceCalculator.START_KMH;
            if (kmh >= confirmThreshold) {
                startConfirmed = true;
            } else if (fix.elapsedNanos - t0Ns > 2_000_000_000L) {
                invalidate("速度回落，疑似跳变");
                return;
            }
        }

        if (kmh > maxRunSpeedKmh) maxRunSpeedKmh = kmh;

        if (mode.hasSegments() && kmh >= 5.0
                && maxRunSpeedKmh - kmh > 8.0
                && kmh < mode.endKmh - 5.0) {
            invalidate("中途减速，成绩无效");
            return;
        }

        if (fix.elapsedNanos - t0Ns > mode.timeoutNs) {
            invalidate("超时");
            return;
        }

        if (mode.isDistance()) {
            handleDistanceRunning(fix, kmh, tMs);
            return;
        }

        if (kmh >= mode.endKmh) {
            TestSample previous = null;
            for (int i = samples.size() - 2; i >= 0; i--) {
                TestSample s = samples.get(i);
                if (s.speedKmh() < mode.endKmh) {
                    previous = s;
                    break;
                }
            }
            if (previous == null) {
                invalidate("缺样本，无法插值终点");
                return;
            }

            double vPrev = previous.speedKmh();
            if (kmh <= vPrev) {
                invalidate("终点速度未上升");
                return;
            }
            long gap = tMs - previous.tMs;
            if (gap <= 0L || gap > MAX_GAP_NS / 1_000_000L) {
                invalidate("终点样本间隔过大");
                return;
            }

            double fraction = (mode.endKmh - vPrev) / (kmh - vPrev);
            double tEndMs = previous.tMs + fraction * gap;
            finishCandidateTEndNs = t0Ns + Math.round(tEndMs * 1e6);
            finishCandidateWallMs = interpolateWall(previous, fix);
            finishCandidateFixNs = fix.elapsedNanos;
            endSpeedKmhAtFinish = mode.endKmh;
            tEndNs = finishCandidateTEndNs;
            state = State.FINISH_PENDING;
            notifyUpdate();
        }
    }

    private void handleDistanceRunning(GnssFix fix, double kmh, long tMs) {
        double dtSec = (tMs - distanceSampleTMs) / 1000.0;
        if (dtSec <= 0.0) return;
        double segment = (distanceSampleSpeedMps + kmh / 3.6) / 2.0 * dtSec;
        double before = distanceAtLastSample;
        double after = before + segment;

        if (after >= mode.distanceMeters) {
            double fraction = segment <= 0.0 ? 0.0
                    : (mode.distanceMeters - before) / segment;
            fraction = Math.max(0.0, Math.min(1.0, fraction));
            double tEndMs = distanceSampleTMs + fraction * (tMs - distanceSampleTMs);
            finishCandidateTEndNs = t0Ns + Math.round(tEndMs * 1e6);
            finishCandidateWallMs = interpolateWallAtMs(tEndMs);
            finishCandidateFixNs = fix.elapsedNanos;
            endSpeedKmhAtFinish = distanceSampleSpeedMps + fraction
                    * (kmh / 3.6 - distanceSampleSpeedMps);
            endSpeedKmhAtFinish *= 3.6;
            tEndNs = finishCandidateTEndNs;
            state = State.FINISH_PENDING;
            notifyUpdate();
            return;
        }

        distanceAtLastSample = after;
        distanceSampleTMs = tMs;
        distanceSampleSpeedMps = kmh / 3.6;
    }

    private void handleFinishPending(GnssFix fix, double kmh, long gapNs) {
        addSample(fix, Math.round((fix.elapsedNanos - t0Ns) / 1e6));

        if (gapNs > MAX_GAP_NS) {
            invalidate("终点确认时 GPS 中断");
            return;
        }

        if (mode.isDistance()) {
            if (kmh < endSpeedKmhAtFinish - 10.0) {
                invalidate("终点后速度回落，疑似跳变");
                return;
            }
        } else if (kmh < mode.endKmh - 2.0) {
            invalidate("终点后速度回落，疑似跳变");
            return;
        }

        endWallMs = finishCandidateWallMs;
        finishValid();
    }

    private void finishValid() {
        long durationNs = finishCandidateTEndNs - t0Ns;
        if (durationNs <= 0L) {
            invalidate("终点时间异常");
            return;
        }
        state = State.FINISHED;
        tEndNs = finishCandidateTEndNs;
        endWallMs = finishCandidateWallMs;
        long durationMs = Math.round(durationNs / 1_000_000.0);
        validFinish(durationMs);
    }

    private void validFinish(long durationMs) {
        double endMs = durationMs;
        long maxGap = PerformanceCalculator.maxGapMs(samples, 0.0, endMs);
        double rate = PerformanceCalculator.gpsRateHz(samples, 0.0, endMs);
        quality = classifyQuality(rate, maxGap);
        invalidReason = "";

        TestRecord record = buildRecord(true, durationMs, endMs, rate);
        resultDelivered = true;
        if (listener != null) {
            listener.onTestFinished(record);
            listener.onSessionUpdated();
        }
    }

    private void failWithoutRecord(String reason) {
        state = State.INVALID;
        invalidReason = reason;
        quality = TestRecord.QUALITY_INSUFFICIENT;
        resultDelivered = true;
        if (listener != null) {
            listener.onTestFinished(null);
            listener.onSessionUpdated();
        }
    }

    private void invalidate(String reason) {
        if (state == State.FINISHED || state == State.INVALID) return;

        state = State.INVALID;
        invalidReason = reason;
        quality = reason.contains("信号") || reason.contains("中断")
                ? TestRecord.QUALITY_INTERRUPTED
                : reason.contains("取消") || reason.contains("后台")
                ? TestRecord.QUALITY_ABORTED
                : TestRecord.QUALITY_INSUFFICIENT;

        if (t0Ns <= 0L) {
            resultDelivered = true;
            if (listener != null) {
                listener.onTestFinished(null);
                listener.onSessionUpdated();
            }
            return;
        }

        long endNs = tEndNs > 0L ? tEndNs : (lastFixNs > 0L ? lastFixNs : t0Ns);
        long durationMs = Math.max(0L, (endNs - t0Ns) / 1_000_000L);
        double endMs = durationMs;
        double rate = PerformanceCalculator.gpsRateHz(samples, 0.0, endMs);
        if (endWallMs <= 0L) {
            endWallMs = lastFixNs > 0L ? startWallMs + durationMs : System.currentTimeMillis();
        }

        TestRecord record = buildRecord(false, durationMs, endMs, rate);
        resultDelivered = true;
        if (listener != null) {
            listener.onTestFinished(record);
            listener.onSessionUpdated();
        }
    }

    private TestRecord buildRecord(boolean valid, long durationMs, double endMs, double rate) {
        TestRecord record = new TestRecord();
        record.mode = mode;
        record.forcedStart = forcedStart;
        record.startedAtMs = startWallMs > 0L ? startWallMs : System.currentTimeMillis();
        record.endedAtMs = endWallMs > 0L ? endWallMs : record.startedAtMs + Math.max(0L, durationMs);
        record.createdAtMs = System.currentTimeMillis();
        record.durationMs = valid ? durationMs : (durationMs > 0L ? durationMs : -1L);
        record.totalG = valid && mode.hasSegments()
                ? PerformanceCalculator.totalG(mode.startThresholdKmh,
                mode.endKmh, durationMs)
                : Double.NaN;
        record.startSpeedKmh = mode.startThresholdKmh;
        record.endSpeedKmh = mode.isDistance()
                ? (valid && Double.isFinite(endSpeedKmhAtFinish)
                ? endSpeedKmhAtFinish : lastSpeedKmh)
                : (valid ? mode.endKmh : lastSpeedKmh);
        record.assistEnabled = mode.isStationary() && assistEnabled;
        record.assistTriggered = assistTriggeredEver;
        record.assistValidated = assistValidated;
        record.assistLeadMs = assistLeadMs;
        record.assistRejected = assistRejected;
        record.valid = valid;
        record.quality = quality;
        record.reason = invalidReason;
        record.gpsRateHz = rate;
        record.sampleCount = samples.size();

        List<TestSample> copy = new ArrayList<>(samples);
        Collections.sort(copy, Comparator.comparingLong(s -> s.tMs));
        record.samples.addAll(copy);
        if (mode.hasSegments()) {
            record.segments.addAll(PerformanceCalculator.segments(
                    copy, 0.0, endMs, mode.startThresholdKmh, mode.endKmh));
        }
        return record;
    }

    private String classifyQuality(double rate, long maxGap) {
        if (rate >= 5.0 && maxGap <= 1_000L) return TestRecord.QUALITY_GOOD;
        if (rate >= 1.0 && maxGap <= 2_200L) return TestRecord.QUALITY_FAIR;
        return TestRecord.QUALITY_POOR;
    }

    /**
     * 只用于静止起步项目的辅助检测。必须由 GNSS 速度确认；不参与终点。
     */
    public void onAccel(float linearMagnitude, float gyroMagnitude, long eventNs) {
        if (!assistEnabled || !mode.isStationary() || state != State.WAITING_LAUNCH) return;

        if (linearMagnitude >= ACCEL_THRESHOLD_MPS2 && gyroMagnitude <= GYRO_MAX_RAD_S) {
            if (accelSustainStartNs == 0L) accelSustainStartNs = eventNs;
            if (eventNs - accelSustainStartNs >= ACCEL_SUSTAIN_NS) {
                if (!assistCandidateActive) {
                    assistCandidateActive = true;
                    assistCandidateNs = eventNs;
                    assistDeadlineNs = eventNs + ACCEL_CONFIRM_NS;
                    assistTriggeredEver = true;
                    notifyUpdate();
                }
                if (linearMagnitude > assistPeak) assistPeak = linearMagnitude;
            }
        } else {
            accelSustainStartNs = 0L;
        }
    }

    public void tick(long nowElapsedNs) {
        if (state == State.WAITING_LAUNCH && assistCandidateActive
                && nowElapsedNs > assistDeadlineNs) {
            assistCandidateActive = false;
            assistDeadlineNs = 0L;
            accelSustainStartNs = 0L;
            assistRejected++;
            notifyUpdate();
        }

        if (state == State.RUNNING && lastFixNs > 0L
                && nowElapsedNs - lastFixNs > MAX_GAP_NS) {
            invalidate("GPS 中断");
            return;
        }

        if (state == State.FINISH_PENDING && finishCandidateFixNs > 0L
                && nowElapsedNs - finishCandidateFixNs > MAX_GAP_NS) {
            invalidate("终点确认超时");
            return;
        }

        if (state == State.RUNNING && t0Ns > 0L
                && nowElapsedNs - t0Ns > mode.timeoutNs) {
            invalidate("超时");
        }
    }

    public void cancel() {
        if (state == State.RUNNING || state == State.FINISH_PENDING) {
            invalidate("已取消");
        } else if (state == State.FINISHED || state == State.INVALID) {
            reset();
        } else {
            reset();
        }
    }

    public void stopAndInvalidate() {
        if (state == State.RUNNING || state == State.FINISH_PENDING) {
            invalidate("退到后台，已中止");
        }
    }

    public void reset() {
        state = mode.isRolling() ? State.WAITING_ROLL_START : State.WAITING_STANDSTILL;
        assistCandidateActive = false;
        assistTriggeredEver = false;
        assistValidated = false;
        assistLeadMs = 0L;
        assistRejected = 0;
        assistPeak = 0.0;
        accelSustainStartNs = 0L;
        assistDeadlineNs = 0L;
        stationarySinceNs = 0L;
        stationaryCount = 0;
        lastFixNs = 0L;
        lastSpeedKmh = 0.0;
        t0Ns = 0L;
        tEndNs = 0L;
        startWallMs = 0L;
        endWallMs = 0L;
        finishCandidateFixNs = 0L;
        finishCandidateTEndNs = 0L;
        finishCandidateWallMs = 0L;
        maxRunSpeedKmh = 0.0;
        endSpeedKmhAtFinish = Double.NaN;
        distanceAtLastSample = 0.0;
        distanceSampleTMs = 0L;
        distanceSampleSpeedMps = 0.0;
        startConfirmed = false;
        resultDelivered = false;
        invalidReason = "";
        quality = TestRecord.QUALITY_INSUFFICIENT;
        roll.clear();
        samples.clear();
        notifyUpdate();
    }

    private void addToRoll(GnssFix fix) {
        roll.add(fix);
        long cutoff = fix.elapsedNanos - PRE_ROLL_NS;
        while (!roll.isEmpty() && roll.get(0).elapsedNanos < cutoff) {
            roll.remove(0);
        }
    }

    private void addSample(GnssFix fix, long tMs) {
        if (!samples.isEmpty()) {
            TestSample last = samples.get(samples.size() - 1);
            if (last.tMs == tMs) return;
        }
        samples.add(new TestSample(
                tMs,
                fix.wallMillis,
                fix.speedMps,
                fix.accuracyM,
                fix.speedAccuracyMps,
                fix.latitude,
                fix.longitude
        ));
    }

    private long interpolateWall(GnssFix a, GnssFix b, double t) {
        if (a.wallMillis <= 0L) return b.wallMillis > 0L ? b.wallMillis : System.currentTimeMillis();
        if (b.wallMillis <= 0L) return a.wallMillis;
        long dt = b.elapsedNanos - a.elapsedNanos;
        if (dt <= 0L) return a.wallMillis;
        double f = (t - a.elapsedNanos) / dt;
        f = Math.max(0.0, Math.min(1.0, f));
        return Math.round(a.wallMillis + f * (b.wallMillis - a.wallMillis));
    }

    private long interpolateWall(TestSample a, GnssFix b) {
        if (a.wallMs <= 0L) return b.wallMillis > 0L ? b.wallMillis : System.currentTimeMillis();
        if (b.wallMillis <= 0L) return a.wallMs;
        long aNs = t0Ns + a.tMs * 1_000_000L;
        long dt = b.elapsedNanos - aNs;
        if (dt <= 0L) return b.wallMillis;
        double f = (finishCandidateTEndNs - aNs) / (double) dt;
        f = Math.max(0.0, Math.min(1.0, f));
        return Math.round(a.wallMs + f * (b.wallMillis - a.wallMs));
    }

    private long interpolateWallAtMs(double tMs) {
        double targetNs = t0Ns + tMs * 1_000_000.0;
        TestSample prev = null;
        for (TestSample s : samples) {
            if (s.wallMs <= 0L) continue;
            if (s.tMs >= tMs) {
                if (prev == null) return s.wallMs;
                double dt = s.tMs - prev.tMs;
                if (dt <= 0.0) return s.wallMs;
                double f = (tMs - prev.tMs) / dt;
                return Math.round(prev.wallMs + f * (s.wallMs - prev.wallMs));
            }
            prev = s;
        }
        return prev != null ? prev.wallMs : System.currentTimeMillis();
    }

    private void notifyUpdate() {
        if (listener != null) listener.onSessionUpdated();
    }
}
