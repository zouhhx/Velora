package com.zouhu.speedlimit.feature.accel.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 零百状态机的纯逻辑测试：用构造的 GNSS 序列验证自动起步、终点、辅助验证和无效处理。
 * 不连接真实设备，也不代表真车精度。
 */
public class ZeroHundredSessionTest {

    private static final long BASE_NS = 10_000_000_000L;
    private static final long BASE_WALL = 1_700_000_000_000L;

    @Test
    public void automaticRunFromStandstill() {
        Recorder recorder = new Recorder();
        ZeroHundredSession session = new ZeroHundredSession(recorder);

        fix(session, 0.0, 0.0);
        fix(session, 1.0, 0.0);
        fix(session, 2.0, 0.0);
        assertEquals(ZeroHundredSession.State.WAITING_LAUNCH, session.getState());

        fix(session, 3.0, 0.0);
        fix(session, 4.0, 20.0);
        fix(session, 5.0, 50.0);
        fix(session, 6.0, 80.0);
        fix(session, 7.0, 100.0);
        assertEquals(ZeroHundredSession.State.FINISH_PENDING, session.getState());
        fix(session, 8.0, 100.0);

        assertEquals(ZeroHundredSession.State.FINISHED, session.getState());
        assertNotNull(recorder.record);
        assertTrue(recorder.record.valid);
        assertEquals(3975L, recorder.record.durationMs, 2L);
        assertEquals(BASE_NS + 3_025_000_000L, session.getT0Ns(), 1_000L);
        assertEquals(10, recorder.record.segments.size());
        assertEquals(TestRecord.QUALITY_FAIR, recorder.record.quality);
        assertTrue(recorder.record.totalG > 0.5 && recorder.record.totalG < 1.0);
    }

    @Test
    public void assistCandidateMustBeValidatedByGnss() {
        Recorder recorder = new Recorder();
        ZeroHundredSession session = new ZeroHundredSession(recorder);
        session.setAssistEnabled(true);

        fix(session, 0.0, 0.0);
        fix(session, 1.0, 0.0);
        fix(session, 2.0, 0.0);
        assertEquals(ZeroHundredSession.State.WAITING_LAUNCH, session.getState());

        session.onAccel(2.0f, 0.1f, BASE_NS + 2_500_000_000L);
        session.onAccel(2.2f, 0.1f, BASE_NS + 2_700_000_000L);
        assertTrue(session.isAssistTriggeredEver());

        fix(session, 3.0, 0.0);
        fix(session, 4.0, 20.0);
        fix(session, 5.0, 50.0);
        fix(session, 6.0, 80.0);
        fix(session, 7.0, 100.0);
        fix(session, 8.0, 100.0);

        assertEquals(ZeroHundredSession.State.FINISHED, session.getState());
        assertNotNull(recorder.record);
        assertTrue(recorder.record.assistEnabled);
        assertTrue(recorder.record.assistTriggered);
        assertTrue(recorder.record.assistValidated);
        assertTrue(recorder.record.assistLeadMs > 0L);
    }

    @Test
    public void falseAccelTriggerIsRejectedWithoutGnssMovement() {
        Recorder recorder = new Recorder();
        ZeroHundredSession session = new ZeroHundredSession(recorder);
        session.setAssistEnabled(true);

        fix(session, 0.0, 0.0);
        fix(session, 1.0, 0.0);
        fix(session, 2.0, 0.0);
        session.onAccel(2.0f, 0.1f, BASE_NS + 2_500_000_000L);
        session.onAccel(2.2f, 0.1f, BASE_NS + 2_700_000_000L);
        session.tick(BASE_NS + 5_000_000_000L);

        assertEquals(ZeroHundredSession.State.WAITING_LAUNCH, session.getState());
        assertEquals(1, session.getAssistRejected());
        assertTrue(session.isAssistTriggeredEver());
        assertTrue(!session.isAssistValidated());
    }

    @Test
    public void rollingSixtyTo160StartsAtThreshold() {
        Recorder recorder = new Recorder();
        ZeroHundredSession session = new ZeroHundredSession(recorder, TestMode.SIXTY_160);

        fix(session, 0.0, 50.0);
        fix(session, 1.0, 55.0);
        fix(session, 2.0, 65.0);
        fix(session, 3.0, 80.0);
        fix(session, 4.0, 100.0);
        fix(session, 5.0, 120.0);
        fix(session, 6.0, 140.0);
        fix(session, 7.0, 155.0);
        fix(session, 8.0, 165.0);
        assertEquals(ZeroHundredSession.State.FINISH_PENDING, session.getState());
        fix(session, 9.0, 165.0);

        assertEquals(ZeroHundredSession.State.FINISHED, session.getState());
        assertNotNull(recorder.record);
        assertTrue(recorder.record.valid);
        assertEquals(TestMode.SIXTY_160, recorder.record.mode);
        assertEquals(BASE_NS + 1_500_000_000L, session.getT0Ns(), 1_000L);
        assertEquals(6000L, recorder.record.durationMs, 10L);
        assertEquals(10, recorder.record.segments.size());
        assertEquals("60–70", recorder.record.segments.get(0).label());
    }

    @Test
    public void rollingHundredTo200StartsAtThreshold() {
        Recorder recorder = new Recorder();
        ZeroHundredSession session = new ZeroHundredSession(recorder, TestMode.HUNDRED_200);

        fix(session, 0.0, 90.0);
        fix(session, 1.0, 95.0);
        fix(session, 2.0, 105.0);
        fix(session, 3.0, 130.0);
        fix(session, 4.0, 160.0);
        fix(session, 5.0, 190.0);
        fix(session, 6.0, 205.0);
        assertEquals(ZeroHundredSession.State.FINISH_PENDING, session.getState());
        fix(session, 7.0, 205.0);

        assertEquals(ZeroHundredSession.State.FINISHED, session.getState());
        assertNotNull(recorder.record);
        assertTrue(recorder.record.valid);
        assertEquals(TestMode.HUNDRED_200, recorder.record.mode);
        assertEquals(BASE_NS + 1_500_000_000L, session.getT0Ns(), 1_000L);
        assertEquals(10, recorder.record.segments.size());
        assertEquals("100–110", recorder.record.segments.get(0).label());
    }

    @Test
    public void zeroTo200HasTwentySegments() {
        Recorder recorder = new Recorder();
        ZeroHundredSession session = new ZeroHundredSession(recorder, TestMode.ZERO_200);

        fix(session, 0.0, 0.0);
        fix(session, 1.0, 0.0);
        fix(session, 2.0, 0.0);
        fix(session, 3.0, 20.0);
        fix(session, 4.0, 80.0);
        fix(session, 5.0, 140.0);
        fix(session, 6.0, 200.0);
        assertEquals(ZeroHundredSession.State.FINISH_PENDING, session.getState());
        fix(session, 7.0, 200.0);

        assertEquals(ZeroHundredSession.State.FINISHED, session.getState());
        assertNotNull(recorder.record);
        assertTrue(recorder.record.valid);
        assertEquals(TestMode.ZERO_200, recorder.record.mode);
        assertEquals(20, recorder.record.segments.size());
        assertEquals("0–10", recorder.record.segments.get(0).label());
        assertEquals("190–200", recorder.record.segments.get(19).label());
    }

    @Test
    public void zeroTo400MetersUsesDistanceAndEndSpeed() {
        Recorder recorder = new Recorder();
        ZeroHundredSession session = new ZeroHundredSession(recorder, TestMode.ZERO_400M);

        // 静止 2 秒，然后 5 m/s² 加速；约 12.65 s 达到 400 m。
        for (int second = 0; second <= 18; second++) {
            double kmh;
            if (second <= 2) {
                kmh = 0.0;
            } else {
                kmh = 5.0 * (second - 2) * 3.6;
            }
            fix(session, second, kmh);
        }

        assertEquals(ZeroHundredSession.State.FINISHED, session.getState());
        assertNotNull(recorder.record);
        assertTrue(recorder.record.valid);
        assertEquals(TestMode.ZERO_400M, recorder.record.mode);
        assertTrue(recorder.record.durationMs > 11_000L);
        assertTrue(recorder.record.durationMs < 14_000L);
        assertTrue(recorder.record.endSpeedKmh > 200.0);
        assertTrue(recorder.record.segments.isEmpty());
        assertTrue(Double.isNaN(recorder.record.totalG));
    }

    @Test
    public void signalGapInvalidatesAndKeepsRawSamples() {
        Recorder recorder = new Recorder();
        ZeroHundredSession session = new ZeroHundredSession(recorder);

        fix(session, 0.0, 0.0);
        fix(session, 1.0, 0.0);
        fix(session, 2.0, 0.0);
        fix(session, 3.0, 20.0);
        fix(session, 4.0, 50.0);
        session.tick(BASE_NS + 8_000_000_000L);

        assertEquals(ZeroHundredSession.State.INVALID, session.getState());
        assertNotNull(recorder.record);
        assertTrue(!recorder.record.valid);
        assertEquals(TestRecord.QUALITY_INTERRUPTED, recorder.record.quality);
        assertTrue(recorder.record.sampleCount > 0);
    }

    @Test
    public void finishConfirmationTimesOutWithoutNextFix() {
        Recorder recorder = new Recorder();
        ZeroHundredSession session = new ZeroHundredSession(recorder);

        fix(session, 0.0, 0.0);
        fix(session, 1.0, 0.0);
        fix(session, 2.0, 0.0);
        fix(session, 3.0, 20.0);
        fix(session, 4.0, 50.0);
        fix(session, 5.0, 100.0);
        assertEquals(ZeroHundredSession.State.FINISH_PENDING, session.getState());

        session.tick(BASE_NS + 8_000_000_000L);

        assertEquals(ZeroHundredSession.State.INVALID, session.getState());
        assertNotNull(recorder.record);
        assertTrue(!recorder.record.valid);
        assertEquals(TestRecord.QUALITY_INSUFFICIENT, recorder.record.quality);
    }

    private static void fix(ZeroHundredSession session, double seconds, double kmh) {
        session.onGnssFix(new GnssFix(
                BASE_NS + Math.round(seconds * 1_000_000_000.0),
                BASE_WALL + Math.round(seconds * 1000.0),
                kmh / 3.6,
                3.0,
                0.2,
                0.0,
                0.0
        ));
    }

    private static final class Recorder implements ZeroHundredSession.Listener {
        TestRecord record;
        final List<TestRecord> finished = new ArrayList<>();

        @Override
        public void onSessionUpdated() {
        }

        @Override
        public void onTestFinished(TestRecord record) {
            this.record = record;
            if (record != null) finished.add(record);
        }
    }
}
