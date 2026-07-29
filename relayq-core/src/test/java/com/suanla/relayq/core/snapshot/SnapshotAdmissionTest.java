package com.suanla.relayq.core.snapshot;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.domain.TriggerType;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotAdmissionTest {

    private final List<SnapshotAdmission> admissions = new ArrayList<>();

    @AfterEach
    void tearDown() {
        admissions.forEach(SnapshotAdmission::close);
    }

    @Test
    void sameTaskAttemptCapturedOnlyOnceAcrossTwoTriggers() {
        Fixture fixture = fixture(properties(8, 60, 20), immediateCollector(), true);
        SnapshotTrigger dead = trigger(11L, 3, TriggerType.DEAD);
        SnapshotTrigger threshold = trigger(11L, 3, TriggerType.FAIL_THRESHOLD);

        fixture.admission().admit(dead);
        fixture.admission().admit(threshold);
        awaitOutcomes(fixture.registry(), 2);

        verify(fixture.collector()).collect(dead);
        assertEquals(1.0D, snapshotCount(fixture.registry(), "captured"));
        assertEquals(1.0D, snapshotCount(fixture.registry(), "throttled"));
    }

    @Test
    void sameTaskIsThrottledDuringCooldownEvenForAnotherAttempt() {
        Fixture fixture = fixture(properties(8, 60, 20), immediateCollector(), true);

        fixture.admission().admit(trigger(12L, 1, TriggerType.FAIL_THRESHOLD));
        awaitOutcomes(fixture.registry(), 1);
        fixture.admission().admit(trigger(12L, 2, TriggerType.FAIL_THRESHOLD));
        awaitOutcomes(fixture.registry(), 2);

        assertEquals(1.0D, snapshotCount(fixture.registry(), "captured"));
        assertEquals(1.0D, snapshotCount(fixture.registry(), "throttled"));
    }

    @Test
    void tokenBucketLimitsStormAcrossOneThousandDifferentTasks() {
        int ratePerMinute = 7;
        Fixture fixture = fixture(
                properties(1_000, 0, ratePerMinute),
                immediateCollector(),
                true);

        for (int index = 0; index < 1_000; index++) {
            fixture.admission().admit(
                    trigger(10_000L + index, 1, TriggerType.FAIL_THRESHOLD));
        }
        awaitOutcomes(fixture.registry(), 1_000);

        assertEquals(ratePerMinute, snapshotCount(fixture.registry(), "captured"));
        assertEquals(1_000 - ratePerMinute, snapshotCount(fixture.registry(), "throttled"));
        assertEquals(0.0D, snapshotCount(fixture.registry(), "dropped"));
    }

    @Test
    void fullQueueDiscardsWithoutBlockingOrThrowing() throws Exception {
        CountDownLatch collectorEntered = new CountDownLatch(1);
        CountDownLatch releaseCollector = new CountDownLatch(1);
        SnapshotCollector collector = mock(SnapshotCollector.class);
        when(collector.collect(any())).thenAnswer(invocation -> {
            collectorEntered.countDown();
            assertTrue(releaseCollector.await(5, TimeUnit.SECONDS));
            return capture(invocation.getArgument(0));
        });
        Fixture fixture = fixture(properties(1, 0, 20), collector, true);

        fixture.admission().admit(trigger(21L, 1, TriggerType.MANUAL));
        assertTrue(collectorEntered.await(5, TimeUnit.SECONDS));
        fixture.admission().admit(trigger(22L, 1, TriggerType.MANUAL));
        assertTimeoutPreemptively(
                Duration.ofMillis(250),
                () -> fixture.admission().admit(trigger(23L, 1, TriggerType.MANUAL)));

        assertEquals(1.0D, snapshotCount(fixture.registry(), "dropped"));
        releaseCollector.countDown();
        awaitOutcomes(fixture.registry(), 3);
    }

    @Test
    void manualTriggerBypassesDeduplication() {
        // cooldown 必须为 0，否则挡住第二次的是冷却而不是去重，测不出想测的东西
        Fixture fixture = fixture(properties(8, 0, 20), immediateCollector(), true);

        fixture.admission().admit(trigger(31L, 4, TriggerType.DEAD));
        fixture.admission().triggerManual(31L, 4);
        awaitOutcomes(fixture.registry(), 2);

        assertEquals(2.0D, snapshotCount(fixture.registry(), "captured"));
        assertEquals(0.0D, snapshotCount(fixture.registry(), "throttled"));
    }

    @Test
    void manualTriggerStillRespectsCooldown() {
        Fixture fixture = fixture(properties(8, 60, 20), immediateCollector(), true);

        fixture.admission().triggerManual(32L, 1);
        awaitOutcomes(fixture.registry(), 1);
        fixture.admission().triggerManual(32L, 2);
        awaitOutcomes(fixture.registry(), 2);

        assertEquals(1.0D, snapshotCount(fixture.registry(), "captured"));
        assertEquals(1.0D, snapshotCount(fixture.registry(), "throttled"));
    }

    @Test
    void manualTriggerDoesNotPolluteDeduplicationTable() {
        // 同样要 cooldown=0，否则无法区分「被冷却挡住」和「被污染的去重表挡住」
        Fixture fixture = fixture(properties(8, 0, 20), immediateCollector(), true);

        fixture.admission().triggerManual(33L, 5);
        awaitOutcomes(fixture.registry(), 1);

        SnapshotTrigger auto = trigger(33L, 5, TriggerType.FAIL_THRESHOLD);
        fixture.admission().admit(auto);
        awaitOutcomes(fixture.registry(), 2);

        verify(fixture.collector()).collect(auto);
        assertEquals(2.0D, snapshotCount(fixture.registry(), "captured"));
        assertEquals(0.0D, snapshotCount(fixture.registry(), "throttled"));
    }

    @Test
    void nullTaskIdIsRejectedBeforeReachingThePool() {
        Fixture fixture = fixture(properties(8, 0, 20), immediateCollector(), true);

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.admission().triggerManual(null, null));
        assertEquals(0.0D, snapshotCount(fixture.registry(), "captured"));
    }

    @Test
    void disabledAdmissionIsACompleteNoop() {
        RelayqProperties.Snapshot properties = properties(1, 0, 1);
        properties.setEnabled(false);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayqMetrics metrics = new RelayqMetrics(
                registry,
                () -> Map.of(),
                new RelayqProperties.Metrics());
        SnapshotAdmission admission = new SnapshotAdmission(
                properties,
                null,
                null,
                metrics);
        admissions.add(admission);

        assertFalse(admission.isEnabled());
        assertDoesNotThrow(() -> admission.triggerManual(41L, 1));
        assertEquals(0.0D, snapshotCount(registry, "captured"));
        assertEquals(0.0D, snapshotCount(registry, "throttled"));
        assertEquals(0.0D, snapshotCount(registry, "dropped"));
        assertEquals(0.0D, snapshotCount(registry, "failed"));
    }

    private Fixture fixture(
            RelayqProperties.Snapshot properties,
            SnapshotCollector collector,
            boolean writerSucceeds) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayqMetrics metrics = new RelayqMetrics(
                registry,
                () -> Map.of(),
                new RelayqProperties.Metrics());
        SnapshotWriter writer = mock(SnapshotWriter.class);
        when(writer.write(any())).thenReturn(writerSucceeds);
        SnapshotAdmission admission = new SnapshotAdmission(
                properties,
                collector,
                writer,
                metrics);
        admissions.add(admission);
        return new Fixture(admission, collector, registry);
    }

    private SnapshotCollector immediateCollector() {
        SnapshotCollector collector = mock(SnapshotCollector.class);
        when(collector.collect(any())).thenAnswer(
                invocation -> capture(invocation.getArgument(0)));
        return collector;
    }

    private SnapshotCapture capture(SnapshotTrigger trigger) {
        return new SnapshotCapture(
                trigger,
                "thread dump",
                true,
                Map.of(),
                Map.of(),
                0L);
    }

    private SnapshotTrigger trigger(long taskId, int attemptNo, TriggerType triggerType) {
        return new SnapshotTrigger(
                taskId,
                99L,
                attemptNo,
                triggerType,
                "test-handler",
                attemptNo - 1,
                3,
                null,
                null);
    }

    private RelayqProperties.Snapshot properties(
            int queueCapacity,
            long cooldownSeconds,
            int ratePerMinute) {
        RelayqProperties.Snapshot properties = new RelayqProperties.Snapshot();
        properties.setQueueCapacity(queueCapacity);
        properties.setCooldownSeconds(cooldownSeconds);
        properties.setRatePerMinute(ratePerMinute);
        return properties;
    }

    private void awaitOutcomes(SimpleMeterRegistry registry, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        double actual;
        do {
            actual = snapshotCount(registry, "captured")
                    + snapshotCount(registry, "throttled")
                    + snapshotCount(registry, "dropped")
                    + snapshotCount(registry, "failed");
            if (actual == expected) {
                return;
            }
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        assertEquals(expected, actual);
    }

    private double snapshotCount(SimpleMeterRegistry registry, String outcome) {
        return registry.get("relayq.snapshot")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private record Fixture(
            SnapshotAdmission admission,
            SnapshotCollector collector,
            SimpleMeterRegistry registry) {
    }
}
