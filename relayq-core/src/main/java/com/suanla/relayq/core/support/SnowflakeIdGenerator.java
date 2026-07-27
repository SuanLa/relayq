package com.suanla.relayq.core.support;

import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

public class SnowflakeIdGenerator {

    private static final long EPOCH = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long MAX_TIMESTAMP_DELTA = (1L << 41) - 1;
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;
    private static final long DEFAULT_MAX_CLOCK_BACKWARD_MS = 5L;

    private final long workerId;
    private final long maxClockBackwardMs;
    private final LongSupplier clock;

    private long lastTimestamp = -1L;
    private long sequence;

    public SnowflakeIdGenerator(long workerId) {
        this(workerId, DEFAULT_MAX_CLOCK_BACKWARD_MS);
    }

    public SnowflakeIdGenerator(long workerId, long maxClockBackwardMs) {
        this(workerId, maxClockBackwardMs, System::currentTimeMillis);
    }

    public SnowflakeIdGenerator(long workerId, long maxClockBackwardMs, LongSupplier clock) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "workerId must be between 0 and " + MAX_WORKER_ID + " (inclusive): " + workerId);
        }
        if (maxClockBackwardMs < 0) {
            throw new IllegalArgumentException(
                    "maxClockBackwardMs must not be negative: " + maxClockBackwardMs);
        }
        this.workerId = workerId;
        this.maxClockBackwardMs = maxClockBackwardMs;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 状态只有上一毫秒与序列号，临界区极短；同步锁比更复杂的无锁 CAS 更容易保证回拨与溢出语义一致。
     */
    public synchronized long nextId() {
        long timestamp = clock.getAsLong();
        if (timestamp < lastTimestamp) {
            long backwardMs = lastTimestamp - timestamp;
            if (backwardMs > maxClockBackwardMs) {
                throw new IllegalStateException(
                        "clock moved backwards by " + backwardMs
                                + "ms, refusing to generate a possibly duplicate id");
            }
            timestamp = waitUntil(lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }

        long timestampDelta = timestamp - EPOCH;
        if (timestampDelta < 0 || timestampDelta > MAX_TIMESTAMP_DELTA) {
            throw new IllegalStateException(
                    "timestamp delta is outside the 41-bit Snowflake ID range: " + timestampDelta);
        }

        lastTimestamp = timestamp;
        return (timestampDelta << TIMESTAMP_SHIFT) | (workerId << WORKER_ID_SHIFT) | sequence;
    }

    private long waitUntil(long targetTimestamp) {
        long timestamp;
        do {
            Thread.onSpinWait();
            timestamp = clock.getAsLong();
            long backwardMs = lastTimestamp - timestamp;
            if (backwardMs > maxClockBackwardMs) {
                throw new IllegalStateException(
                        "clock moved backwards by " + backwardMs
                                + "ms while waiting, refusing to generate a possibly duplicate id");
            }
        } while (timestamp < targetTimestamp);
        return timestamp;
    }
}
