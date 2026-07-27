package com.suanla.relayq.core.retry;

import com.suanla.relayq.core.config.RelayqProperties;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

public class ExponentialJitterBackoff implements BackoffPolicy {

    private final long baseDelayMs;
    private final double multiplier;
    private final long maxDelayMs;
    private final double jitterRatio;
    private final DoubleSupplier randomSupplier;

    public ExponentialJitterBackoff(RelayqProperties.Retry properties) {
        this(
                Objects.requireNonNull(properties, "retry properties must not be null")
                        .getBaseDelayMs(),
                properties.getMultiplier(),
                properties.getMaxDelayMs(),
                properties.getJitterRatio());
    }

    public ExponentialJitterBackoff(
            long baseDelayMs,
            double multiplier,
            long maxDelayMs,
            double jitterRatio) {
        this(
                baseDelayMs,
                multiplier,
                maxDelayMs,
                jitterRatio,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    public ExponentialJitterBackoff(
            long baseDelayMs,
            double multiplier,
            long maxDelayMs,
            double jitterRatio,
            DoubleSupplier randomSupplier) {
        if (baseDelayMs < 0L) {
            throw new IllegalArgumentException("baseDelayMs must not be negative: " + baseDelayMs);
        }
        if (!Double.isFinite(multiplier) || multiplier < 1.0D) {
            throw new IllegalArgumentException("multiplier must be finite and at least 1");
        }
        if (maxDelayMs < 0L) {
            throw new IllegalArgumentException("maxDelayMs must not be negative: " + maxDelayMs);
        }
        if (!Double.isFinite(jitterRatio) || jitterRatio < 0.0D || jitterRatio > 1.0D) {
            throw new IllegalArgumentException("jitterRatio must be between 0 and 1");
        }
        this.baseDelayMs = baseDelayMs;
        this.multiplier = multiplier;
        this.maxDelayMs = maxDelayMs;
        this.jitterRatio = jitterRatio;
        this.randomSupplier = Objects.requireNonNull(
                randomSupplier, "randomSupplier must not be null");
    }

    @Override
    public Duration nextDelay(int retryNumber) {
        if (retryNumber < 1) {
            throw new IllegalArgumentException("retryNumber must be at least 1: " + retryNumber);
        }
        double cappedExponential = cappedExponentialDelay(retryNumber);
        double random = randomSupplier.getAsDouble();
        if (!Double.isFinite(random) || random < 0.0D || random >= 1.0D) {
            throw new IllegalStateException("randomSupplier must return a value in [0, 1)");
        }
        double jitterFactor = 1.0D + ((random * 2.0D) - 1.0D) * jitterRatio;
        // 最终再封顶，避免基础值已到上限时正向抖动突破 maxDelay。
        double jittered = Math.min(cappedExponential * jitterFactor, maxDelayMs);
        long delayMs = Math.max(0L, Math.min(Math.round(jittered), maxDelayMs));
        return Duration.ofMillis(delayMs);
    }

    private double cappedExponentialDelay(int retryNumber) {
        if (baseDelayMs == 0L || maxDelayMs == 0L) {
            return 0.0D;
        }
        if (baseDelayMs >= maxDelayMs || multiplier == 1.0D) {
            return Math.min(baseDelayMs, maxDelayMs);
        }

        /*
         * 先用对数判断是否已经触顶，只有安全区间才调用 pow。
         * 这样 retryNumber 接近 Integer.MAX_VALUE 时不会先生成 Infinity 再补救。
         */
        double exponent = retryNumber - 1.0D;
        double exponentAtCap = Math.log((double) maxDelayMs / baseDelayMs)
                / Math.log(multiplier);
        if (exponent >= exponentAtCap) {
            return maxDelayMs;
        }
        return Math.min(baseDelayMs * Math.pow(multiplier, exponent), maxDelayMs);
    }
}
