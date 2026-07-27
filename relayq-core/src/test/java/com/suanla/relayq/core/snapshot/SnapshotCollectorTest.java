package com.suanla.relayq.core.snapshot;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import org.junit.jupiter.api.Test;

import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotCollectorTest {

    @Test
    void highThreadCountDisablesLockInformation() {
        ThreadMXBean threadMxBean = mock(ThreadMXBean.class);
        when(threadMxBean.getThreadCount()).thenReturn(101);
        when(threadMxBean.dumpAllThreads(false, false)).thenReturn(new ThreadInfo[0]);
        MemoryMXBean memoryMxBean = mock(MemoryMXBean.class);
        when(memoryMxBean.getHeapMemoryUsage()).thenReturn(new MemoryUsage(0L, 1L, 2L, 3L));
        RelayqProperties.Snapshot properties = new RelayqProperties.Snapshot();
        properties.setLockInfoThreadLimit(100);
        SnapshotCollector collector = new SnapshotCollector(
                threadMxBean,
                memoryMxBean,
                properties,
                RelayqMetrics.noop(),
                System::nanoTime);

        SnapshotCapture capture = collector.collect(SnapshotTrigger.manual(1L, 1));

        assertFalse(capture.lockInfoIncluded());
        verify(threadMxBean).dumpAllThreads(false, false);
    }
}
