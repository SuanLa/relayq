package com.suanla.relayq.core.metrics;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.domain.TaskStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelayqMetricsTest {

    @Test
    void allBacklogGaugesShareOneDatabaseRefreshInsideCacheWindow() {
        AtomicInteger queryCount = new AtomicInteger();
        RelayqMetrics.BacklogProvider provider = () -> {
            queryCount.incrementAndGet();
            return Map.of(
                    TaskStatus.PENDING, 7L,
                    TaskStatus.DEAD, 2L);
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RelayqProperties.Metrics properties = new RelayqProperties.Metrics();
        properties.setBacklogCacheSeconds(30L);
        RelayqMetrics metrics = new RelayqMetrics(registry, provider, properties);

        assertEquals(7.0D, backlogGauge(registry, TaskStatus.PENDING));
        assertEquals(2.0D, backlogGauge(registry, TaskStatus.DEAD));
        assertEquals(0.0D, backlogGauge(registry, TaskStatus.RUNNING));
        assertEquals(7L, metrics.pendingBacklogCount());
        assertEquals(1, queryCount.get());
    }

    private double backlogGauge(SimpleMeterRegistry registry, TaskStatus status) {
        return registry.get("relayq.task.backlog")
                .tag("status", status.name())
                .gauge()
                .value();
    }
}
